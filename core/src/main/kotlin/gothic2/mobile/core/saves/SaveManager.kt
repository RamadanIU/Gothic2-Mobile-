package gothic2.mobile.core.saves

import gothic2.mobile.core.util.FileOps
import gothic2.mobile.core.util.Hashing
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Родные сохранения Gothic II: резервирование, восстановление, импорт и экспорт.
 *
 * Принципы (раздел 16 плана):
 *  - копия делается только по достоверному признаку завершения записи
 *    (выход из игры или событие адаптера + стабильность файлов), см. [SaveBackupPolicy];
 *  - хранится несколько поколений копий;
 *  - любая замена слота сначала резервирует текущее содержимое и выполняется
 *    через временный каталог с атомарным переименованием.
 *
 * @param savesDir каталог `Saves` внутри импортированной игры
 * @param backupsDir постоянный каталог резервных копий приложения (не кэш)
 */
class SaveManager(
    val savesDir: Path,
    val backupsDir: Path,
    val generations: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(generations >= 1)
    }

    data class FileEntry(val name: String, val size: Long, val sha256: String)

    data class Backup(val slot: String, val id: String, val dir: Path, val reason: String, val files: List<FileEntry>)

    data class Fingerprint(val files: Map<String, Pair<Long, Long>>)

    fun slots(): List<String> {
        if (!savesDir.isDirectory()) return emptyList()
        return savesDir.listDirectoryEntries()
            .filter { it.isDirectory() && isSlotName(it.name) && it.listDirectoryEntries().any { f -> f.isRegularFile() } }
            .map { it.name }
            .sortedWith(compareBy({ slotNumber(it) }, { it }))
    }

    fun slotDir(slot: String): Path {
        require(isSlotName(slot)) { "Недопустимое имя слота: $slot" }
        return savesDir.resolve(slot)
    }

    /** Размер и время изменения файлов — для проверки, что запись закончилась. */
    fun fingerprint(slot: String): Fingerprint {
        val dir = slotDir(slot)
        if (!dir.isDirectory()) return Fingerprint(emptyMap())
        return Fingerprint(
            dir.listDirectoryEntries().filter { it.isRegularFile() }.associate {
                it.name to (Files.size(it) to Files.getLastModifiedTime(it).toMillis())
            },
        )
    }

    fun listBackups(slot: String): List<Backup> {
        val dir = backupsDir.resolve(slot)
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.isDirectory() && !it.name.startsWith(".") && it.resolve(MANIFEST).exists() }
            .mapNotNull { readBackup(slot, it) }
            .sortedByDescending { it.id }
    }

    /**
     * Резервирует слот. Если содержимое совпадает с последней копией, новая не создаётся.
     * @return созданная копия или null (слот пуст или не изменился)
     */
    fun backupSlot(slot: String, reason: String): Backup? {
        val src = slotDir(slot)
        if (!src.isDirectory()) return null
        val files = src.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.name }
        if (files.isEmpty()) return null
        val entries = files.map { FileEntry(it.name, Files.size(it), Hashing.sha256(it)) }
        if (listBackups(slot).firstOrNull()?.files == entries) return null

        val slotBackups = Files.createDirectories(backupsDir.resolve(slot))
        val id = uniqueId(slotBackups)
        val tmp = slotBackups.resolve(".tmp-$id")
        FileOps.deleteTree(tmp)
        Files.createDirectories(tmp)
        try {
            for (f in files) Files.copy(f, tmp.resolve(f.name))
            // Проверяем, что за время копирования файлы не изменились.
            val copied = files.map { FileEntry(it.name, Files.size(tmp.resolve(it.name)), Hashing.sha256(tmp.resolve(it.name))) }
            check(copied == entries) { "Слот $slot изменился во время копирования" }
            tmp.resolve(MANIFEST).writeText(manifestText(reason, entries))
            val dst = slotBackups.resolve(id)
            FileOps.moveAtomic(tmp, dst)
            prune(slot)
            return Backup(slot, id, dst, reason, entries)
        } catch (e: Exception) {
            FileOps.deleteTree(tmp)
            throw e
        }
    }

    /** Резервирует все изменившиеся слоты (например, после выхода из игры). */
    fun backupChanged(reason: String): List<Backup> = slots().mapNotNull { backupSlot(it, reason) }

    /** Восстанавливает копию; текущее содержимое слота сначала резервируется. */
    fun restore(backup: Backup) {
        verifyBackup(backup)
        backupSlot(backup.slot, "before-restore")
        replaceSlot(backup.slot) { staging ->
            for (f in backup.files) Files.copy(backup.dir.resolve(f.name), staging.resolve(f.name))
        }
    }

    fun verifyBackup(backup: Backup) {
        for (f in backup.files) {
            val p = backup.dir.resolve(f.name)
            check(p.isRegularFile() && Hashing.sha256(p) == f.sha256) { "Копия ${backup.id} повреждена: ${f.name}" }
        }
    }

    /** Экспорт слотов в ZIP (для передачи через системный выбор файлов). */
    fun exportZip(out: OutputStream, slots: List<String> = slots()) {
        ZipOutputStream(out).use { zip ->
            for (slot in slots) {
                val dir = slotDir(slot)
                for (f in dir.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.name }) {
                    zip.putNextEntry(ZipEntry("$slot/${f.name}"))
                    Files.copy(f, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Импорт из ZIP. Принимаются только записи вида `<слот>/<файл>`; лимиты на число
     * и размер файлов защищают от повреждённых и вредоносных архивов. Затрагиваемые
     * слоты предварительно резервируются.
     * @return список импортированных слотов
     */
    fun importZip(input: InputStream, limits: ImportLimits = ImportLimits()): List<String> {
        val staging = Files.createDirectories(backupsDir).resolve(".import-${clock()}")
        FileOps.deleteTree(staging)
        Files.createDirectories(staging)
        try {
            var count = 0
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val parts = e.name.split('/')
                    if (!FileOps.isSafeRelative(e.name) || parts.size != 2 || !isSlotName(parts[0])) {
                        throw SaveImportException("Недопустимый путь в архиве: ${e.name}")
                    }
                    if (++count > limits.maxFiles) throw SaveImportException("Слишком много файлов в архиве")
                    val dst = Files.createDirectories(staging.resolve(parts[0])).resolve(parts[1])
                    Files.newOutputStream(dst).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var fileSize = 0L
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            fileSize += n
                            total += n
                            if (fileSize > limits.maxFileBytes || total > limits.maxTotalBytes) {
                                throw SaveImportException("Архив превышает допустимый размер")
                            }
                            os.write(buf, 0, n)
                        }
                    }
                }
            }
            val imported = staging.listDirectoryEntries().filter { it.isDirectory() }.map { it.name }.sorted()
            if (imported.isEmpty()) throw SaveImportException("В архиве нет сохранений Gothic II")
            for (slot in imported) {
                backupSlot(slot, "before-import")
                replaceSlot(slot) { st -> FileOps.copyTree(staging.resolve(slot), st) }
            }
            return imported
        } finally {
            FileOps.deleteTree(staging)
        }
    }

    data class ImportLimits(
        val maxFiles: Int = 2_000,
        val maxFileBytes: Long = 256L * 1024 * 1024,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    )

    // ---- внутреннее --------------------------------------------------------------------

    private fun replaceSlot(slot: String, fill: (Path) -> Unit) {
        Files.createDirectories(savesDir)
        val target = slotDir(slot)
        val staging = savesDir.resolve(".new-$slot")
        val old = savesDir.resolve(".old-$slot")
        FileOps.deleteTree(staging)
        FileOps.deleteTree(old)
        Files.createDirectories(staging)
        try {
            fill(staging)
            if (target.exists()) FileOps.moveAtomic(target, old)
            FileOps.moveAtomic(staging, target)
            FileOps.deleteTree(old)
        } catch (e: Exception) {
            if (!target.exists() && old.exists()) FileOps.moveAtomic(old, target)
            FileOps.deleteTree(staging)
            throw e
        }
    }

    private fun prune(slot: String) {
        listBackups(slot).drop(generations).forEach { FileOps.deleteTree(it.dir) }
    }

    private fun uniqueId(dir: Path): String {
        val base = ID_FORMAT.format(Instant.ofEpochMilli(clock()))
        var id = base
        var n = 1
        while (dir.resolve(id).exists()) id = "$base-${n++}"
        return id
    }

    private fun manifestText(reason: String, entries: List<FileEntry>) = buildString {
        append("reason=").append(reason.replace('\n', ' ')).append('\n')
        for (e in entries) append(e.sha256).append(' ').append(e.size).append(' ').append(e.name).append('\n')
    }

    private fun readBackup(slot: String, dir: Path): Backup? = runCatching {
        val lines = Files.readAllLines(dir.resolve(MANIFEST))
        val reason = lines.first().removePrefix("reason=")
        val files = lines.drop(1).filter { it.isNotBlank() }.map {
            val (sha, size, name) = it.split(' ', limit = 3)
            FileEntry(name, size.toLong(), sha)
        }
        Backup(slot, dir.name, dir, reason, files)
    }.getOrNull()

    companion object {
        private const val MANIFEST = "backup.manifest"
        private val SLOT_RE = Regex("(?i)savegame\\d{1,3}|quicksave")
        private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        fun isSlotName(name: String) = SLOT_RE.matches(name)

        private fun slotNumber(name: String) = name.filter { it.isDigit() }.toIntOrNull() ?: -1
    }
}

class SaveImportException(message: String) : Exception(message)
