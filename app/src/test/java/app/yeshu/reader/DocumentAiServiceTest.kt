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
