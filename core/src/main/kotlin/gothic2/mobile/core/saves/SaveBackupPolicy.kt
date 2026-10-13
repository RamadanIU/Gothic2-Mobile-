package gothic2.mobile.core.saves

/**
 * Когда делать резервные копии. Никаких таймеров «каждые N минут» во время игры:
 * копия создаётся только по достоверному признаку завершения записи.
 */
class SaveBackupPolicy(
    private val saves: SaveManager,
    private val quietPeriodMs: Long = 1_500,
    private val maxWaitMs: Long = 30_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Игра завершилась — запись гарантированно окончена. */
    fun onGameExited(): List<SaveManager.Backup> = saves.backupChanged("game-exit")

    /**
     * Адаптер сообщил, что запись слота завершена. Дополнительно ждём, пока файлы
     * перестанут меняться в течение [quietPeriodMs].
     * @return копия, null — слот не изменился или не стабилизировался за [maxWaitMs]
     */
    fun onSaveCompleted(slot: String): SaveManager.Backup? {
        if (!awaitStable(slot)) return null
        return saves.backupSlot(slot, "save-completed")
    }

    fun awaitStable(slot: String): Boolean {
        val deadline = clock() + maxWaitMs
        var previous = saves.fingerprint(slot)
        while (clock() < deadline) {
            sleep(quietPeriodMs)
            val current = saves.fingerprint(slot)
            if (current == previous && current.files.isNotEmpty()) return true
            previous = current
        }
        return false
    }
}
