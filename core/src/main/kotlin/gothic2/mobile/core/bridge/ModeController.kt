package gothic2.mobile.core.bridge

import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter

/**
 * Источник режима управления. Игрок режимы не переключает: панель подстраивается сама.
 *
 * Уровень 2 — игровой адаптер: режим приходит из игры и точен.
 *
 * Уровень 1 — без адаптера: режим угадывается по клавишам, которые отправило само приложение
 * ([onKeyTapped]), и по намерению игрока ([onWorldIntent]):
 *  - запуск игры — главное меню;
 *  - «Сумка» (Tab) — инвентарь, повторно или Esc — обратно в мир;
 *  - Esc в мире — меню игры; Enter в меню — вложенное меню, Esc — уровень назад;
 *  - карта, журнал, персонаж — экран со стрелками, Esc или та же клавиша — обратно;
 *  - «Оружие» (Space) и слоты 1–0 — что в руках: 1 — ближний бой, 2 — лук или арбалет,
 *    3–0 — магия; повторный тот же слот или Space — убрать;
 *  - игрок потянул камеру на экране меню — значит, меню уже закрыто (например, после
 *    «Продолжить» или загрузки сохранения), панель возвращается к игре.
 * Ошибка догадки стоит одного движения: стрелки меню — те же клавиши, что ходьба, а камера
 * сразу возвращает игровую панель.
 */
