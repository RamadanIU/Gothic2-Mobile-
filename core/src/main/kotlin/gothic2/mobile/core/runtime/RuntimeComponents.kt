package gothic2.mobile.core.runtime

import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.util.Hashing

/**
 * Закреплённые версии компонентов среды исполнения (раздел 5 плана).
 * Автоматическое обновление до «последних» версий запрещено: любая замена компонента
 * проходит регрессионные тесты (docs/TEST_PLAN.md) и меняет этот манифест.
 */
data class RuntimeComponent(
    val id: String,
    val version: String,
    val source: String,
    val license: String,
    /** Архив компонента внутри APK/assets или в загружаемом пакете. */
    val archive: String,
    val sha256: String?,
) {
    val isPinned: Boolean get() = sha256 != null
}

data class RuntimeComponents(val components: List<RuntimeComponent>) {
    operator fun get(id: String): RuntimeComponent? = components.firstOrNull { it.id == id }

    fun unpinned(): List<RuntimeComponent> = components.filterNot { it.isPinned }

    companion object {
        /** Обязательные компоненты для запуска. */
        val REQUIRED = listOf("wine", "box64", "rootfs")

        fun parse(text: String): RuntimeComponents {
            val ini = IniFile.parse(text)
            return RuntimeComponents(
                ini.sections().filter { it.startsWith("component:") }.map { s ->
                    fun req(k: String) = ini.get(s, k) ?: error("[$s]: нет ключа $k")
                    val sha = req("sha256").lowercase()
                    RuntimeComponent(
                        id = s.removePrefix("component:"),
                        version = req("version"),
                        source = req("source"),
                        license = req("license"),
                        archive = req("archive"),
                        sha256 = sha.takeIf { it != "pending" }?.also {
                            require(Hashing.isSha256(it)) { "[$s]: sha256 не SHA-256" }
                        },
                    )
                },
            )
        }
    }
}
