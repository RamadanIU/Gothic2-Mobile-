package gothic2.mobile.core.bridge

import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.RecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeTest {
    private val exe = "c".repeat(64)
    private var now = 0L
    private val session = BridgeSession(exe, token = "0123456789abcdef0123456789abcdef", timeoutMs = 1_000, clock = { now })
    private val arbiter = InputArbiter(RecordingSink(), clock = { now })
    private val modes = ModeController(arbiter).also { session.addListener(it) }

    private fun hello(sha: String = exe, token: String = session.token) = session.onLine("HELLO 1 $sha $token")

    @Test
    fun `state drives control mode after handshake`() {
        hello()
        assertEquals(BridgeSession.Status.ACTIVE, session.status)
        session.onLine("STATE mode=WORLD weapon=MELEE target=1 cansave=1 slots=3:RUNE,4:SCROLL")
        assertEquals(ControlMode.COMBAT, arbiter.mode)
        assertTrue(arbiter.targetLocked)
        assertTrue(modes.adapterActive)
        assertEquals(mapOf(3 to "RUNE", 4 to "SCROLL"), session.lastState!!.quickSlots)
    }

    @Test
    fun `manual switching is ignored while adapter is active`() {
        hello()
        session.onLine("STATE mode=DIALOG weapon=NONE target=0 cansave=0 slots=")
        modes.manual(ControlMode.COMBAT)
        assertEquals(ControlMode.DIALOG, arbiter.mode)
    }

    @Test
    fun `wrong exe or token closes session and falls back to manual`() {
        hello(sha = "d".repeat(64))
        assertEquals(BridgeSession.Status.CLOSED, session.status)
        assertFalse(modes.adapterActive)
        modes.manual(ControlMode.COMBAT)
        assertEquals(ControlMode.COMBAT, arbiter.mode)
    }

    @Test
    fun `state before hello is a violation`() {
        session.onLine("STATE mode=WORLD weapon=NONE target=0 cansave=0")
        assertEquals(BridgeSession.Status.CLOSED, session.status)
    }

    @Test
    fun `silence beyond timeout drops adapter`() {
        hello()
        session.onLine("STATE mode=WORLD weapon=RANGED target=0 cansave=1")
        assertEquals(ControlMode.RANGED, arbiter.mode)
        now += 1_500
        session.checkAlive()
        assertEquals(BridgeSession.Status.CLOSED, session.status)
        assertEquals(ControlMode.WORLD, arbiter.mode)
    }

    @Test
    fun `malformed messages are rejected`() {
        val bad = listOf(
            "HELLO 1 xyz 00",
            "STATE mode=FLYING weapon=NONE target=0 cansave=0",
            "STATE mode=WORLD weapon=NONE target=2 cansave=0",
            "STATE mode=WORLD weapon=NONE target=0 cansave=0 slots=11:RUNE",
            "SAVE_DONE ../../etc",
            "EXEC rm -rf",
            "STATE " + "x".repeat(600),
        )
        for (line in bad) assertFailsWith<BridgeProtocol.ProtocolException>(line) { BridgeProtocol.parse(line) }
    }

    @Test
    fun `save done is forwarded`() {
        val slots = mutableListOf<String>()
        session.addListener(object : BridgeSession.Listener {
            override fun onSaveDone(slot: String) { slots += slot }
        })
        hello()
        session.onLine("SAVE_DONE savegame5")
        assertEquals(listOf("savegame5"), slots)
    }
}
