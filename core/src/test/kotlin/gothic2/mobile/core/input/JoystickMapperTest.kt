package gothic2.mobile.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoystickMapperTest {
    private val mapper = JoystickMapper()

    @Test
    fun `dead zone produces nothing`() {
        assertTrue(mapper.map(0.1f, 0.1f).keys.isEmpty())
    }

    @Test
    fun `full forward runs without modifier`() {
        val out = mapper.map(0f, 1f)
        assertEquals(setOf(GameKey.FORWARD), out.keys)
        assertTrue(out.running)
    }

    @Test
    fun `small deflection walks with modifier`() {
        mapper.map(0f, 0.3f) // ещё не включилось
        val out = mapper.map(0f, 0.5f)
        assertEquals(setOf(GameKey.FORWARD, GameKey.SPEED_MODIFIER), out.keys)
        assertFalse(out.running)
    }

    @Test
    fun `run and walk switching has hysteresis`() {
        assertTrue(mapper.map(0f, 0.9f).running)
        assertTrue(mapper.map(0f, 0.65f).running) // выше runOff — бег сохраняется
        assertFalse(mapper.map(0f, 0.55f).running)
        assertFalse(mapper.map(0f, 0.70f).running) // ниже runOn — ходьба сохраняется
        assertTrue(mapper.map(0f, 0.80f).running)
    }

    @Test
    fun `axis has hysteresis near diagonal border`() {
        mapper.map(0.5f, 0.85f)
        // x упал ниже порога включения, но выше порога выключения — стрейф остаётся
        assertEquals(setOf(GameKey.FORWARD, GameKey.STRAFE_RIGHT), mapper.map(0.32f, 0.9f).keys)
        assertEquals(setOf(GameKey.FORWARD), mapper.map(0.2f, 0.95f).keys)
    }

    @Test
    fun `opposite directions are never produced together`() {
        val dirs = (0 until 360 step 5).map { a ->
            val r = Math.toRadians(a.toDouble())
            mapper.map(Math.cos(r).toFloat(), Math.sin(r).toFloat()).keys
        }
        for (k in dirs) {
            assertFalse(GameKey.FORWARD in k && GameKey.BACK in k)
            assertFalse(GameKey.STRAFE_LEFT in k && GameKey.STRAFE_RIGHT in k)
        }
    }
}
