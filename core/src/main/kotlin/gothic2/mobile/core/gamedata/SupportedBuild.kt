package gothic2.mobile.core.gamedata

import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.util.FileOps
import gothic2.mobile.core.util.Hashing

/**
 * Вариант запуска внутри одной установки. Gothic II Gold содержит «Ночь Ворона» и может
 * запускать классическую Gothic II как отдельную игру — это разные варианты одной сборки.
 *
 * @property exePath собственный исполняемый файл варианта (в GOG-издании классическая игра —
 *   отдельный `System/Gothic2c.exe`) или null — EXE сборки
 * @property args аргументы EXE (например, `-game:<файл>.ini`); null — ещё не
 *   подтверждены на реальной копии, вариант не предлагается игроку
 * @property savesDir собственный каталог сохранений варианта или null — общий каталог сборки
 * @property renames переименования на время запуска (по порядку), откатываются после выхода —
 *   см. [VariantLayout]
 */
data class GameVariant(
    val id: String,
    val title: String,
    val exePath: String?,
    val args: List<String>?,
    val requiredFiles: List<String>,
    val savesDir: String?,
    val renames: List<FileRename> = emptyList(),
) {
    val isDefined: Boolean get() = args != null
}

/**
 * Закреплённая конфигурация Gothic II (раздел 2 плана). Описание хранится в
 * `supported_builds.ini` (assets приложения).
 *
 * Пока контрольная сумма EXE не зафиксирована по отчёту о сборке
 * ([BuildFingerprint]), сборка считается незакреплённой ([isPinned] = false)
 * и принимается только в режиме разработчика.
 */
data class SupportedBuild(
    val id: String,
    val title: String,
    val exePath: String,
    val exeSha256: String?,
    val language: String,
    val requiredFiles: List<String>,
    /** Файлы, которых в этой сборке быть не должно (отличает Classic от Gold). */
    val forbiddenFiles: List<String>,
    val requiredSha256: Map<String, String>,
    val variants: List<GameVariant>,
    /** Каталог сохранений относительно корня игры. */
    val savesDir: String,
    /** GOTHIC.INI относительно корня игры. */
    val iniPath: String,
) {
    init {
        require(variants.isNotEmpty()) { "Сборка $id без вариантов запуска" }
        require(variants.map { it.id }.toSet().size == variants.size) { "Повторяющиеся варианты в $id" }
    }

    val isPinned: Boolean get() = exeSha256 != null

    /** Варианты, которые можно предложить игроку. */
    fun definedVariants(): List<GameVariant> = variants.filter { it.isDefined }

    fun variant(id: String?): GameVariant? = variants.firstOrNull { it.id == id && it.isDefined }

    /** Вариант по умолчанию: первый подтверждённый. */
    fun defaultVariant(): GameVariant? = definedVariants().firstOrNull()

    fun savesDirOf(variant: GameVariant): String = variant.savesDir ?: savesDir

    fun exeOf(variant: GameVariant): String = variant.exePath ?: exePath

    /** Все каталоги сохранений — при импорте игры они не копируются поверх пользовательских. */
    fun allSavesDirs(): List<String> = (listOf(savesDir) + variants.mapNotNull { it.savesDir }).distinct()

    companion object {
        private const val PREFIX = "build:"
        private const val VARIANT_PREFIX = "variant:"
        private const val PENDING = "pending"

        fun parseAll(text: String): List<SupportedBuild> {
            val ini = IniFile.parse(text)
            fun list(section: String, key: String) =
                ini.get(section, key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

            return ini.sections().filter { it.startsWith(PREFIX) }.map { section ->
                fun req(key: String) = ini.get(section, key) ?: error("[$section]: нет ключа $key")
                val id = section.removePrefix(PREFIX)
                val sha = req("exeSha256").lowercase()
                val variantIds = list(section, "variants").ifEmpty { listOf("default") }
                SupportedBuild(
                    id = id,
                    title = req("title"),
                    exePath = req("exe"),
                    exeSha256 = sha.takeIf { it != PENDING }?.also {
                        require(Hashing.isSha256(it)) { "[$section]: exeSha256 не SHA-256" }
                    },
                    language = req("language"),
                    requiredFiles = list(section, "requires"),
                    forbiddenFiles = list(section, "forbids"),
                    requiredSha256 = list(section, "requiresSha256").associate {
                        val (path, hash) = it.split(':', limit = 2)
                        require(Hashing.isSha256(hash.lowercase())) { "[$section]: неверный хэш для $path" }
                        path to hash.lowercase()
                    },
                    variants = variantIds.map { vid -> parseVariant(ini, "$VARIANT_PREFIX$id/$vid", vid, section) },
                    savesDir = ini.get(section, "saves") ?: "Saves",
                    iniPath = ini.get(section, "ini") ?: "System/GOTHIC.INI",
                )
            }
        }

        private fun parseVariant(ini: IniFile, section: String, id: String, buildSection: String): GameVariant {
            // Без отдельной секции вариант запускается без аргументов (обычный запуск сборки).
            if (section !in ini.sections()) {
                return GameVariant(id, ini.get(buildSection, "title") ?: id, null, emptyList(), emptyList(), null)
            }
            val rawArgs = ini.get(section, "args").orEmpty()
            return GameVariant(
                id = id,
                title = ini.get(section, "title") ?: error("[$section]: нет ключа title"),
                exePath = ini.get(section, "exe")?.takeIf { it.isNotBlank() },
                args = if (rawArgs.trim().equals(PENDING, ignoreCase = true)) null else rawArgs.split(' ').filter { it.isNotEmpty() },
                requiredFiles = ini.get(section, "requires").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                savesDir = ini.get(section, "saves"),
                renames = ini.get(section, "rename").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                    val parts = it.split('>')
                    require(parts.size == 2 && parts.all { p -> FileOps.isSafeRelative(p.trim()) }) {
                        "[$section]: неверное переименование «$it» (ожидается путь>путь)"
                    }
                    FileRename(parts[0].trim(), parts[1].trim())
                },
            )
        }
    }
}
