package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度口径的唯一实现：三条路径（进度条、顶栏、落库）必须给出同一个数。
 * 这里只测纯函数，覆盖前缀未加载完、不足一屏、到末尾与往返互逆。
 */
class ProgressModelTest {

    @Test
    fun `未渲染完的长文不会因为滚到已加载末尾而判成读完`() {
        // 全书 1000 块，只渲染了前 100 块，用户滚到第 99 块
        val progress = ProgressModel.progressOf(
            positionIndex = 99,
            totalCount = 1000,
            fullyLoaded = false,
            fitsOnScreen = false,
            interacted = true
        )
        assertEquals(99f / 999f, progress, 1e-6f)
        assertTrue("未加载完时不得超过 0.98", progress < 0.98f)
    }

    @Test
    fun `加载完的长文滚到最后一块等于 100%`() {
        val progress = ProgressModel.progressOf(999, 1000, fullyLoaded = true, fitsOnScreen = false)
        assertEquals(1f, progress, 1e-6f)
    }

    @Test
    fun `不足一屏的短文没有交互时不记为读完`() {
        val untouched = ProgressModel.progressOf(
            positionIndex = 0, totalCount = 5, fullyLoaded = true, fitsOnScreen = true, interacted = false
        )
        assertEquals(0f, untouched, 1e-6f)

        val scrolled = ProgressModel.progressOf(
            positionIndex = 0, totalCount = 5, fullyLoaded = true, fitsOnScreen = true, interacted = true
        )
        assertEquals(1f, scrolled, 1e-6f)
    }

    @Test
    fun `空文档与单块文档不会除以零`() {
        assertEquals(0f, ProgressModel.progressOf(0, 0, fullyLoaded = false), 1e-6f)
        assertEquals(1f, ProgressModel.progressOf(0, 1, fullyLoaded = true), 1e-6f)
        assertEquals(0f, ProgressModel.progressOf(0, 1, fullyLoaded = false), 1e-6f)
    }

    @Test
    fun `下标越界被夹紧到合法区间`() {
        assertEquals(0f, ProgressModel.progressOf(-5, 100, fullyLoaded = true), 1e-6f)
        assertEquals(1f, ProgressModel.progressOf(9999, 100, fullyLoaded = true), 1e-6f)
    }

    @Test
    fun `恢复位置是保存进度的逆运算`() {
        for (index in intArrayOf(0, 1, 37, 500, 999)) {
            val progress = ProgressModel.progressOf(index, 1000, fullyLoaded = true)
            assertEquals(index, ProgressModel.indexForProgress(progress, 1000))
        }
    }

    @Test
    fun `保存-恢复-再保存的循环不漂移`() {
        // 一次往返精确相等只说明映射互逆；连续多轮往返不漂移才说明
        // 「恢复后再保存」不会累积误差（D16：此前版本正是这里会漂）。
        for (start in intArrayOf(0, 5, 250, 700, 999)) {
            var index = start
            repeat(10) {
                val progress = ProgressModel.progressOf(index, 1000, fullyLoaded = true)
                index = ProgressModel.indexForProgress(progress, 1000)
            }
            assertEquals("从 $start 出发 10 轮往返后漂移", start, index)
        }
    }

    @Test
    fun `未渲染完时的往返回到同一块或相邻块`() {
        // 0.98 上限会让末段进度的逆运算偏一块以内——这是设计内的取舍，
        // 断言放宽到 ±1，超出才算漂移。
        for (start in intArrayOf(0, 100, 500, 900)) {
            val progress = ProgressModel.progressOf(start, 1000, fullyLoaded = false)
            val back = ProgressModel.indexForProgress(progress, 1000)
            assertTrue("未渲染完的往返漂移超过 1 块：$start -> $back", kotlin.math.abs(back - start) <= 1)
        }
    }

    @Test
    fun `逆运算对退化输入保持安全`() {
        assertEquals(0, ProgressModel.indexForProgress(0.5f, 0))
        assertEquals(0, ProgressModel.indexForProgress(0.5f, 1))
        assertEquals(0, ProgressModel.indexForProgress(-1f, 10))
        assertEquals(9, ProgressModel.indexForProgress(2f, 10))
    }

    @Test
    fun `半程进度在三处口径下一致`() {
        // 同一位置用「进度条」「落库」两个入口计算，结果必须逐位相同
        val forBar = ProgressModel.progressOf(499, 1000, fullyLoaded = true, fitsOnScreen = false)
        val forStore = ProgressModel.progressOf(499, 1000, fullyLoaded = true, fitsOnScreen = false, interacted = true)
        assertEquals(forBar, forStore, 0f)
        assertTrue(forBar > 0.49f && forBar < 0.51f)
    }

    @Test
    fun `图片集占位正文不会被当成可发送正文`() {
        assertFalse(hasExtractableText("图片集：共 12 页"))
        assertFalse(hasExtractableText("图片集：共 300 页"))
        assertFalse(hasExtractableText(""))
        assertFalse(hasExtractableText("   "))
        assertFalse(hasExtractableText("短"))
        assertTrue(
            hasExtractableText(
                "第一章 序\n".repeat(30) + "这是一段足够长的真实正文，用来确认短文档不会被误判为图片集占位摘要。"
            )
        )
        // 以「图片集」开头但明显是真正文的超长文本仍应放行
        assertTrue(hasExtractableText("图片集：共 12 页\n" + "正文内容。".repeat(40)))
    }

    @Test
    fun `字符数量小于一千时给精确值而不是 0k`() {
        assertEquals("0", formatChars(0))
        assertEquals("1", formatChars(1))
        assertEquals("999", formatChars(999))
        assertEquals("1k", formatChars(1000))
        assertEquals("12k", formatChars(12345))
    }
}
