package gothic2.mobile.core.input

/**
 * Длительности фаз удара и блока. Значения — стартовые; их подбирают экспериментально
 * на 30 и 60 FPS (docs/TEST_PLAN.md, раздел «Ввод»). Каждая фаза должна длиться
 * не меньше пары кадров, иначе игра может не заметить нажатие между опросами.
 */
data class CombatTiming(
    /** Пауза после снятия движения перед нажатием «действия». */
    val movementReleaseMs: Long = 40,
    /** Удержание «действия» до нажатия направления. */
    val actionLeadMs: Long = 40,
    /** Удержание «действие + направление». */
    val strikeHoldMs: Long = 120,
    /** Пауза после отпускания до возврата движения. */
    val recoverMs: Long = 40,
    /** Минимальное удержание для одиночного нажатия (меню, слоты, прыжок по касанию). */
    val tapHoldMs: Long = 70,
) {
    init {
        require(listOf(movementReleaseMs, actionLeadMs, strikeHoldMs, recoverMs, tapHoldMs).all { it >= 0 })
    }

    val attackTotalMs: Long get() = movementReleaseMs + actionLeadMs + strikeHoldMs + recoverMs
}
