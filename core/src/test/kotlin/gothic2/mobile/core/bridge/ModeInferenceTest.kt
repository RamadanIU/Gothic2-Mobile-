package gothic2.mobile.core.bridge

import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Уровень 1: режим угадывается по клавишам, которые отправило приложение. */
class ModeInferenceTest {
    private val arbiter = InputArbiter(RecordingSink(), clock = { 0L })
    private val modes = ModeController(arbiter).also { m -> arbiter.onKeyTapped = m::onKeyTapped }

    private fun tap(vararg keys: GameKey) = keys.forEach(arbiter::tap)

    @Test
    fun `game starts in main menu and camera drag returns to world`() {
        modes.onGameStarted()
        assertEquals(ControlMode.MENU, arbiter.mode)
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.MENU, arbiter.mode, "из главного меню Esc никуда не ведёт")
        tap(GameKey.CONFIRM) // «Загрузить» → список, ещё Enter → загрузка
        tap(GameKey.CONFIRM)
        assertEquals(ControlMode.MENU, arbiter.mode)
        modes.onWorldIntent()
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `escape opens game menu and closes it level by level`() {
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.MENU, arbiter.mode)
        tap(GameKey.CONFIRM) // настройки
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.MENU, arbiter.mode)
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `inventory opens and closes by the same button or escape`() {
        tap(GameKey.INVENTORY)
        assertEquals(ControlMode.INVENTORY, arbiter.mode)
        tap(GameKey.ACTION) // использовать предмет — инвентарь остаётся
        assertEquals(ControlMode.INVENTORY, arbiter.mode)
        tap(GameKey.INVENTORY)
        assertEquals(ControlMode.WORLD, arbiter.mode)
        tap(GameKey.INVENTORY, GameKey.ESCAPE)
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `camera drag does not close inventory`() {
        tap(GameKey.INVENTORY)
        modes.onWorldIntent()
        assertEquals(ControlMode.INVENTORY, arbiter.mode)
    }

    @Test
    fun `map and log close by escape or their own key`() {
        tap(GameKey.MAP)
        assertEquals(ControlMode.MENU, arbiter.mode)
        tap(GameKey.MAP)
        assertEquals(ControlMode.WORLD, arbiter.mode)
        tap(GameKey.LOG, GameKey.CONFIRM, GameKey.ESCAPE)
        assertEquals(ControlMode.MENU, arbiter.mode, "из записи — назад к списку журнала")
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `weapon and slots follow what is in hand`() {
        tap(GameKey.WEAPON)
        assertEquals(ControlMode.COMBAT, arbiter.mode)
        tap(GameKey.WEAPON)
        assertEquals(ControlMode.WORLD, arbiter.mode)
        tap(GameKey.SLOT_2)
        assertEquals(ControlMode.RANGED, arbiter.mode)
        tap(GameKey.SLOT_3)
        assertEquals(ControlMode.MAGIC, arbiter.mode)
        tap(GameKey.SLOT_4)
        assertEquals(ControlMode.MAGIC, arbiter.mode, "другая руна — магия остаётся")
        tap(GameKey.SLOT_4)
        assertEquals(ControlMode.WORLD, arbiter.mode, "тот же слот ещё раз — убрать")
        tap(GameKey.WEAPON)
        assertEquals(ControlMode.MAGIC, arbiter.mode, "Space достаёт то, что было в руках последним")
    }

    @Test
    fun `weapon survives game menu and resets after quickload`() {
        tap(GameKey.SLOT_1, GameKey.ESCAPE)
        assertEquals(ControlMode.MENU, arbiter.mode)
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.COMBAT, arbiter.mode)
        tap(GameKey.QUICKLOAD)
        assertEquals(ControlMode.WORLD, arbiter.mode)
        assertNull(modes.armed)
    }

    @Test
    fun `manual fallback for dialogs started by the game`() {
        modes.manual(ControlMode.DIALOG)
        assertEquals(ControlMode.DIALOG, arbiter.mode)
        tap(GameKey.ESCAPE)
        assertEquals(ControlMode.DIALOG, arbiter.mode, "Esc в диалоге не угадывается")
        modes.onWorldIntent()
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `adapter state wins over guesses`() {
        val token = "0123456789abcdef0123456789abcdef"
        val session = BridgeSession("c".repeat(64), token = token, timeoutMs = 1_000, clock = { 0L })
        session.addListener(modes)
        session.onLine("HELLO 1 ${"c".repeat(64)} $token")
        session.onLine("STATE mode=DIALOG weapon=NONE target=0 cansave=0 slots=")
        tap(GameKey.ESCAPE, GameKey.INVENTORY)
        modes.onWorldIntent()
        assertEquals(ControlMode.DIALOG, arbiter.mode)
    }
}
