package app.yeshu.reader.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EPUB 的真实形态覆盖。
 *
 * 真实电子书的结构差异远大于「一个标准 EPUB」：XHTML 里用未声明的 HTML 实体、
 * OPF 藏在子目录、container.xml 列了多个 rootfile、正文是 GBK 编码、
 * 纯图片的固定版式、带 DRM 声明……任何一条没处理到，用户看到的就是「打不开」。
 *
 * 断言写的是期望行为，失败即代表兼容性缺口。
 */
class DocParserEpubVariantsTest {

    private fun corpus(name: String): File {
        val url = javaClass.getResourceAsStream("/corpus/$name")
            ?: error("语料缺失：$name")
        val file = File.createTempFile("epub-", "-" + name)
        url.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.deleteOnExit()
        return file
    }

    private fun parse(name: String): ParsedDoc = DocParser.parseText(corpus(name))

    @Test
    fun `XHTML 里的 HTML 实体不会导致整本打不开`() {
        // 最要命的一条：&nbsp; &mdash; 等在 XML 里是未定义实体，
        // 严格解析会直接抛错，而真实电子书大量使用它们。
        val doc = parse("epub_entities.epub")
        assertEquals("epub", doc.format)
        assertTrue("缺少标题：${doc.fullText}", doc.fullText.contains("第一章 实体"))
        assertTrue("缺少正文", doc.fullText.contains("不换行空格"))
        assertTrue("实体未被还原：${doc.fullText}", !doc.fullText.contains("&nbsp;"))
        assertTrue("破折号未还原", !doc.fullText.contains("&mdash;"))
    }

    @Test
    fun `OPF 在子目录且正文用相对路径回退引用`() {
        val doc = parse("epub_nested.epub")
        assertEquals("epub", doc.format)
        assertTrue("缺少第一章：${doc.fullText}", doc.fullText.contains("子目录里的正文"))
        assertTrue("缺少 ../ 回退引用的章节", doc.fullText.contains("回退引用的正文"))
    }

    @Test
    fun `container 列了多个 rootfile 时取可用的那个`() {
        val doc = parse("epub_multi_rootfile.epub")
        assertTrue("应跳过缺失的 rootfile：${doc.fullText}", doc.fullText.contains("第一个可用的 rootfile"))
    }

    @Test
    fun `纯图片的固定版式 EPUB 不应报错`() {
        // 漫画/扫描书的 spine 全是包着 <img> 的页面，没有任何正文文字。
        // 直接抛「未提取到文本」会让用户以为文件坏了。
        val doc = parse("epub_image_only.epub")
        assertEquals("epub", doc.format)
        assertTrue("应说明这是图片型书籍：${doc.fullText}", doc.fullText.contains("图片"))
    }

    @Test
    fun `GBK 编码的 XHTML 能正确解码`() {
        val doc = parse("epub_gbk.epub")
        assertTrue("GBK 正文解码失败：${doc.fullText}", doc.fullText.contains("GBK 编码的正文段落"))
    }

    @Test
    fun `没有 spine 的 EPUB 给出可读原因`() {
        val error = runCatching { parse("epub_no_spine.epub") }.exceptionOrNull()
        assertNotNull("无 spine 应当报错而不是产出空文档", error)
        assertTrue("错误信息应可读：${error!!.message}", error is ParseException)
        assertTrue("应说明是 spine 为空：${error.message}", error.message?.contains("spine") == true)
    }

    @Test
    fun `带 DRM 声明的 EPUB 提示受保护`() {
        val error = runCatching { parse("epub_drm.epub") }.exceptionOrNull()
        assertNotNull("加密书应当报错", error)
        assertTrue(
            "应提示受 DRM 保护而不是格式不支持：${error!!.message}",
            error.message?.contains("保护") == true || error.message?.contains("加密") == true,
        )
    }

    @Test
    fun `spine 中 linear=no 的封面页不会被当正文`() {
        val doc = parse("epub_linear_no.epub")
        assertTrue("应包含正文", doc.fullText.contains("正文内容"))
        // linear=no 是「非顺序内容」，不应混进正文流
        assertTrue("封面占位不应进入正文：${doc.fullText}", !doc.fullText.contains("封面占位"))
    }

    @Test
    fun `不规范 XHTML 也能提取正文`() {
        val doc = parse("epub_sloppy.epub")
        assertTrue("缺少标题：${doc.fullText}", doc.fullText.contains("第一章 不规范"))
        assertTrue("缺少正文", doc.fullText.contains("第一行"))
        assertTrue("缺少不加引号属性的段落", doc.fullText.contains("居中段落"))
    }

    @Test
    fun `带 UTF-8 BOM 的 XHTML 不会把 BOM 读进正文`() {
        val doc = parse("epub_bom.epub")
        assertTrue("缺少标题：${doc.fullText}", doc.fullText.contains("第一章 BOM"))
        assertTrue("正文里混进了 BOM", !doc.fullText.contains('\uFEFF'))
    }
}
