package app.yeshu.reader

import app.yeshu.reader.ai.DocumentAiService
import app.yeshu.reader.parse.Block
import app.yeshu.reader.parse.ParsedDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAiServiceTest {

    @Test
    fun buildAnchoredContext_constructsChapterAndParagraphAnchorsForMarkdown() {
        val parsed = parsedDoc(
            format = "md",
            Block(Block.TEXT, "# 第一章 起步"),
            Block(Block.TEXT, "页枢优先在本地管理书籍。"),
            Block(Block.TEXT, "## 第二章 理解资料"),
            Block(Block.TEXT, "AI 只在用户主动触发时运行。")
        )

        val context = DocumentAiService.buildAnchoredContext(
            parsed = parsed,
            formatHint = "markdown",
            maxChars = 2_048
        )

        assertFalse(context.truncated)
        assertEquals(4, context.includedSegments)
        assertEquals(4, context.totalSegments)
        assertEquals(
            listOf(
                DocumentAnchor(AnchorType.CHAPTER, 1, "第一章 起步", "第一章 起步"),
                DocumentAnchor(AnchorType.CHAPTER, 2, "第二章 理解资料", "第二章 理解资料"),
                DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1", "页枢优先在本地管理书籍。"),
                DocumentAnchor(AnchorType.PARAGRAPH, 2, "段落 2", "AI 只在用户主动触发时运行。")
            ),
            context.anchors
        )
        assertTrue(context.text.contains("[CHAPTER:1]\n第一章 起步"))
        assertTrue(context.text.contains("[CHAPTER:1] [PARAGRAPH:1]"))
        assertTrue(context.text.contains("[CHAPTER:2] [PARAGRAPH:2]"))
    }

    @Test
    fun buildAnchoredContext_preservesPptSlideNumbersAndParentsParagraphs() {
        val parsed = parsedDoc(
            format = "pptx",
            Block(Block.HEADING, "— 第 2 页 —"),
            Block(Block.TEXT, "第二页的关键结论。"),
            Block(Block.HEADING, "第 5 页"),
            Block(Block.TEXT, "第五页的复习题。")
        )

        val context = DocumentAiService.buildAnchoredContext(parsed, maxChars = 2_048)

        assertEquals(
            listOf(
                DocumentAnchor(AnchorType.SLIDE, 2, "第 2 张幻灯片", "第 2 张幻灯片"),
                DocumentAnchor(AnchorType.SLIDE, 5, "第 5 张幻灯片", "第 5 张幻灯片"),
                DocumentAnchor(AnchorType.PARAGRAPH, 1, "幻灯片 2 · 段落 1", "第二页的关键结论。"),
                DocumentAnchor(AnchorType.PARAGRAPH, 2, "幻灯片 5 · 段落 2", "第五页的复习题。")
            ),
            context.anchors
        )
        assertTrue(context.text.contains("[SLIDE:2] [PARAGRAPH:1]"))
        assertTrue(context.text.contains("[SLIDE:5] [PARAGRAPH:2]"))
    }

    @Test
    fun validateCitations_acceptsOnlyExactAnchorsIncludedInContext() {
        val context = anchoredContext(
            DocumentAnchor(AnchorType.CHAPTER, 1, "第一章"),
            DocumentAnchor(AnchorType.PARAGRAPH, 3, "段落 3")
        )

        val validation = DocumentAiService.validateCitations(
            "结论 [CHAPTER:1]，证据 [PARAGRAPH:3]；伪造 [PARAGRAPH:9] [PAGE:1] [CHAPTER:x]。",
            context
        )

        assertFalse(validation.isValid)
        assertEquals(
            listOf(
                DocumentAiService.CitationRef(AnchorType.CHAPTER, 1),
                DocumentAiService.CitationRef(AnchorType.PARAGRAPH, 3)
            ),
            validation.valid
        )
        assertEquals(
            listOf("[PARAGRAPH:9]", "[PAGE:1]", "[CHAPTER:x]"),
            validation.invalid.map { it.raw }
        )
    }

    @Test
    fun validateModelOutput_requiresAllSectionsAndCitedConclusionBullets() {
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val valid = """
            ## 分层摘要
            摘要 [PARAGRAPH:1]
            ## 目录大纲
            - 大纲 [PARAGRAPH:1]
            ## 关键概念
            概念 [PARAGRAPH:1]
            ## 核心结论
            - 本地优先 [PARAGRAPH:1]
            ## 闪卡
            正面 / 背面 [PARAGRAPH:1]
            ## 测验
            问题与答案 [PARAGRAPH:1]
        """.trimIndent()

        val accepted = DocumentAiService.validateModelOutput(valid, context)
        val rejected = DocumentAiService.validateModelOutput(
            valid.replace("- 本地优先 [PARAGRAPH:1]", "- 没有来源的结论"),
            context
        )

        assertTrue(accepted.isAcceptable)
        assertFalse(rejected.isAcceptable)
        assertEquals(listOf("- 没有来源的结论"), rejected.uncitedConclusionLines)
    }

    @Test
    fun `摘要不受理解包六段格式约束`() {
        // 真机实测：摘要输出完全正常（一句话概括 + 要点），却被判「缺少分层摘要/目录大纲/
        // 关键概念/核心结论/闪卡/测验」六个段落——因为校验不区分任务种类。
        // 摘要天生不会产出闪卡与测验，这类误判会让用户以为功能坏了。
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val summary = """
            这份资料讲的是排期与重构 [PARAGRAPH:1]。
            - 先定排期 [PARAGRAPH:1]
            - 再谈重构 [PARAGRAPH:1]
        """.trimIndent()

        val validated = DocumentAiService.validateModelOutput(summary, context, DocumentAiService.KIND_SUMMARY)
        assertTrue("摘要不该被六段格式判失败", validated.isAcceptable)
        assertTrue("段落缺失列表应为空", validated.missingSections.isEmpty())
    }

    @Test
    fun `非理解包仍然核对引用是否真实存在`() {
        // 放宽段落检查不等于放弃引用校验：编造锚点必须被识别。
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val fabricated = "结论见 [PARAGRAPH:99]"
        val validated = DocumentAiService.validateModelOutput(fabricated, context, DocumentAiService.KIND_SUMMARY)
        assertTrue("不存在的锚点应被标为无效", validated.citations.invalid.isNotEmpty())
    }

    @Test
    fun `理解包仍然要求六段齐全`() {
        // 分派不能把理解包的检查一起放宽掉。
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val partial = "## 分层摘要\n摘要 [PARAGRAPH:1]"
        val validated = DocumentAiService.validateModelOutput(partial, context, DocumentAiService.KIND_STUDY_PACK)
        assertFalse("理解包缺段落应判失败", validated.isAcceptable)
        assertTrue(validated.missingSections.isNotEmpty())
    }

    private fun parsedDoc(format: String, vararg blocks: Block) = ParsedDoc(
        format = format,
        blocks = blocks.toList(),
        fullText = blocks.joinToString("\n") { it.text }
    )

    private fun anchoredContext(vararg anchors: DocumentAnchor) =
        DocumentAiService.AnchoredContext(
            text = "test context",
            anchors = anchors.toList(),
            includedSegments = anchors.size,
            totalSegments = anchors.size,
            truncated = false,
            maxChars = 2_048
        )
}
