package gothic2.mobile.core.input

/**
 * Единый арбитр ввода. Все источники (касания, геймпад, служебные операции) проходят
 * через него, и только он решает, какие клавиши удерживаются.
 *
 * Приоритеты (раздел 10 плана):
 *  1. Меню и диалог — бой и камера отключены, джойстик работает как стрелки.
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
    /**
     * true — взаимодействие с предметами без оружия требует «действие + вперёд», как в
     * управлении Gothic 1 (`useGothic1Controls=1`); false — хватает одного «действия».
     * Проверить на сборке (docs/TEST_PLAN.md).
     */
    var interactUsesForward: Boolean = true

    /** Множитель камеры при зафиксированной цели: 0 — палец не срывает фиксацию. */
    var cameraScaleWhileTargetLocked: Float = 0f

    /**
     * Вызывается после каждого одиночного нажатия ([tap]) — по нему [gothic2.mobile.core.bridge.ModeController]
     * угадывает, какой экран открыла игра. Может сменить режим прямо из вызова.
     */
    var onKeyTapped: (GameKey) -> Unit = {}

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
    private var attack: Attack? = null
    private var strike: Strike? = null
    private var blockStartedAt: Long? = null
    private var recoverUntil = Long.MIN_VALUE

    private data class Attack(val direction: AttackDirection, val startedAt: Long)

    /**
     * Жест главной кнопки: палец на кнопке держит «действие» (прицел, готовность к удару),
     * движение пальца задаёт удары и блок, отпускание без движения — удар вперёд
     * (взаимодействие, выстрел, заклинание).
     */
    private class Strike(val startedAt: Long) {
        var fingerDown = true
        var fired = false
        var hit: Hit? = null
    }

    /** Направление удара: клавиша [key] вместе с «действием» с [start] до [end] (null — пока палец держит). */
    private class Hit(val key: GameKey?, val start: Long, var end: Long?)

    // ---- события -----------------------------------------------------------------------

    fun setMode(newMode: ControlMode) {
        if (newMode == mode) return
        val layerChanged = newMode.layer != mode.layer
        mode = newMode
        targetLocked = false
        if (layerChanged) {
            // Палец на джойстике физически остаётся — его состояние сохраняется (в меню он
            // становится стрелками). Всё остальное, относящееся к прежней панели, снимается.
            // Одиночные нажатия доигрываются: смена режима часто вызвана самим нажатием.
            holds.clear()
            attack = null
            strike = null
            blockStartedAt = null
            camera.discard()
        } else if (!newMode.allowsMelee) {
            // Оружие убрано: направленный удар геймпада не продолжается.
            attack = null
        }
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
        if (mode == ControlMode.LOADING && key != GameKey.ESCAPE) return
        val now = clock()
        taps[key] = maxOf(taps[key] ?: Long.MIN_VALUE, now + timing.tapHoldMs)
        commit()
        onKeyTapped(key)
    }

    // ---- главная кнопка: удар, действие, выстрел, блок -----------------------------------

    /**
     * Палец лёг на главную кнопку. Движение снимается, через [CombatTiming.movementReleaseMs]
     * нажимается «действие» и держится, пока палец на кнопке.
     * @return false, если жест отклонён (не мир, идёт другой удар или блок).
     */
    fun strikeBegin(): Boolean {
        if (!mode.isInWorld || strike != null || attack != null || blockStartedAt != null) return false
        strike = Strike(clock())
        commit()
        return true
    }

    /**
     * Палец сместился в сектор [dir] (null — вернулся в центр). Вверх — удар вперёд, пока палец
     * там (заряд заклинания, серия ударов); влево/вправо — один боковой удар; вниз — блок,
     * пока палец там. Новый удар во время текущего отклоняется — без очереди.
     */
    fun strikeAim(dir: SwipeDir?): Boolean {
        val s = strike ?: return false
        if (!s.fingerDown) return false
        val now = clock()
        s.hit?.let { h ->
            // Удержание вверх/вниз заканчивается, когда палец ушёл из сектора.
            if (h.end == null && directionOf(h.key) != dir) h.end = maxOf(now, h.start + timing.strikeHoldMs)
        }
        if (dir == null || hitActive(s, now)) {
            commit()
            return false
        }
        val start = maxOf(now, readyAt(s) + timing.actionLeadMs)
        s.hit = when (dir) {
            SwipeDir.UP -> Hit(GameKey.FORWARD, start, null)
            SwipeDir.DOWN -> Hit(GameKey.BACK, start, null)
            SwipeDir.LEFT -> Hit(GameKey.TURN_LEFT, start, start + timing.strikeHoldMs)
            SwipeDir.RIGHT -> Hit(GameKey.TURN_RIGHT, start, start + timing.strikeHoldMs)
        }
        s.fired = true
        commit()
        return true
    }

    /** Палец поднят: без движения — удар вперёд (или взаимодействие), иначе жест завершается. */
    fun strikeEnd() {
        val s = strike ?: return
        if (!s.fingerDown) return
        val now = clock()
        s.fingerDown = false
        s.hit?.let { h -> if (h.end == null) h.end = maxOf(now, h.start + timing.strikeHoldMs) }
        if (!s.fired) {
            val start = maxOf(now, readyAt(s) + timing.actionLeadMs)
            val key = if (mode == ControlMode.WORLD && !interactUsesForward) null else GameKey.FORWARD
            s.hit = Hit(key, start, start + timing.strikeHoldMs)
            s.fired = true
        }
        commit()
    }

    /** @return false, если удар отклонён (не боевой режим, идёт другой удар или блок). */
    fun requestAttack(direction: AttackDirection): Boolean {
        if (!mode.allowsMelee || attack != null || strike != null || blockStartedAt != null) return false
        attack = Attack(direction, clock())
        commit()
        return true
    }

    fun setBlock(held: Boolean) {
        val now = clock()
        if (held) {
            if (!mode.allowsMelee || blockStartedAt != null) return
            attack = null // блок важнее начатого удара
            strike = null
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
        attack = null
        strike = null
        blockStartedAt = null
        recoverUntil = Long.MIN_VALUE
        joystick.reset()
        camera.discard()
        tracker.releaseAll()
    }

    /** Пересчитать состояние по текущему времени (фазы ударов, окончание нажатий). */
    fun tick() = commit()

    /** Текущее время арбитра (для жестов, зависящих от длительности касания). */
    fun now(): Long = clock()

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
        strike?.let { s ->
            candidates += readyAt(s)
            s.hit?.let { h ->
                candidates += h.start
                h.end?.let { candidates += it }
            }
        }
        blockStartedAt?.let { s ->
            candidates += s + timing.movementReleaseMs
            candidates += s + timing.movementReleaseMs + timing.actionLeadMs
        }
        candidates += recoverUntil
        return candidates.filter { it > now }.minOrNull()
    }

    fun isAttacking(): Boolean = attack != null || strike?.fired == true

    fun isBlocking(): Boolean = blockStartedAt != null || strike?.hit?.let { it.key == GameKey.BACK && it.end == null } == true

    fun isStriking(): Boolean = strike != null

    fun downScanCodes(): Set<Int> = tracker.downKeys()

    // ---- вычисление --------------------------------------------------------------------

    private fun commit() {
        val now = clock()
        val combat = combatKeys(now)
        val suppressMovement = combat.suppressMovement || now < recoverUntil

        val movement: Set<GameKey> = when {
            mode.isNavigation -> setOfNotNull(joystick.mapNav(joyX, joyY))
            !mode.isInWorld -> {
                joystick.reset()
                emptySet()
            }
            else -> {
                val out = joystick.map(joyX, joyY).keys
                if (suppressMovement) emptySet() else out
            }
        }

        taps.entries.removeAll { it.value <= now }

        val buttonKeys = holds.values.toSet()
            .filterNot { suppressMovement && it in MOVEMENT_KEYS }
            .toSet()

        tracker.apply(
            mapOf(
                SRC_COMBAT to scans(combat.keys),
                SRC_JOYSTICK to scans(movement),
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
        strike?.let { s -> return strikeKeys(s, now) }
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

    private fun strikeKeys(s: Strike, now: Long): CombatOutput {
        val h = s.hit
        if (!s.fingerDown && !hitActive(s, now)) {
            // Жест окончен: короткая пауза до возврата движения, как после удара.
            strike = null
            recoverUntil = maxOf(recoverUntil, (h?.end ?: now) + timing.recoverMs)
            return CombatOutput(emptySet(), suppressMovement = now < recoverUntil)
        }
        if (now < readyAt(s)) return CombatOutput(emptySet(), suppressMovement = true)
        val keys = buildSet {
            add(GameKey.ACTION)
            if (h != null && now >= h.start && (h.end == null || now < h.end!!)) h.key?.let(::add)
        }
        return CombatOutput(keys, suppressMovement = true)
    }

    /** Удар ещё не закончился (или ещё не начался). */
    private fun hitActive(s: Strike, now: Long): Boolean {
        val h = s.hit ?: return false
        val end = h.end ?: return true
        return now < end
    }

    private fun readyAt(s: Strike) = s.startedAt + timing.movementReleaseMs

    private fun directionOf(key: GameKey?): SwipeDir? = when (key) {
        GameKey.FORWARD -> SwipeDir.UP
        GameKey.BACK -> SwipeDir.DOWN
        GameKey.TURN_LEFT -> SwipeDir.LEFT
        GameKey.TURN_RIGHT -> SwipeDir.RIGHT
        else -> null
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
        const val SRC_BUTTONS = "buttons"
        const val SRC_TAPS = "taps"

        val MOVEMENT_KEYS = setOf(
            GameKey.FORWARD, GameKey.BACK, GameKey.STRAFE_LEFT, GameKey.STRAFE_RIGHT,
            GameKey.TURN_LEFT, GameKey.TURN_RIGHT, GameKey.ACTION,
        )
    }
}
