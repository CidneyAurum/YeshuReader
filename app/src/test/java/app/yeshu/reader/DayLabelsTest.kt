package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 统计页横轴标签：同月只给日，跨月带月份，脏数据原样返回。 */
class DayLabelsTest {

    @Test
    fun `同一月份只显示日`() {
        val days = listOf("2026-09-15", "2026-09-16", "2026-09-21")
        assertEquals(listOf("15", "16", "21"), DayLabels.labels(days))
    }

    @Test
    fun `跨月时带上月份`() {
        val days = listOf("2026-08-29", "2026-08-30", "2026-08-31", "2026-09-01", "2026-09-02")
        assertEquals(
            listOf("08/29", "08/30", "08/31", "09/01", "09/02"),
            DayLabels.labels(days)
        )
    }

    @Test
    fun `跨年也带月份且不会只剩日`() {
        val days = listOf("2025-12-31", "2026-01-01")
        assertEquals(listOf("12/31", "01/01"), DayLabels.labels(days))
    }

    @Test
    fun `空列表安全`() {
        assertEquals(emptyList<String>(), DayLabels.labels(emptyList()))
    }

    @Test
    fun `无法解析的项原样返回`() {
        val days = listOf("2026-09-15", "not-a-date")
        assertEquals(days, DayLabels.labels(days))
    }
}
