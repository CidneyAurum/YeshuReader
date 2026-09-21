package app.yeshu.reader

/**
 * 统计页横轴日标签。
 *
 * 之前固定取 `yyyy-MM-dd` 的第 5 位之后，永远显示「MM-dd」：同一月份内也带着月份，
 * 7 个标签挤在窄柱上读不清；而一旦只显示日，跨月时又会出现 31→1 像倒着走的错觉。
 * 规则：整段落在同一个月内只显示日，跨月才带上月份。
 */
object DayLabels {

    /** [days] 形如 `yyyy-MM-dd`，按时间升序；无法解析的项原样返回。 */
    fun labels(days: List<String>): List<String> {
        val parsed = days.map { it.split('-') }
        // 必须严格是 yyyy-MM-dd：`split('-')` 对 "not-a-date" 也会得到 3 段，只数段数会误判
        val wellFormed = parsed.all { parts ->
            parts.size == 3 &&
                parts[0].length == 4 && parts[0].all(Char::isDigit) &&
                parts[1].length == 2 && parts[1].all(Char::isDigit) &&
                parts[2].length == 2 && parts[2].all(Char::isDigit)
        }
        if (!wellFormed) return days
        val sameMonth = parsed.map { it[0] to it[1] }.distinct().size == 1
        return parsed.map { parts ->
            if (sameMonth) parts[2] else "${parts[1]}/${parts[2]}"
        }
    }
}
