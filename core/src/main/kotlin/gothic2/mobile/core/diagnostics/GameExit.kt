package gothic2.mobile.core.diagnostics

/**
 * Чем закончилась игра. От этого зависит, покажет ли лаунчер экран «Игра закрылась» с журналом
 * (раздел 17 плана). Выход игрока — через наше меню или из главного меню самой Gothic — не сбой,
 * сколько бы ни длилась игра: раньше сбоем считался любой выход быстрее трёх минут.
 */
enum class GameExit(val failed: Boolean) {
    /** Игрок вышел через меню «Ещё» → «Выйти». */
    USER_QUIT(false),

    /** Процесс игры завершился сам, без признаков сбоя в выводе Wine: выход из меню Gothic. */
    GAME_QUIT(false),

    /** В выводе Wine — необработанное исключение или обработчик сбоев Gothic. */
    CRASHED(true),

    /** Игра закрылась, так и не показав своё окно. */
    NOT_STARTED(true),
    ;

    companion object {
        /**
         * @param userQuit игрок сам выбрал выход
         * @param windowShown окно игры успело показать кадр
         * @param crashLine первая строка вывода Wine, похожая на сбой ([isCrashLine]), или null
         */
        fun classify(userQuit: Boolean, windowShown: Boolean, crashLine: String?): GameExit = when {
            userQuit -> USER_QUIT
            crashLine != null -> CRASHED
            !windowShown -> NOT_STARTED
            else -> GAME_QUIT
        }

        /** Строка вывода Wine/Box64, по которой видно, что процесс игры упал. */
        fun isCrashLine(line: String): Boolean = CRASH_MARKERS.any { line.contains(it) }

        private val CRASH_MARKERS = listOf(
            // Исключение, которое Wine не смог передать обработчику («Exception frame is not in
            // stack limits», «Unhandled exception code …») — так падала классика при сохранении.
            "err:seh:",
            "Unhandled exception",
            "Unhandled page fault",
            // Обработчик сбоев Gothic (zSpy/Union) строит стек вызовов перед закрытием игры.
            "symbol_demangle",
        )
    }
}
