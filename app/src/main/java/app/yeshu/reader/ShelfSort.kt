package app.yeshu.reader

/**
 * 书架排序。
 *
 * 抽成纯函数是为了能单测：排序规则一旦被改坏，用户看到的是「书架顺序莫名其妙」，
 * 很难从界面上反推原因。
 */
object ShelfSort {

    /** 排序档位。顺序即「点排序按钮」的循环顺序。 */
    val MODES = listOf("recent", "title", "author", "added")

    fun label(mode: String): String = when (mode) {
        "title" -> "标题"
        "author" -> "作者"
        "added" -> "加入"
        else -> "最近"
    }

    /** 下一个档位。未知值回到第一个，避免存量脏数据把循环卡死。 */
    fun next(current: String): String {
        val index = MODES.indexOf(current)
        return MODES[(index + 1).mod(MODES.size)]
    }

    /**
     * 按作者排序：同一作者聚在一起，作者缺失的排在最后。
     *
     * 「未知作者」不能按空串参与首字母排序——那会把它们全部堆在 A 之前，
     * 看起来像书架坏了。所以先按「是否缺失」分组，再按作者名、书名。
     */
    fun <T> byAuthor(items: List<T>, author: (T) -> String, title: (T) -> String): List<T> =
        items.sortedWith(
            compareBy({ author(it).isBlank() }, { author(it) }, { title(it) })
        )

    fun <T> sort(items: List<T>, mode: String, title: (T) -> String, author: (T) -> String, addedAt: (T) -> Long, lastReadAt: (T) -> Long): List<T> =
        when (mode) {
            "title" -> items.sortedBy(title)
            "author" -> byAuthor(items, author, title)
            "added" -> items.sortedByDescending(addedAt)
            else -> items.sortedByDescending(lastReadAt)
        }
}
