package app.yeshu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切句。
 *
 * 菜单会告诉用户「这一段有 N 句」，所以最要紧的两条是：
 * 不能丢内容，也不能把一句拆成两句。
 */
class SentencesTest {

    @Test
    fun `中文句号切分`() {
        assertEquals(
            listOf("今天天气很好。", "我们出去走走吧。"),
            Sentences.split("今天天气很好。我们出去走走吧。"),
        )
    }

    @Test
    fun `问号感叹号分号都断句`() {
        assertEquals(
            listOf("真的吗？", "太好了！", "先做这个；", "再做那个。"),
            Sentences.split("真的吗？太好了！先做这个；再做那个。"),
        )
    }

    @Test
    fun `连着的终止符算一句`() {
        assertEquals(listOf("怎么会这样……", "不可能！！"), Sentences.split("怎么会这样……不可能！！"))
    }

    @Test
    fun `收尾引号跟着上一句`() {
        assertEquals(
            listOf("他说：「我明天再来。」", "然后就走了。"),
            Sentences.split("他说：「我明天再来。」然后就走了。"),
        )
    }

    @Test
    fun `英文句点断句`() {
        assertEquals(
            listOf("The sky is blue.", "The grass is green."),
            Sentences.split("The sky is blue. The grass is green."),
        )
    }

    @Test
    fun `小数点不当作句末`() {
        val text = "圆周率是 3.14 左右，版本号 v1.2 也一样。"
        assertEquals(listOf(text), Sentences.split(text))
    }

    @Test
    fun `没有终止符时整段算一句`() {
        val text = "这一整段话里没有任何句末标点"
        assertEquals(listOf(text), Sentences.split(text))
    }

    @Test
    fun `空串与纯空白返回空列表`() {
        assertEquals(emptyList<String>(), Sentences.split(""))
        assertEquals(emptyList<String>(), Sentences.split("   \n  "))
    }

    @Test
    fun `拼回去等于原文 不丢任何内容`() {
        val samples = listOf(
            "今天天气很好。我们出去走走吧。",
            "他说：「我明天再来。」然后就走了。",
            "The sky is blue. The grass is green.",
            "圆周率是 3.14 左右，版本号 v1.2 也一样。",
            "先做这个；再做那个。最后收尾！",
            "怎么会这样……不可能！！",
            "第一行\n第二行没有标点",
        )
        samples.forEach { sample ->
            val joined = Sentences.split(sample).joinToString("").filterNot { it.isWhitespace() }
            assertEquals("丢内容了：$sample", sample.filterNot { it.isWhitespace() }, joined)
        }
    }

    @Test
    fun `换行是硬断 且不吞掉下一行开头的引号`() {
        assertEquals(
            listOf("第一行", "「第二行」"),
            Sentences.split("第一行\n「第二行」"),
        )
    }

    @Test
    fun `句子过多时退回整段`() {
        val many = "短句。".repeat(Sentences.MAX_SENTENCES + 1)
        assertEquals(listOf(many), Sentences.split(many))
    }

    @Test
    fun `偏移量能定位回原文`() {
        val text = "第一句。第二句。"
        val second = Sentences.split(text)[1]
        assertEquals(4, Sentences.offsetOf(text, second))
        assertEquals(0, Sentences.offsetOf(text, "不存在"))
    }

    @Test
    fun `首尾空白被裁掉`() {
        assertEquals(listOf("有内容。"), Sentences.split("  有内容。  "))
        assertTrue(Sentences.split("  有内容。  ").all { it == it.trim() })
    }
}