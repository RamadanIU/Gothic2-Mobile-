package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.Hashing

/**
 * Определяет, является ли выбранная папка поддерживаемой Gothic II Classic.
 *
 * Решение принимается по контрольной сумме EXE, а не по имени файла: обычный
 * `Gothic2.exe` в разных поставках может запускать разные версии.
 */
class BuildVerifier(private val builds: List<SupportedBuild>) {

    sealed interface Result {
        /** Сборка закреплена и все проверки пройдены. */
        data class Supported(val build: SupportedBuild, val exeSha256: String) : Result

        /** Похоже на нужную сборку, но эталонный хэш ещё не зафиксирован (только режим разработчика). */
        data class Unpinned(val build: SupportedBuild, val exeSha256: String, val addonFilesPresent: Boolean) : Result

        data class UnknownExe(val exeSha256: String, val addonFilesPresent: Boolean) : Result

        data class MissingFiles(val build: SupportedBuild, val missing: List<String>) : Result

        data class Corrupted(val build: SupportedBuild, val files: List<String>) : Result

        data object NotGothic2 : Result
    }

    fun verify(tree: GameTree): Result {
        val candidates = builds.mapNotNull { b -> tree.resolve(b.exePath)?.let { b to it } }
        if (candidates.isEmpty()) return Result.NotGothic2
        val hashes = candidates.map { it.second }.distinct().associateWith { p -> tree.open(p).use { Hashing.sha256(it) } }
        val addon = hasAddonFiles(tree)

        val pinnedMatch = candidates.firstOrNull { (b, p) -> b.isPinned && b.exeSha256 == hashes[p] }
        if (pinnedMatch != null) {
            val (build, exe) = pinnedMatch
            val missing = build.requiredFiles.filter { tree.resolve(it) == null }
            if (missing.isNotEmpty()) return Result.MissingFiles(build, missing)
            val corrupted = build.requiredSha256.filter { (path, expected) ->
                val real = tree.resolve(path) ?: return@filter true
                tree.open(real).use { Hashing.sha256(it) } != expected
            }.keys.toList()
            if (corrupted.isNotEmpty()) return Result.Corrupted(build, corrupted)
            return Result.Supported(build, hashes.getValue(exe))
        }

        val unpinned = candidates.firstOrNull { (b, _) -> !b.isPinned }
        if (unpinned != null) {
            val (build, exe) = unpinned
            val missing = build.requiredFiles.filter { tree.resolve(it) == null }
            if (missing.isNotEmpty()) return Result.MissingFiles(build, missing)
            return Result.Unpinned(build, hashes.getValue(exe), addon)
        }
        return Result.UnknownExe(hashes.values.first(), addon)
    }

    /**
     * Архивы дополнения «Ночь Ворона». Их наличие само по себе не означает, что Classic
     * не запустится (Gold-издания содержат обе версии), но подсказывает пользователю,
     * почему EXE не опознан.
     */
    fun hasAddonFiles(tree: GameTree): Boolean {
        val data = tree.resolve("Data") ?: return false
        return tree.list(data).any { !it.isDirectory && it.name.contains("addon", ignoreCase = true) }
    }

    companion object {
        /** Человекочитаемое сообщение для экрана импорта. */
        fun describe(result: Result): String = when (result) {
            is Result.Supported -> "Найдена поддерживаемая сборка: ${result.build.title}."
            is Result.Unpinned ->
                "Найдена ${result.build.title}, но эталонная контрольная сумма ещё не закреплена. " +
                    "Запуск возможен только в режиме разработчика." +
                    (if (result.addonFilesPresent) " Обнаружены файлы «Ночи Ворона»: убедитесь, что это Classic." else "")
            is Result.UnknownExe ->
                "Исполняемый файл не совпадает ни с одной поддерживаемой сборкой Gothic II Classic." +
                    (if (result.addonFilesPresent) " Похоже, это Gothic II Gold / «Ночь Ворона» — она не поддерживается." else "")
            is Result.MissingFiles -> "Не хватает файлов игры: ${result.missing.joinToString()}."
            is Result.Corrupted -> "Файлы игры повреждены: ${result.files.joinToString()}."
            Result.NotGothic2 -> "В выбранной папке не найдена установленная Gothic II. Выберите корневую папку игры."
        }
    }
}