class ModeController(
    private val arbiter: InputArbiter,
    private val onModeChanged: (ControlMode, adapterActive: Boolean) -> Unit = { _, _ -> },
) : BridgeSession.Listener {

    var adapterActive: Boolean = false
        private set

    /** Что в руках по догадке: null — ничего, иначе COMBAT, RANGED или MAGIC. */
    var armed: ControlMode? = null
        private set

    private var lastArmed = ControlMode.COMBAT
    private var lastSlot: GameKey? = null

    /** Главное меню без начатой игры: Esc из него никуда не ведёт. */
    private var menuRoot = false

    /** Сколько уровней меню открыто поверх игры (или поверх главного меню). */
    private var menuDepth = 0

    /** Экран, открытый своей клавишей (карта, журнал, персонаж): та же клавиша его закрывает. */
    private var screenKey: GameKey? = null

    /** Игра запущена: после логотипов Gothic открывает главное меню. */
    fun onGameStarted() {
        if (adapterActive) return
        armed = null
        lastSlot = null
        enterMenu(root = true, screen = null)
    }

    /** Ручная смена панели (запасной путь: диалог или торговля, начатые игрой); игнорируется при адаптере. */
    fun manual(mode: ControlMode) {
        if (adapterActive) return
        if (mode.isInWorld) {
            armed = mode.takeIf { it != ControlMode.WORLD }
            toWorld()
        } else {
            menuRoot = false
            menuDepth = 0
            screenKey = null
            set(mode)
        }
    }

    /** Игрок сам вернул игровую панель (запасной путь); оружие — как было. */
    fun showWorldPanel() {
        if (adapterActive) return
        toWorld()
    }

    /**
     * Игрок делает то, что бывает только в мире (тянет камеру), а панель — меню или диалог:
     * значит, игра уже в мире. В инвентаре не срабатывает — из него выход надёжен (Tab/Esc).
     */
    fun onWorldIntent() {
        if (adapterActive) return
        val mode = arbiter.mode
        if (mode != ControlMode.MENU && mode != ControlMode.DIALOG && mode != ControlMode.TRADE) return
        // Из главного меню мир открывается новой игрой или загрузкой — оружие не в руках.
        if (mode == ControlMode.MENU && menuRoot) {
            armed = null
            lastSlot = null
        }
        toWorld()
    }

    /** Приложение отправило игре одиночное нажатие [key]. */
    fun onKeyTapped(key: GameKey) {
        if (adapterActive) return
        val mode = arbiter.mode
        when {
            mode.isInWorld -> inWorld(key)
            mode == ControlMode.INVENTORY -> when (key) {
                GameKey.INVENTORY, GameKey.ESCAPE -> toWorld()
                in SCREENS -> enterMenu(root = false, screen = key)
                else -> Unit
            }
            mode == ControlMode.MENU -> inMenu(key)
            else -> if (key == GameKey.QUICKLOAD) loaded()
        }
    }

    override fun onState(state: BridgeProtocol.GameState) {
        adapterActive = true
        arbiter.setTargetLocked(false)
        set(map(state))
        arbiter.setTargetLocked(state.hasTarget)
    }

    override fun onClosed(reason: String) {
        // Соединение без рукопожатия не меняет режим, выбранный догадкой.
        if (!adapterActive) return
        adapterActive = false
        armed = null
        toWorld()
    }

    private fun inWorld(key: GameKey) {
        when (key) {
            GameKey.WEAPON -> {
                armed = if (armed == null) lastArmed else null
                set(worldMode())
            }
            in SLOTS -> {
                val kind = slotKind(key)
                armed = if (armed == kind && lastSlot == key) null else kind
                lastArmed = kind
                lastSlot = key
                set(worldMode())
            }
            GameKey.INVENTORY -> {
                screenKey = null
                set(ControlMode.INVENTORY)
            }
            GameKey.ESCAPE -> enterMenu(root = false, screen = null)
            in SCREENS -> enterMenu(root = false, screen = key)
            GameKey.QUICKLOAD -> loaded()
            else -> Unit
        }
    }

    private fun inMenu(key: GameKey) {
        when (key) {
            GameKey.CONFIRM -> menuDepth++
            GameKey.ESCAPE -> when {
                menuDepth > 0 -> {
                    menuDepth--
                    if (menuDepth == 0 && !menuRoot) toWorld()
                }
                !menuRoot -> toWorld()
            }
            in SCREENS -> if (key == screenKey) toWorld() else screenKey = key
            GameKey.QUICKLOAD -> loaded()
            else -> Unit
        }
    }

    /** Быстрая загрузка: мир из сохранения, оружие убрано. */
    private fun loaded() {
        armed = null
        lastSlot = null
        toWorld()
    }

    private fun enterMenu(root: Boolean, screen: GameKey?) {
        menuRoot = root
        menuDepth = if (root) 0 else 1
        screenKey = screen
        set(ControlMode.MENU)
    }

    private fun toWorld() {
        menuRoot = false
        menuDepth = 0
        screenKey = null
        set(worldMode())
    }

    private fun worldMode(): ControlMode = armed ?: ControlMode.WORLD

    private fun set(mode: ControlMode) {
        arbiter.setMode(mode)
        onModeChanged(mode, adapterActive)
    }

    companion object {
        private val SLOTS = listOf(
            GameKey.SLOT_1, GameKey.SLOT_2, GameKey.SLOT_3, GameKey.SLOT_4, GameKey.SLOT_5,
            GameKey.SLOT_6, GameKey.SLOT_7, GameKey.SLOT_8, GameKey.SLOT_9, GameKey.SLOT_10,
        )
        private val SCREENS = setOf(GameKey.MAP, GameKey.LOG, GameKey.CHARACTER)

        /** Слоты Gothic II: 1 — оружие ближнего боя, 2 — дальнего, 3–0 — руны и свитки. */
        fun slotKind(key: GameKey): ControlMode = when (key) {
            GameKey.SLOT_1 -> ControlMode.COMBAT
            GameKey.SLOT_2 -> ControlMode.RANGED
            else -> ControlMode.MAGIC
        }

        fun map(state: BridgeProtocol.GameState): ControlMode = when (state.mode) {
            BridgeProtocol.GameMode.DIALOG -> ControlMode.DIALOG
            BridgeProtocol.GameMode.INVENTORY -> ControlMode.INVENTORY
            BridgeProtocol.GameMode.TRADE -> ControlMode.TRADE
            BridgeProtocol.GameMode.MENU -> ControlMode.MENU
            BridgeProtocol.GameMode.LOADING, BridgeProtocol.GameMode.CUTSCENE -> ControlMode.LOADING
            BridgeProtocol.GameMode.WORLD -> when (state.weapon) {
                BridgeProtocol.Weapon.NONE -> ControlMode.WORLD
                BridgeProtocol.Weapon.MELEE -> ControlMode.COMBAT
                BridgeProtocol.Weapon.RANGED -> ControlMode.RANGED
                BridgeProtocol.Weapon.MAGIC -> ControlMode.MAGIC
            }
        }
    }
}
