package gothic2.mobile.core.config

/**
 * Минимальный INI-редактор, сохраняющий исходное форматирование, комментарии и порядок
 * строк. Имена секций и ключей сравниваются без учёта регистра (как в Gothic).
 * Используется для GOTHIC.INI, dxvk.conf-подобных файлов и манифестов приложения.
 */
class IniFile private constructor(private val lines: MutableList<String>) {

    fun sections(): List<String> = lines.mapNotNull { sectionName(it) }.distinct()

    fun get(section: String, key: String): String? {
        val range = sectionRange(section) ?: return null
        for (i in range) parseKey(lines[i])?.let { (k, v) -> if (k.equals(key, ignoreCase = true)) return v }
        return null
    }

    fun keys(section: String): List<String> {
        val range = sectionRange(section) ?: return emptyList()
        return range.mapNotNull { parseKey(lines[it])?.first }
    }

    /** Меняет значение на месте или добавляет ключ в конец секции (секция создаётся при необходимости). */
    fun set(section: String, key: String, value: String) {
        require('\n' !in value && '\r' !in value) { "Перевод строки в значении" }
        val range = sectionRange(section)
        if (range == null) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += "$key=$value"
            return
        }
        for (i in range) {
            val kv = parseKey(lines[i]) ?: continue
            if (kv.first.equals(key, ignoreCase = true)) {
                val eq = lines[i].indexOf('=')
                lines[i] = lines[i].substring(0, eq + 1) + value
                return
            }
        }
        // Вставляем после последней непустой строки секции.
        var insertAt = range.first
        for (i in range) if (lines[i].isNotBlank()) insertAt = i + 1
        lines.add(insertAt, "$key=$value")
    }

    override fun toString(): String = lines.joinToString("\r\n", postfix = "\r\n")

    private fun sectionRange(section: String): IntRange? {
        val start = lines.indexOfFirst { sectionName(it)?.equals(section, ignoreCase = true) == true }
        if (start < 0) return null
        var end = lines.size
        for (i in start + 1 until lines.size) if (sectionName(lines[i]) != null) { end = i; break }
        return (start + 1) until end
    }

    companion object {
        fun parse(text: String): IniFile =
            IniFile(text.removePrefix("﻿").split("\r\n", "\n").toMutableList().also {
                if (it.isNotEmpty() && it.last().isEmpty()) it.removeAt(it.lastIndex)
            })

        fun empty() = IniFile(mutableListOf())

        private fun sectionName(line: String): String? {
            val t = line.trim()
            return if (t.startsWith("[") && t.endsWith("]") && t.length > 2) t.substring(1, t.length - 1).trim() else null
        }

        private fun parseKey(line: String): Pair<String, String>? {
            val t = line.trimStart()
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("#") || t.startsWith("//")) return null
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            return line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
    }
}
