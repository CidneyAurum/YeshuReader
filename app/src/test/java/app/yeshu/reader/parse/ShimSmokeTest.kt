package app.yeshu.reader.parse

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShimSmokeTest {
    @Test
    fun docxParsesOnJvm() {
        val f = File.createTempFile("shim-", ".docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                     <w:body><w:p><w:r><w:t>第一章 测试标题</w:t></w:r></w:p>
                     <w:p><w:r><w:t>这是正文段落。</w:t></w:r></w:p></w:body>
                   </w:document>""".toByteArray()
            )
            z.closeEntry()
        }
        try {
            val parsed = DocParser.parseText(f)
            assertTrue("blocks=${parsed.blocks}", parsed.fullText.contains("这是正文段落。"))
        } finally { f.delete() }
    }
}
