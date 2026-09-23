package app.yeshu.reader.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实文件**跑解析器的兼容性测试。
 *
 * 语料由 `tools/make_test_corpus.py` 生成（python-docx / python-pptx / ebooklib /
 * reportlab 等真实生产者产出），位于 `src/test/resources/corpus/`。
 *
 * 为什么要这样做：此前 EPUB / DOCX / PPTX 三个解析器在离线测试里完全不可达
 * （依赖 android.util.Xml 的 stub），只靠读代码判断「应该没问题」。真实文件能暴露
 * 手写 XML 永远测不到的东西——生产者写入的额外部件、命名空间变体、编码差异、
 * 嵌套结构、以及各种损坏形态。
 *
 * 断言写的是**期望行为**：失败即代表兼容性缺口，而不是测试需要放宽。
 */
class DocParserCorpusTest {

    private fun corpus(name: String): File {
        val url = javaClass.getResourceAsStream("/corpus/$name")
            ?: error("语料缺失：$name（先运行 python tools/make_test_corpus.py）")
        val file = File.createTempFile("corpus-", "-" + name)
        url.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.deleteOnExit()
        return file
    }

    private fun parse(name: String): ParsedDoc = DocParser.parseText(corpus(name))

    private fun texts(doc: ParsedDoc) = doc.blocks.filter { it.type == Block.TEXT }.map { it.text }
    private fun headings(doc: ParsedDoc) = doc.blocks.filter { it.type == Block.HEADING }.map { it.text }

    // ------------------------------------------------------------ Office

    @Test
    fun `真实 docx 提取标题 正文 列表与表格`() {
        val doc = parse("sample.docx")
        assertEquals("docx", doc.format)
        assertTrue("缺少一级标题：${headings(doc)}", headings(doc).any { it.contains("第一章") })
        assertTrue("缺少二级标题", headings(doc).any { it.contains("1.1") })
        val body = texts(doc).joinToString("\n")
        assertTrue("缺少正文", body.contains("登录模块的重构"))
        assertTrue("缺少项目符号项", body.contains("下周三前完成登录模块重构"))
        assertTrue("缺少表格内容", body.contains("小张"))
        assertTrue("缺少长段落", body.contains("用于测试长段落分块"))
    }

    @Test
    fun `docm 与 docx 同样处理`() {
        val doc = parse("sample.docm")
        assertEquals("docx", doc.format)
        assertTrue(doc.fullText.contains("登录模块的重构"))
    }

    @Test
    fun `空 docx 给出可读错误而不是崩溃`() {
        val error = runCatching { parse("empty.docx") }.exceptionOrNull()
        assertNotNull("空文档应当报错", error)
        assertTrue("错误信息应可读：${error!!.message}", error is ParseException)
    }

    @Test
    fun `缺少 document xml 的 docx 给出明确原因`() {
        val error = runCatching { parse("broken.docx") }.exceptionOrNull()
        assertTrue("应提示缺少 word/document.xml：${error?.message}", error?.message?.contains("document.xml") == true)
    }

    @Test
    fun `真实 pptx 提取每页标题与要点`() {
        val doc = parse("sample.pptx")
        assertEquals("pptx", doc.format)
        val body = doc.fullText
        assertTrue("缺少第 1 页标题：$body", body.contains("项目概览"))
        assertTrue("缺少第 2 页标题", body.contains("风险"))
        assertTrue("缺少第 3 页标题", body.contains("分工"))
        assertTrue("缺少要点内容", body.contains("完成登录模块重构"))
        assertTrue("缺少表格内容", body.contains("小张"))
    }

    @Test
    fun `ppsx 与 pptx 同样处理`() {
        assertEquals("pptx", parse("sample.ppsx").format)
    }

    // ------------------------------------------------------------ EPUB

    @Test
    fun `真实 epub2 提取多章正文`() {
        val doc = parse("sample_epub2.epub")
        assertEquals("epub", doc.format)
        assertTrue("缺少第一章：${doc.fullText}", doc.fullText.contains("排期"))
        assertTrue("缺少第二章", doc.fullText.contains("接口文档"))
        assertTrue("章节应有标题块：${headings(doc)}", headings(doc).isNotEmpty())
    }

    @Test
    fun `epub 的 head 元数据不进正文`() {
        // 真机样本的每个章节文件长这样：<head><title>第一章 排期</title></head>
        //                            <body><h1>第一章 排期</h1><p>…</p></body>
        // 之前把 <title> 也当成标题块，于是目录里每章出现两次（title 一次、h1 一次），
        // 章节计数也随之虚高——真机目录里「第一章 排期」确实重复显示了两遍。
        val doc = parse("sample_epub2.epub")
        val headingTexts = headings(doc)
        assertEquals("每章只应有一个标题块", headingTexts.distinct().size, headingTexts.size)
        assertEquals(listOf("第一章 排期", "第二章 文档"), headingTexts)
    }

