package gothic2.mobile.core.gamedata

import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.util.Hashing

/**
 * Закреплённая конфигурация Gothic II Classic (раздел 2 плана).
 * Описание хранится в `supported_builds.ini` (assets приложения).
 *
 * Пока контрольная сумма EXE не зафиксирована по результатам этапа 1, сборка считается
 * незакреплённой ([isPinned] = false) и принимается только в режиме разработчика.
 */
data class SupportedBuild(
    val id: String,
    val title: String,
    val exePath: String,
    val exeSha256: String?,
    val language: String,
    val requiredFiles: List<String>,
    val requiredSha256: Map<String, String>,
    val launchArgs: List<String>,
    /** Каталог сохранений относительно корня игры. */
    val savesDir: String,
    /** GOTHIC.INI относительно корня игры. */
    val iniPath: String,
) {
    val isPinned: Boolean get() = exeSha256 != null

    companion object {
        private const val PREFIX = "build:"

        fun parseAll(text: String): List<SupportedBuild> {
            val ini = IniFile.parse(text)
            return ini.sections().filter { it.startsWith(PREFIX) }.map { section ->
                fun req(key: String) = ini.get(section, key) ?: error("[$section]: нет ключа $key")
                fun list(key: String) = ini.get(section, key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val sha = req("exeSha256").lowercase()
                SupportedBuild(
                    id = section.removePrefix(PREFIX),
                    title = req("title"),
                    exePath = req("exe"),
                    exeSha256 = sha.takeIf { it != "pending" }?.also {
                        require(Hashing.isSha256(it)) { "[$section]: exeSha256 не SHA-256" }
                    },
                    language = req("language"),
                    requiredFiles = list("requires"),
                    requiredSha256 = list("requiresSha256").associate {
                        val (path, hash) = it.split(':', limit = 2)
                        require(Hashing.isSha256(hash.lowercase())) { "[$section]: неверный хэш для $path" }
                        path to hash.lowercase()
                    },
                    launchArgs = ini.get(section, "args").orEmpty().split(' ').filter { it.isNotEmpty() },
                    savesDir = ini.get(section, "saves") ?: "Saves",
                    iniPath = ini.get(section, "ini") ?: "System/GOTHIC.INI",
                )
            }
        }
    }
}
