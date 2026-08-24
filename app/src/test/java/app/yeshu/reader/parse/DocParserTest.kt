package app.yeshu.reader.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class DocParserTest {

    @Test
    fun detect_usesCurrentExtensionApi() {
        assertEquals("md", DocParser.detect("notes.MD"))
        assertEquals("md", DocParser.detect("notes.markdown"))
        assertEquals("jpg", DocParser.detect("cover.JPEG"))
        assertEquals("epub", DocParser.detect("book.EPUB"))
        assertEquals("", DocParser.detect("book.unknown"))
        assertEquals("", DocParser.detect("README"))
    }

    @Test
    fun parseMarkdown_preservesMarkdownFormatAndHeadings() {
        val file = Files.createTempFile("yeshu-markdown-", ".md").toFile()
        try {
            file.writeText("# Markdown title\n正文\n\n第二章 继续\n段落", Charsets.UTF_8)

            val parsed = DocParser.parseText(file)

            assertEquals("md", parsed.format)
            assertEquals(
                listOf(
                    Block(Block.HEADING, "Markdown title"),
                    Block(Block.TEXT, "正文"),
                    Block(Block.HEADING, "第二章 继续"),
                    Block(Block.TEXT, "段落")
                ),
                parsed.blocks
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun resolvePath_decodesHrefAndRejectsArchiveTraversal() {
        assertEquals("OPS/text/chapter.xhtml", DocParser.resolvePath("OPS/text", "chapter.xhtml"))
        assertEquals("OPS/images/cover.png", DocParser.resolvePath("OPS/text", "../images/cover.png"))
        assertEquals("OPS/text/chapter one.xhtml", DocParser.resolvePath("OPS/text", "chapter%20one.xhtml#part-2"))
        assertEquals("OPS/text/chapter.xhtml", DocParser.resolvePath("OPS/text", "./chapter.xhtml"))
        assertThrows(ParseException::class.java) {
            DocParser.resolvePath("OPS/text", "../../../../cover.png")
        }
    }
}
