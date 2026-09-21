package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阅读时长计入规则：噪声丢弃、重复 resume 不会翻倍、超长会话按上限。 */
class ReadSessionTest {

    private val cap = ReadSession.DEFAULT_CAP_MS

    @Test
    fun `不足一秒的噪声不计入`() {
        assertEquals(0L, ReadSession.countMs(0, 0, cap))
        assertEquals(0L, ReadSession.countMs(999, 999, cap))
    }

    @Test
    fun `正常会话按真实时长计入`() {
        assertEquals(5_000L, ReadSession.countMs(5_000, 5_000, cap))
        assertEquals(60_000L, ReadSession.countMs(60_000, 90_000, cap))
    }

    @Test
    fun `累计不会超过本次挂载的墙钟时间`() {
        // 宿主重复 resume 会把 elapsed 拉长，但墙钟只有 3 秒，只能按 3 秒计
        assertEquals(3_000L, ReadSession.countMs(60_000, 3_000, cap))
    }

    @Test
    fun `墙钟为负时不计入`() {
        assertEquals(0L, ReadSession.countMs(60_000, -1, cap))
    }

    @Test
    fun `超长会话按上限截断而不是整段丢弃`() {
        val eightHours = 8 * 3600_000L
        assertEquals(cap, ReadSession.countMs(eightHours, eightHours, cap))
    }

    @Test
    fun `上限低于实际时长时按上限计入`() {
        assertEquals(1_000L, ReadSession.countMs(1_500, 1_500, 1_000))
    }
}