    @Test
    fun `段落内部的换行不会被当成新段落`() {
        // 第一章的 <p> 内含一个换行；它仍是一个段落块。
        // 段落数决定了 AI 引用编号 [PARAGRAPH:n] 能否跳回原文，数错就跳错位置。
        val doc = parse("sample_epub2.epub")
        val paragraphs = doc.blocks.filter { it.type == Block.TEXT && it.text.isNotBlank() }
        assertEquals("两章各一个 <p>，共两段", 2, paragraphs.size)
        assertTrue("段落应保留内部换行", paragraphs.first().text.contains("\n"))
    }

    @Test
    fun `真实 epub3 嵌套目录也能提取`() {
        val doc = parse("sample_epub3.epub")
        assertEquals("epub", doc.format)
        assertTrue("缺少第一部分", doc.fullText.contains("开篇"))
        assertTrue("缺少嵌套章节", doc.fullText.contains("嵌套目录下的正文"))
        assertTrue("缺少第二个嵌套章节", doc.fullText.contains("嵌套目录下的第二节"))
    }

    @Test
    fun `缺少 container xml 的 epub 给出明确原因`() {
        val error = runCatching { parse("broken.epub") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("应说明不是有效 EPUB：${error!!.message}", error is ParseException)
    }

    // ------------------------------------------------------------ 其它格式

    @Test
    fun `odt 可以解析`() {
        val doc = parse("sample.odt")
        assertEquals("odt", doc.format)
        assertTrue("缺少 ODT 标题：${doc.fullText}", doc.fullText.contains("ODT 标题"))
        assertTrue("缺少 ODT 正文", doc.fullText.contains("ODT 的正文段落"))
        assertTrue("缺少行内标记文本", doc.fullText.contains("行内标记"))
    }

    @Test
    fun `单文件 html 可以解析并丢弃脚本样式`() {
        val doc = parse("sample.html")
        assertEquals("html", doc.format)
        assertTrue("缺少标题", doc.fullText.contains("网页正文"))
        assertTrue("缺少正文", doc.fullText.contains("单文件 HTML 文档的正文"))
        assertTrue("缺少列表项", doc.fullText.contains("要点一"))
        assertTrue("不应包含脚本内容：${doc.fullText}", !doc.fullText.contains("var x"))
        assertTrue("不应包含样式内容", !doc.fullText.contains("color: red"))
    }

    @Test
    fun `rtf 可以解析并去掉控制字`() {
        val doc = parse("sample.rtf")
        assertEquals("rtf", doc.format)
        assertTrue("缺少 RTF 正文：${doc.fullText}", doc.fullText.contains("正文段落"))
        assertTrue("不应残留控制字：${doc.fullText}", !doc.fullText.contains("\\par"))
    }

    @Test
    fun `fb2 可以解析`() {
        val doc = parse("sample.fb2")
        assertEquals("fb2", doc.format)
        assertTrue("缺少 FB2 标题：${doc.fullText}", doc.fullText.contains("FB2 标题"))
        assertTrue("缺少 FB2 正文", doc.fullText.contains("FB2 的正文段落"))
        assertTrue("缺少小节", doc.fullText.contains("小节正文"))
    }

    @Test
    fun `csv 与 tsv 可以解析`() {
        assertTrue("CSV 缺少内容", parse("sample.csv").fullText.contains("登录"))
        assertTrue("TSV 缺少内容", parse("sample.tsv").fullText.contains("小张"))
    }

    @Test
    fun `xlsx 提取单元格文本`() {
        val doc = parse("sample.xlsx")
        assertEquals("xlsx", doc.format)
        assertTrue("缺少表头：${doc.fullText}", doc.fullText.contains("负责人"))
        assertTrue("缺少单元格内容", doc.fullText.contains("小张"))
        assertTrue("缺少第二个工作表", doc.fullText.contains("联调冲突"))
    }

    @Test
    fun `cbz 作为图片书可识别`() {
        val doc = parse("sample.cbz")
        assertEquals("cbz", doc.format)
        assertTrue("应报告页数：${doc.fullText}", doc.fullText.contains("3"))
    }

    @Test
    fun `zip 包裹的文档可以自动下钻`() {
        val doc = parse("wrapped.zip")
        assertTrue("应能读到包裹内的 markdown：${doc.fullText}", doc.fullText.contains("排期与重构"))
    }

    // ------------------------------------------------------------ 文本编码

    @Test
    fun `各种编码的中文文本都能正确解码`() {
        for (name in listOf("utf8.txt", "utf8_bom.txt", "utf16le.txt", "utf16be_bom.txt",
            "utf16le_bom.txt", "utf16be_nobom.txt", "gbk.txt", "crlf.txt")) {
            val doc = parse(name)
            assertTrue("$name 解码失败：${doc.fullText.take(60)}", doc.fullText.contains("排期与重构"))
            assertTrue("$name 缺少第二章", doc.fullText.contains("接口文档"))
            assertTrue("$name 不应出现 NUL：${doc.fullText.take(40)}", !doc.fullText.contains('\u0000'))
        }
    }

    @Test
    fun `纯 ASCII 不会被误判为 UTF-16`() {
        val doc = parse("ascii.txt")
        assertTrue("ASCII 解码失败：${doc.fullText.take(60)}", doc.fullText.contains("plain english text"))
        assertTrue("出现 NUL 说明被当成 UTF-16", !doc.fullText.contains('\u0000'))
    }

    @Test
    fun `空文件与纯空白文件给出可读结果`() {
        for (name in listOf("empty.txt", "blank.txt")) {
            val error = runCatching { parse(name) }.exceptionOrNull()
            assertNotNull("$name 应当报错而不是产出空文档", error)
            assertTrue("$name 错误信息应可读", error is ParseException)
        }
    }

    @Test
    fun `没有换行的超长单行会被切分而不是只留一段`() {
        val doc = parse("one_line.txt")
        assertTrue("应当切成多个块：${doc.blocks.size}", doc.blocks.size > 1)
        assertTrue("内容不应丢失：${doc.fullText.length}", doc.fullText.length > 5000)
    }

    @Test
    fun `大量段落不会丢失或超限崩溃`() {
        val doc = parse("many_paragraphs.txt")
        assertTrue("块数过少：${doc.blocks.size}", doc.blocks.size > 1000)
        assertTrue("应保留首段", doc.fullText.contains("第1段"))
    }

    // ------------------------------------------------------------ 格式嗅探

    @Test
    fun `扩展名与实际内容不符时以内容判型`() {
        // mismatched.jpg 实际是 PNG
        assertEquals("png", DocParser.detectContent(corpus("mismatched.jpg"), "jpg"))
    }

    @Test
    fun `无扩展名的 zip 内容能被嗅探`() {
        val docx = corpus("sample.docx")
        val renamed = File.createTempFile("noext-", "")
        docx.copyTo(renamed, overwrite = true)
        try {
            assertEquals("docx", DocParser.detectContent(renamed, ""))
        } finally {
            renamed.delete()
        }
    }

    @Test
    fun `各种图片格式都能识别`() {
        assertEquals("jpg", DocParser.detectContent(corpus("cover.jpg"), "jpg"))
        assertEquals("png", DocParser.detectContent(corpus("cover.png"), "png"))
        assertEquals("webp", DocParser.detectContent(corpus("cover.webp"), "webp"))
        assertEquals("bmp", DocParser.detectContent(corpus("cover.bmp"), "bmp"))
        assertEquals("gif", DocParser.detectContent(corpus("cover.gif"), "gif"))
    }

    // ------------------------------------------------------------ 恶意输入

    @Test
    fun `路径穿越条目被拒绝`() {
        val error = runCatching { parse("traversal.docx") }.exceptionOrNull()
        assertNotNull("含 ../ 的归档应当被拒绝", error)
    }

    @Test
    fun `高压缩比归档被拒绝`() {
        val error = runCatching { parse("bomb.docx") }.exceptionOrNull()
        assertNotNull("压缩炸弹应当被拒绝", error)
    }

    @Test
    fun `截断的 zip 给出可读错误`() {
        val error = runCatching { parse("truncated.docx") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("错误信息应可读：${error!!.message}", error is ParseException)
    }

    // ------------------------------------------------------------ WPS / ODF 变体

    @Test
    fun `WPS 三种格式按 OOXML 处理`() {
        // .wps/.et/.dps 与 docx/xlsx/pptx 同构，只是扩展名不同
        assertEquals("docx", parse("sample.wps").format)
        assertTrue("WPS 文字缺少正文", parse("sample.wps").fullText.contains("登录模块的重构"))
        assertEquals("xlsx", parse("sample.et").format)
        assertEquals("pptx", parse("sample.dps").format)
    }

    @Test
    fun `没有 META-INF 的 ODT 也能读`() {
        val doc = parse("minimal.odt")
        assertEquals("odt", doc.format)
        assertTrue("缺少 ODT 正文", doc.fullText.contains("ODT 的正文段落"))
    }

    // ------------------------------------------------------------ 压缩包边界

    @Test
    fun `嵌套压缩包不会无限下钻`() {
        val error = runCatching { parse("nested.zip") }.exceptionOrNull()
        assertNotNull("内层还是压缩包时应当报错而不是递归", error)
        assertTrue("错误信息应可读：${error!!.message}", error is ParseException)
    }

    @Test
    fun `空压缩包给出可读错误`() {
        val error = runCatching { parse("empty.zip") }.exceptionOrNull()
        assertTrue("应提示压缩包为空：${error?.message}", error?.message?.contains("空") == true)
    }

    @Test
    fun `压缩包内多个文档时给出可读提示`() {
        val error = runCatching { parse("multi.zip") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("错误信息应可读：${error!!.message}", error is ParseException)
    }

    @Test
    fun `图片集页数按自然顺序排列`() {
        val doc = parse("sample.cbz")
        assertEquals("cbz", doc.format)
        assertEquals("应报告 3 页", 3, doc.pageEntries.size)
        assertEquals(listOf("page_001.png", "page_002.png", "page_003.png"), doc.pageEntries)
    }
}
