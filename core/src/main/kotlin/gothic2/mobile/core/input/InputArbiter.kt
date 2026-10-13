package gothic2.mobile.core.input

/**
 * Единый арбитр ввода. Все источники (касания, геймпад, служебные операции) проходят
 * через него, и только он решает, какие клавиши удерживаются.
 *
 * Приоритеты (раздел 10 плана):
 *  1. Меню и диалог — движение, камера и бой отключены.
 *  2. Блок или текущий удар — подавляют движение на время комбинации.
 *  3. Управление целью — камера не срывает фиксацию.
 *  4. Движение.
 *  5. Камера.
 *
 * Один намеренный ввод игрока = одно действие: удары не ставятся в очередь,
 * повторный удар во время текущего отклоняется.
 *
 * Не потокобезопасен: вызывать из одного потока (UI-поток Android).
 * Время берётся из [clock]; после каждого события и в моменты [nextDeadline]
 * нужно вызывать [tick], чтобы фазы удара/нажатия отработали вовремя.
 */
class InputArbiter(
    private val sink: InputSink,
    private val bindings: KeyBindings = KeyBindings.GOTHIC2_DEFAULT,
    private val timing: CombatTiming = CombatTiming(),
    joystickConfig: JoystickMapper.Config = JoystickMapper.Config(),
    val camera: CameraMapper = CameraMapper(),
    private val clock: () -> Long,
) {
    /** true — взаимодействие с предметами требует «действие + вперёд» (проверить на сборке). */
    var interactUsesForward: Boolean = true

    /** Множитель камеры при зафиксированной цели: 0 — палец не срывает фиксацию. */
    var cameraScaleWhileTargetLocked: Float = 0f

    var mode: ControlMode = ControlMode.WORLD
        private set

    var targetLocked: Boolean = false
        private set

    private val tracker = KeyStateTracker(sink)
    private val joystick = JoystickMapper(joystickConfig)

    private var joyX = 0f
    private var joyY = 0f
    private val holds = LinkedHashMap<String, GameKey>()
    private val taps = HashMap<GameKey, Long>()
    private var contextHeld = false
    private var attack: Attack? = null
    private var blockStartedAt: Long? = null
    private var recoverUntil = Long.MIN_VALUE

    private data class Attack(val direction: AttackDirection, val startedAt: Long)

    // ---- события -----------------------------------------------------------------------

    fun setMode(newMode: ControlMode) {
        if (newMode == mode) return
        mode = newMode
        // Палец на джойстике физически остаётся — его состояние сохраняется.
        // Всё остальное, относящееся к прежней панели, снимается.
        holds.clear()
        taps.clear()
        contextHeld = false
        attack = null
        blockStartedAt = null
        targetLocked = false
        camera.discard()
        commit()
    }

    fun setTargetLocked(locked: Boolean) {
        targetLocked = locked
    }

    /** x — вправо, y — вперёд; диапазон −1..1. */
    fun onJoystick(x: Float, y: Float) {
        joyX = x.coerceIn(-1f, 1f)
        joyY = y.coerceIn(-1f, 1f)
        commit()
    }

    fun onJoystickReleased() = onJoystick(0f, 0f)

    fun onCameraDelta(dxPx: Float, dyPx: Float) {
        if (!mode.isInWorld) return
        val scale = if (targetLocked) cameraScaleWhileTargetLocked else 1f
        if (scale == 0f) return
        camera.add(dxPx * scale, dyPx * scale)
        camera.drain()?.let { (dx, dy) -> sink.mouseMove(dx, dy) }
    }

    /** Кнопка удерживает [key], пока не придёт [onHoldEnd] с тем же [holderId]. */
    fun onHoldStart(holderId: String, key: GameKey) {
        if (mode == ControlMode.LOADING) return
        holds[holderId] = key
        commit()
    }

    fun onHoldEnd(holderId: String) {
        if (holds.remove(holderId) != null) commit()
    }

    /** Одиночное нажатие с гарантированной минимальной длительностью удержания. */
    fun tap(key: GameKey) {
        if (mode == ControlMode.LOADING) return
        val now = clock()
        taps[key] = maxOf(taps[key] ?: Long.MIN_VALUE, now + timing.tapHoldMs)
        commit()
    }

    /** Контекстное действие (говорить, подобрать, использовать) в мирном режиме. */
    fun setContextAction(held: Boolean) {
        val allowed = held && mode == ControlMode.WORLD
        if (allowed == contextHeld) return
        contextHeld = allowed
        commit()
    }

    /** @return false, если удар отклонён (не боевой режим, идёт другой удар или блок). */
    fun requestAttack(direction: AttackDirection): Boolean {
        if (!mode.allowsMelee || attack != null || blockStartedAt != null) return false
        attack = Attack(direction, clock())
        commit()
        return true
    }

    fun setBlock(held: Boolean) {
        val now = clock()
        if (held) {
            if (!mode.allowsMelee || blockStartedAt != null) return
            attack = null // блок важнее начатого удара
            blockStartedAt = now
        } else {
            if (blockStartedAt == null) return
            blockStartedAt = null
            recoverUntil = now + timing.recoverMs
        }
        commit()
    }

    /** Полный сброс: потеря фокуса, ACTION_CANCEL, сворачивание, звонок. */
    fun releaseAll() {
        joyX = 0f
        joyY = 0f
        holds.clear()
        taps.clear()
        contextHeld = false
        attack = null
        blockStartedAt = null
        recoverUntil = Long.MIN_VALUE
        joystick.reset()
        camera.discard()
        tracker.releaseAll()
    }

    /** Пересчитать состояние по текущему времени (фазы ударов, окончание нажатий). */
    fun tick() = commit()

    /** Ближайший момент, когда состояние изменится само; null — ждать нечего. */
    fun nextDeadline(): Long? {
        val now = clock()
        val candidates = mutableListOf<Long>()
        candidates += taps.values
        attack?.let { a ->
            var t = a.startedAt
            for (d in listOf(timing.movementReleaseMs, timing.actionLeadMs, timing.strikeHoldMs, timing.recoverMs)) {
                t += d
                candidates += t
            }
        }
        blockStartedAt?.let { s ->
            candidates += s + timing.movementReleaseMs
            candidates += s + timing.movementReleaseMs + timing.actionLeadMs
        }
        candidates += recoverUntil
        return candidates.filter { it > now }.minOrNull()
    }

    fun isAttacking(): Boolean = attack != null

    fun isBlocking(): Boolean = blockStartedAt != null

    fun downScanCodes(): Set<Int> = tracker.downKeys()

    // ---- вычисление --------------------------------------------------------------------

    private fun commit() {
        val now = clock()
        val combat = combatKeys(now)
        val suppressMovement = combat.suppressMovement || now < recoverUntil
        val menuLike = !mode.isInWorld

        val movement: Set<GameKey> = when {
            menuLike || mode == ControlMode.LOADING -> {
                joystick.reset()
                emptySet()
            }
            else -> {
                val out = joystick.map(joyX, joyY).keys
                if (suppressMovement) emptySet() else out
            }
        }

        val context: Set<GameKey> = if (contextHeld && !suppressMovement) {
            if (interactUsesForward) setOf(GameKey.ACTION, GameKey.FORWARD) else setOf(GameKey.ACTION)
        } else {
            emptySet()
        }

        taps.entries.removeAll { it.value <= now }

        val buttonKeys = holds.values.toSet()
            .filterNot { suppressMovement && it in MOVEMENT_KEYS }
            .toSet()

        tracker.apply(
            mapOf(
                SRC_COMBAT to scans(combat.keys),
                SRC_JOYSTICK to scans(movement),
                SRC_CONTEXT to scans(context),
                SRC_BUTTONS to scans(buttonKeys),
                SRC_TAPS to scans(taps.keys),
            ),
        )
    }

    private class CombatOutput(val keys: Set<GameKey>, val suppressMovement: Boolean)

    private fun combatKeys(now: Long): CombatOutput {
        blockStartedAt?.let { start ->
            val t = now - start
            val keys = when {
                t < timing.movementReleaseMs -> emptySet()
                t < timing.movementReleaseMs + timing.actionLeadMs -> setOf(GameKey.ACTION)
                else -> setOf(GameKey.ACTION, GameKey.BACK)
            }
            return CombatOutput(keys, suppressMovement = true)
        }
        val a = attack ?: return CombatOutput(emptySet(), suppressMovement = false)
        val t = now - a.startedAt
        val p1 = timing.movementReleaseMs
        val p2 = p1 + timing.actionLeadMs
        val p3 = p2 + timing.strikeHoldMs
        val p4 = p3 + timing.recoverMs
        val keys = when {
            t < p1 -> emptySet()
            t < p2 -> setOf(GameKey.ACTION)
            t < p3 -> setOf(GameKey.ACTION, directionKey(a.direction))
            t < p4 -> emptySet()
            else -> {
                attack = null
                return CombatOutput(emptySet(), suppressMovement = false)
            }
        }
        return CombatOutput(keys, suppressMovement = true)
    }

    private fun directionKey(direction: AttackDirection): GameKey = when (direction) {
        AttackDirection.FORWARD -> GameKey.FORWARD
        AttackDirection.LEFT -> GameKey.TURN_LEFT
        AttackDirection.RIGHT -> GameKey.TURN_RIGHT
    }

    private fun scans(keys: Collection<GameKey>): Set<Int> = keys.mapTo(HashSet()) { bindings.scan(it) }

    private companion object {
        const val SRC_COMBAT = "combat"
        const val SRC_JOYSTICK = "joystick"
        const val SRC_CONTEXT = "context"
        const val SRC_BUTTONS = "buttons"
        const val SRC_TAPS = "taps"

        val MOVEMENT_KEYS = setOf(
            GameKey.FORWARD, GameKey.BACK, GameKey.STRAFE_LEFT, GameKey.STRAFE_RIGHT,
            GameKey.TURN_LEFT, GameKey.TURN_RIGHT, GameKey.ACTION,
        )
    }
}
