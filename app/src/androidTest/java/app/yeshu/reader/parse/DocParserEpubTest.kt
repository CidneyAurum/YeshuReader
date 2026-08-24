package app.yeshu.reader.parse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocParserEpubTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun parseEpub_readsSpineOrderAndCover() {
        val file = File.createTempFile("yeshu-epub-", ".epub", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entry(zip, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container><rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles></container>
                """.trimIndent())
                entry(zip, "OPS/content.opf", """
                    <package><manifest>
                      <item id="cover" href="images/cover.png" properties="cover-image"/>
                      <item id="second" href="text/second.xhtml"/>
                      <item id="first" href="text/first.xhtml"/>
                    </manifest><spine>
                      <itemref idref="first"/><itemref idref="second"/>
                    </spine></package>
                """.trimIndent())
                entry(zip, "OPS/images/cover.png", "cover")
                entry(zip, "OPS/text/first.xhtml", "<html><body><h1>第一章</h1><p>先出现</p><script>忽略</script></body></html>")
                entry(zip, "OPS/text/second.xhtml", "<html><body><h2>第二章</h2><p>后出现</p></body></html>")
            }

            val parsed = DocParser.parseText(file)

            assertEquals("epub", parsed.format)
            assertEquals(listOf("第一章", "先出现", "第二章", "后出现"), parsed.blocks.map { it.text })
            assertEquals(listOf(Block.HEADING, Block.TEXT, Block.HEADING, Block.TEXT), parsed.blocks.map { it.type })
            assertEquals("cover", parsed.coverBytes?.toString(Charsets.UTF_8))
            assertTrue(parsed.fullText.contains("先出现\n第二章"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsePptx_usesPresentationRelationshipOrder() {
        val file = File.createTempFile("yeshu-slides-", ".pptx", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entry(zip, "ppt/presentation.xml", """
                    <p:presentation xmlns:p="urn:p" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId1"/></p:sldIdLst>
                    </p:presentation>
                """.trimIndent())
                entry(zip, "ppt/_rels/presentation.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
                    </Relationships>
                """.trimIndent())
                entry(zip, "ppt/slides/slide1.xml", slideXml("第一页文件"))
                entry(zip, "ppt/slides/slide2.xml", slideXml("第二页文件但实际在前"))
            }

            val parsed = DocParser.parseText(file)

            assertEquals("pptx", parsed.format)
            assertEquals("第二页文件但实际在前", parsed.blocks.first { it.type == Block.TEXT }.text)
            assertTrue(parsed.fullText.indexOf("第二页文件但实际在前") < parsed.fullText.indexOf("第一页文件"))
        } finally {
            file.delete()
        }
    }

    private fun slideXml(text: String) =
        "<p:sld xmlns:p=\"urn:p\" xmlns:a=\"urn:a\"><a:p><a:t>$text</a:t></a:p></p:sld>"

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
