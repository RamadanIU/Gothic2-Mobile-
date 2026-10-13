package gothic2.mobile.core.input

/**
 * Учёт удержания клавиш по источникам (джойстик, кнопка, бой, геймпад...).
 *
 * Клавиша нажата, пока её держит хотя бы один источник. Отпускание одного источника
 * не отменяет удержание другого. Все изменения применяются пакетом: сначала все
 * отпускания, затем все нажатия, в порядке возрастания скан-кода — порядок детерминирован.
 */
class KeyStateTracker(private val sink: InputSink) {
    private val holders = HashMap<Int, MutableSet<String>>()
    private val bySource = HashMap<String, Set<Int>>()

    fun isDown(scanCode: Int): Boolean = holders[scanCode]?.isNotEmpty() == true

    fun downKeys(): Set<Int> = holders.filterValues { it.isNotEmpty() }.keys.toSortedSet()

    fun heldBy(source: String): Set<Int> = bySource[source].orEmpty()

    /** Задаёт полный набор клавиш для каждого источника из [updates]. */
    fun apply(updates: Map<String, Set<Int>>) {
        val toRelease = sortedSetOf<Int>()
        val toPress = sortedSetOf<Int>()
        val wasDown = HashMap<Int, Boolean>()
        for ((source, keys) in updates) {
            val old = bySource[source].orEmpty()
            for (k in old - keys) {
                wasDown.getOrPut(k) { isDown(k) }
                holders[k]?.remove(source)
            }
            for (k in keys - old) {
                wasDown.getOrPut(k) { isDown(k) }
                holders.getOrPut(k) { HashSet() }.add(source)
            }
            if (keys.isEmpty()) bySource.remove(source) else bySource[source] = keys.toSet()
        }
        for ((k, before) in wasDown) {
            val after = isDown(k)
            if (before && !after) toRelease += k
            if (!before && after) toPress += k
        }
        toRelease.forEach(sink::keyUp)
        toPress.forEach(sink::keyDown)
    }

    fun set(source: String, keys: Set<Int>) = apply(mapOf(source to keys))

    fun release(source: String) = set(source, emptySet())

    /** Снимает всё удержание (потеря фокуса, ACTION_CANCEL, сворачивание). */
    fun releaseAll() {
        val down = downKeys()
        holders.clear()
        bySource.clear()
        down.forEach(sink::keyUp)
    }
}
