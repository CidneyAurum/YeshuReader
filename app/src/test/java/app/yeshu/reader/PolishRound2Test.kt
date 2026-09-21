package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮打磨（R51–R80）里新增的纯逻辑。
 *
 * 重点覆盖排序与标签映射这类「改坏了很难从界面反推」的规则。
 */
class PolishRound2Test {

    private data class Item(val title: String, val author: String, val added: Long, val read: Long)

    private val items = listOf(
        Item("B 书", "张三", 1L, 10L),
        Item("A 书", "李四", 3L, 5L),
        Item("C 书", "", 2L, 20L),
        Item("D 书", "李四", 4L, 1L),
    )

    private fun sort(mode: String) = ShelfSort.sort(
        items = items,
        mode = mode,
        title = { it.title },
        author = { it.author },
        addedAt = { it.added },
        lastReadAt = { it.read },
    ).map { it.title }

    // ---------- 排序 ----------

    @Test
    fun `最近阅读按时间倒序`() {
        assertEquals(listOf("C 书", "B 书", "A 书", "D 书"), sort("recent"))
    }

    @Test
    fun `按加入时间倒序`() {
        assertEquals(listOf("D 书", "A 书", "C 书", "B 书"), sort("added"))
    }

    @Test
    fun `按标题升序`() {
        assertEquals(listOf("A 书", "B 书", "C 书", "D 书"), sort("title"))
    }

    @Test
    fun `按作者时未知作者排在最后而不是最前`() {
        val order = sort("author")
        // 李四两本相邻、张三其次、空作者垫底。
        // 注意：中文按 Unicode 码点比较（张 U+5F20 < 李 U+674E），不是拼音序——
        // 这是已知局限，要做拼音排序需要引入排序表，代价大于收益。
        assertEquals(listOf("B 书", "A 书", "D 书", "C 书"), order)
        assertEquals("C 书", order.last())
        // 同作者的书必须相邻，这是这一档位存在的意义。
        val authors = order.map { title -> items.first { it.title == title }.author }
        assertEquals(authors.distinct(), authors.filterIndexed { i, a -> i == 0 || authors[i - 1] != a })
    }

    @Test
    fun `未知排序档位回退到最近阅读`() {
        // 存量脏数据不能让书架变成随机顺序。
        assertEquals(sort("recent"), sort("不存在的档位"))
    }

    // ---------- 档位循环 ----------

    @Test
    fun `排序档位循环一圈回到起点`() {
        var mode = ShelfSort.MODES.first()
        val seen = mutableListOf(mode)
        repeat(ShelfSort.MODES.size - 1) {
            mode = ShelfSort.next(mode)
            seen.add(mode)
        }
        assertEquals(ShelfSort.MODES, seen)
        assertEquals(ShelfSort.MODES.first(), ShelfSort.next(mode))
    }

    @Test
    fun `未知档位的下一个是第一个档位`() {
        assertEquals(ShelfSort.MODES.first(), ShelfSort.next("脏数据"))
    }

    @Test
    fun `每个档位都有中文标签`() {
        ShelfSort.MODES.forEach { mode ->
            assertTrue("档位 $mode 缺少标签", ShelfSort.label(mode).isNotBlank())
        }
    }

    // ---------- 高亮颜色 ----------

    @Test
    fun `高亮颜色有中文名`() {
        assertEquals("琥珀", HighlightColors.label("amber"))
        assertEquals("绿", HighlightColors.label("green"))
    }

    @Test
    fun `未知颜色原样返回而不是显示空白`() {
        // 导出里显示空字符串会让人以为这条划线没有颜色信息。
        assertEquals("自定义色", HighlightColors.label("自定义色"))
    }
}
