package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.Hashing

/**
 * Отчёт о сборке игры: состав файлов и их SHA-256. Пользователь получает его на своём
 * телефоне из своей копии и присылает разработчикам — по нему сборка закрепляется в
 * `supported_builds.ini`, не передавая сами игровые файлы (гигабайты и чужие права).
 *
 * В отчёт попадают только относительные пути внутри папки игры, размеры и хэши —
 * никаких путей устройства и личных данных. Сохранения пропускаются.
 */
object BuildFingerprint {
    data class FileInfo(val path: String, val size: Long, val sha256: String?)

    data class Report(
        val files: List<FileInfo>,
        val exePath: String?,
        val exeSha256: String?,
        val addonPresent: Boolean,
        val verdict: String,
        /** Распознанные сторонние компоненты: название → признак (файл). */
        val components: Map<String, String> = emptyMap(),
        /** Содержимое небольших файлов настроек: путь → текст. */
        val configs: Map<String, String> = emptyMap(),
    )

    /** Известные компоненты, которые меняют поведение игры или графики, и их признаки. */
    private val KNOWN_COMPONENTS = linkedMapOf(
        "GD3D11 — графический мод D3D11 (по плану по умолчанию выключен)" to "System/GD3D11",
        "Union — платформа плагинов" to "System/Union.patch",
        "SystemPack — исправления движка" to "System/SystemPack.ini",
        "DSOAL — звук DirectSound через OpenAL" to "System/Autorun/dsoal-aldrv.dll",
        "Отдельный EXE классической Gothic II" to "System/Gothic2c.exe",
        "GothicStarter — запуск модов" to "System/GothicStarter.exe",
        "Сторонний ddraw.dll" to "System/ddraw.dll",
        "Готовый dxvk.conf" to "System/dxvk.conf",
    )

    private val CONFIG_EXTENSIONS = listOf(".ini", ".bat", ".cfg", ".conf", ".d")
    private const val MAX_CONFIG_BYTES = 32 * 1024

    /** Каталоги, файлы которых хэшируются целиком (движок, архивы, настройки, моды). */
    private val HASHED_DIRS = listOf("system/", "data/")

    fun collect(
        tree: GameTree,
        verifier: BuildVerifier,
        savesDirs: List<String> = listOf("Saves"),
        onProgress: (doneBytes: Long, totalBytes: Long, current: String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Report {
        val savesPrefixes = savesDirs.map { it.lowercase().trimEnd('/') + "/" }
        val all = tree.walkFiles()
            .filterNot { (p, _) -> savesPrefixes.any { p.lowercase().startsWith(it) } || p.lowercase().startsWith("saves") }
            .sortedBy { it.first.lowercase() }
            .toList()
        val hashedTotal = all.filter { (p, _) -> hashed(p) }.sumOf { it.second }
        var done = 0L
        val infos = all.map { (path, size) ->
            if (isCancelled()) throw InterruptedException("Отчёт отменён")
            val sha = if (hashed(path)) {
                onProgress(done, hashedTotal, path)
                tree.open(path).use { Hashing.sha256(it) }.also { done += size }
            } else {
                null
            }
            FileInfo(path, size, sha)
        }
        onProgress(hashedTotal, hashedTotal, "")
        val exe = infos.firstOrNull { it.path.equals("System/Gothic2.exe", ignoreCase = true) }
        val verdict = BuildVerifier.describe(verifier.verify(tree))
        val components = KNOWN_COMPONENTS.mapNotNull { (name, marker) -> tree.resolve(marker)?.let { name to it } }.toMap()
        // Файлы настроек показывают, как запускаются варианты и какие клавиши назначены.
        // Только маленькие текстовые файлы из System/ и корня — без сохранений и скриптов.
        val configs = infos.filter { f ->
            val p = f.path.lowercase()
            f.size <= MAX_CONFIG_BYTES && CONFIG_EXTENSIONS.any { p.endsWith(it) } &&
                (p.startsWith("system/") || '/' !in p)
        }.associate { f -> f.path to tree.open(f.path).use { String(it.readBytes(), WINDOWS_1251) } }
        return Report(infos, exe?.path, exe?.sha256, verifier.hasAddonFiles(tree), verdict, components, configs)
    }

    private val WINDOWS_1251 = charset("windows-1251")

    private fun hashed(path: String) = HASHED_DIRS.any { path.lowercase().startsWith(it) }

    /** Текст отчёта: сводка, черновик секции манифеста и полный список файлов. */
    fun format(report: Report, appVersion: String): String = buildString {
        appendLine("# Gothic II Mobile — отчёт о сборке игры")
        appendLine("# Версия приложения: $appVersion")
        appendLine("# Файлов: ${report.files.size}, размер: ${report.files.sumOf { it.size } / (1024 * 1024)} МБ")
        appendLine("# Дополнение «Ночь Ворона»: ${if (report.addonPresent) "есть" else "нет"}")
        appendLine("# Проверка: ${report.verdict}")
        if (report.components.isNotEmpty()) {
            appendLine("# Сторонние компоненты:")
            for ((name, marker) in report.components) appendLine("#   $name ($marker)")
        }
        appendLine()
        appendLine("; Черновик для app/src/main/assets/supported_builds.ini")
        appendLine("[build:CHANGE-ME]")
        appendLine("exe=${report.exePath ?: "System/Gothic2.exe"}")
        appendLine("exeSha256=${report.exeSha256 ?: "PENDING"}")
        val archives = report.files.filter { it.path.lowercase().startsWith("data/") && it.path.lowercase().endsWith(".vdf") && it.sha256 != null }
        appendLine("requires=${archives.joinToString(",") { it.path }}")
        appendLine("requiresSha256=${archives.joinToString(",") { "${it.path}:${it.sha256}" }}")
        appendLine()
        appendLine("; Игровые варианты (моды и ini в System, архивы модов в Data/modvdf):")
        report.files.filter {
            val p = it.path.lowercase()
            (p.startsWith("system/") && p.endsWith(".ini")) || p.endsWith(".mod")
        }.forEach { appendLine(";   ${it.path}") }
        appendLine()
        appendLine("# sha256  размер  путь")
        for (f in report.files) appendLine("${f.sha256 ?: "-".repeat(64)}  ${f.size}  ${f.path}")
        if (report.configs.isNotEmpty()) {
            appendLine()
            appendLine("# Содержимое файлов настроек")
            for ((path, text) in report.configs) {
                appendLine()
                appendLine("## ----- $path -----")
                appendLine(text.replace("\r\n", "\n").trimEnd())
            }
        }
    }
}
