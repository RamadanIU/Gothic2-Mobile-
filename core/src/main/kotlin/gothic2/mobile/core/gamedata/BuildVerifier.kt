package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.Hashing

/**
 * Определяет, является ли выбранная папка поддерживаемой сборкой Gothic II
 * (Gold-издание с «Ночью Ворона» и классической игрой или отдельная Classic).
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
            val missing = missingFiles(tree, build)
            if (missing.isNotEmpty()) return Result.MissingFiles(build, missing)
            val corrupted = build.requiredSha256.filter { (path, expected) ->
                val real = tree.resolve(path) ?: return@filter true
                tree.open(real).use { Hashing.sha256(it) } != expected
            }.keys.toList()
            if (corrupted.isNotEmpty()) return Result.Corrupted(build, corrupted)
            return Result.Supported(build, hashes.getValue(exe))
        }

        // Незакреплённые сборки различаются составом файлов: у Gold есть архивы дополнения,
        // у отдельной Classic их быть не должно.
        val unpinned = candidates.filter { (b, _) -> !b.isPinned }
        val fitting = unpinned.firstOrNull { (b, _) -> missingFiles(tree, b).isEmpty() && presentForbidden(tree, b).isEmpty() }
        if (fitting != null) {
            val (build, exe) = fitting
            return Result.Unpinned(build, hashes.getValue(exe), addon)
        }
        val closest = unpinned.firstOrNull { (b, _) -> presentForbidden(tree, b).isEmpty() }
        if (closest != null) return Result.MissingFiles(closest.first, missingFiles(tree, closest.first))
        return Result.UnknownExe(hashes.values.first(), addon)
    }

    private fun missingFiles(tree: GameTree, build: SupportedBuild) = build.requiredFiles.filter { tree.resolve(it) == null }

    private fun presentForbidden(tree: GameTree, build: SupportedBuild) = build.forbiddenFiles.filter { tree.resolve(it) != null }

    /** Архивы дополнения «Ночь Ворона» — признак Gold-издания. */
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
                    (if (result.addonFilesPresent) " Обнаружено дополнение «Ночь Ворона» (Gold-издание)." else "")
            is Result.UnknownExe ->
                "Исполняемый файл не совпадает ни с одной закреплённой сборкой Gothic II." +
                    (if (result.addonFilesPresent) " Похоже на Gold-издание другой версии или поставки." else "") +
                    " Сохраните отчёт о сборке и пришлите его разработчикам — по нему сборку можно добавить."
            is Result.MissingFiles -> "Не хватает файлов игры: ${result.missing.joinToString()}."
            is Result.Corrupted -> "Файлы игры повреждены: ${result.files.joinToString()}."
            Result.NotGothic2 -> "В выбранной папке не найдена установленная Gothic II. Выберите корневую папку игры."
        }
    }
}
