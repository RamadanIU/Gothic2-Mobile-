package gothic2.mobile.core.bridge

import gothic2.mobile.core.util.Hashing

/**
 * Протокол моста состояния между игровым адаптером (внутри процесса Gothic II) и приложением.
 *
 * Транспорт — локальный сокет в приватном каталоге приложения, НЕ сетевой порт.
 * Формат — строки UTF-8 не длиннее [MAX_LINE] байт. Первая строка от адаптера —
 * `HELLO <версия> <sha256 EXE> <токен>`; токен приложение генерирует на каждый запуск
 * и передаёт адаптеру через переменную окружения [TOKEN_ENV].
 *
 * Адаптер → приложение:
 *   STATE mode=<GameMode> weapon=<Weapon> target=<0|1> cansave=<0|1> slots=<n:kind,...>
 *   SAVE_DONE <slotName>
 *   BYE
 * Приложение → адаптер (только белый список [Command]):
 *   PING | PAUSE | RESUME
 */
object BridgeProtocol {
    const val VERSION = 1
    const val MAX_LINE = 512
    const val TOKEN_ENV = "G2M_BRIDGE_TOKEN"
    const val SOCKET_ENV = "G2M_BRIDGE_SOCKET"

    enum class GameMode { WORLD, DIALOG, INVENTORY, TRADE, MENU, LOADING, CUTSCENE }

    enum class Weapon { NONE, MELEE, RANGED, MAGIC }

    enum class Command { PING, PAUSE, RESUME }

    data class GameState(
        val mode: GameMode,
        val weapon: Weapon,
        val hasTarget: Boolean,
        val canSave: Boolean,
        /** Номер быстрого слота → тип содержимого (как сообщает игра). */
        val quickSlots: Map<Int, String>,
    )

    sealed interface Message {
        data class Hello(val version: Int, val exeSha256: String, val token: String) : Message
        data class State(val state: GameState) : Message
        data class SaveDone(val slot: String) : Message
        data object Bye : Message
    }

    class ProtocolException(message: String) : Exception(message)

    private val SLOT_KIND = Regex("[A-Za-z_]{1,24}")
    private val SLOT_NAME = Regex("(?i)savegame\\d{1,3}|quicksave")

    fun parse(line: String): Message {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE) throw ProtocolException("Слишком длинная строка")
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw ProtocolException("Пустая строка")
        return when (parts[0]) {
            "HELLO" -> {
                if (parts.size != 4) throw ProtocolException("HELLO: неверное число полей")
                val version = parts[1].toIntOrNull() ?: throw ProtocolException("HELLO: версия")
                if (!Hashing.isSha256(parts[2])) throw ProtocolException("HELLO: sha256")
                if (!parts[3].all { it in '0'..'9' || it in 'a'..'f' } || parts[3].length != 32) {
                    throw ProtocolException("HELLO: токен")
                }
                Message.Hello(version, parts[2], parts[3])
            }
            "STATE" -> Message.State(parseState(parts.drop(1)))
            "SAVE_DONE" -> {
                if (parts.size != 2 || !SLOT_NAME.matches(parts[1])) throw ProtocolException("SAVE_DONE: слот")
                Message.SaveDone(parts[1])
            }
            "BYE" -> Message.Bye
            else -> throw ProtocolException("Неизвестное сообщение")
        }
    }

    fun encode(command: Command): String = command.name

    private fun parseState(fields: List<String>): GameState {
        val kv = fields.associate {
            val i = it.indexOf('=')
            if (i <= 0) throw ProtocolException("STATE: поле $it")
            it.substring(0, i) to it.substring(i + 1)
        }
        fun field(k: String) = kv[k] ?: throw ProtocolException("STATE: нет $k")
        fun flag(k: String) = when (field(k)) {
            "0" -> false
            "1" -> true
            else -> throw ProtocolException("STATE: $k")
        }
        val mode = GameMode.entries.firstOrNull { it.name == field("mode") } ?: throw ProtocolException("STATE: mode")
        val weapon = Weapon.entries.firstOrNull { it.name == field("weapon") } ?: throw ProtocolException("STATE: weapon")
        val slots = kv["slots"].orEmpty().split(',').filter { it.isNotEmpty() }.associate {
            val (n, kind) = it.split(':', limit = 2).takeIf { p -> p.size == 2 } ?: throw ProtocolException("STATE: slots")
            val num = n.toIntOrNull()?.takeIf { x -> x in 1..10 } ?: throw ProtocolException("STATE: номер слота")
            if (!SLOT_KIND.matches(kind)) throw ProtocolException("STATE: тип слота")
            num to kind
        }
        return GameState(mode, weapon, flag("target"), flag("cansave"), slots)
    }
}
