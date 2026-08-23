package app.yeshu.reader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 统一文档块模型 */
data class Block(val type: Int, val text: String) {
    companion object {
        const val TEXT = 0
        const val HEADING = 1
    }
}

data class ParsedDoc(
    val format: String,
    val blocks: List<Block>,
    val fullText: String,
    val coverBytes: ByteArray? = null
)

/** 解析失败时抛出，调用方展示错误信息 */
class ParseException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * 零依赖文档解析引擎。
 * TXT/EPUB/DOCX/PPTX 全部基于 java.util.zip + XmlPullParser 实现，
 * PDF 走 android.graphics.pdf（见 ReaderView 的 PdfSession）。
 */
object DocParser {

    private val EXT_MAP = mapOf(
        "txt" to "txt", "md" to "txt", "log" to "txt",
        "epub" to "epub",
        "docx" to "docx", "docm" to "docx",
        "pptx" to "pptx", "ppsx" to "pptx",
        "pdf" to "pdf",
        "jpg" to "jpg", "jpeg" to "jpeg", "png" to "png"
    )

    /** 按扩展名判断格式；未知返回空串 */
    fun detect(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_MAP[ext] ?: ""
    }

    /** ZIP 魔数嗅探：无扩展名或扩展名不可信时用内容判型 */
    private fun sniffZip(file: File): String? {
        return try {
            ZipFile(file).use { z ->
                if (z.getEntry("word/document.xml") != null) "docx"
                else if (z.entries().toList().any {
                        e -> e.name.matches(Regex("ppt/slides/slide\\d+\\.xml"))
                    }) "pptx"
                else if (z.getEntry("META-INF/container.xml") != null) "epub"
                else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 文本类格式统一入口（PDF 由阅读器单独处理） */
    fun parseText(file: File): ParsedDoc {
        var fmt = detect(file.name)
        if (fmt.isEmpty() || fmt == "txt") {
            // txt 优先按扩展名；非 txt 扩展名但内容是 zip 则嗅探
            if (fmt.isEmpty()) {
                val head = file.inputStream().use { ins ->
                    val b = ByteArray(2)
                    val n = ins.read(b)
                    if (n == 2) String(b) else ""
                }
                if (head == "PK") fmt = sniffZip(file) ?: throw ParseException("无法识别的文件格式")
            }
        }
        return when (fmt) {
            "txt" -> parseTxt(file)
            "epub" -> parseEpub(file)
            "docx" -> parseDocx(file)
            "pptx" -> parsePptx(file)
            "pdf" -> throw ParseException("请使用 PDF 阅读通道打开")
            else -> throw ParseException("不支持的格式")
        }
    }

    // ---------- TXT ----------

    private val CHAPTER_RE = Regex(
        "^\\s*(第[0-9０-９零一二三四五六七八九十百千两]+[章节卷回部篇集]|序章|序言|楔子|引子|后记|尾声|终章|附录|番外|Chapter\\s+\\d+).{0,40}$",
        RegexOption.IGNORE_CASE
    )

    private fun parseTxt(file: File): ParsedDoc {
        val bytes = file.readBytes()
        var s = String(bytes, Charsets.UTF_8)
        if (s.contains('\uFFFD')) {
            try { s = String(bytes, charset("GBK")) } catch (e: Exception) {}
        }
        // 去 UTF-8 BOM（不少编辑器会写入，粘在首个章节标题前会导致识别失败）
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        val blocks = mutableListOf<Block>()
        for (para in s.split(Regex("\\r?\\n\\s*\\r?\\n|\\r?\\n"))) {
            val t = para.trim()
            if (t.isEmpty()) continue
            // 章节标题自动识别（第X章/卷、序章、楔子等）
            blocks.add(if (CHAPTER_RE.matches(t) && t.length <= 50) Block(Block.HEADING, t)
                       else Block(Block.TEXT, t))
        }
        return build("txt", blocks)
    }

    // ---------- EPUB ----------

    private fun parseEpub(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            val containerXml = readEntry(z, "META-INF/container.xml")
                ?: throw ParseException("EPUB 缺少 container.xml")
            val opfPath = firstTagAttr(containerXml, "rootfile", "full-path")
                ?: throw ParseException("EPUB container.xml 格式异常")
            val opfXml = readEntry(z, opfPath)
                ?: throw ParseException("EPUB 缺少 OPF: $opfPath")

            // manifest: id -> (href, properties)；同时抓 epub2 的 cover meta
            data class MItem(val id: String, val href: String, val props: String)
            val manifest = mutableListOf<MItem>()
            var metaCoverId: String? = null
            xmlPull(opfXml) { xp ->
                while (true) {
                    val ev = xp.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.START_TAG) {
                        when (xp.name) {
                            "item" -> {
                                val id = attrOf(xp, "id")
                                val href = attrOf(xp, "href")
                                if (id != null && href != null) {
                                    manifest.add(MItem(id, href, attrOf(xp, "properties") ?: ""))
                                }
                            }
                            "meta" -> {
                                if (attrOf(xp, "name") == "cover") metaCoverId = attrOf(xp, "content")
                            }
                        }
                    }
                }
            }

            // spine: 按顺序的 idref
            val spine = mutableListOf<String>()
            xmlPull(opfXml) { xp ->
                while (true) {
                    val ev = xp.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.START_TAG && xp.name == "itemref") {
                        attrOf(xp, "idref")?.let { spine.add(it) }
                    }
                }
            }

            // 封面：epub3 properties="cover-image" 优先，其次 epub2 meta
            val baseDir = opfPath.substringBeforeLast('/', "")
            var coverBytes: ByteArray? = null
            val coverItem = manifest.firstOrNull { it.props.split(' ').contains("cover-image") }
                ?: manifest.firstOrNull { it.id == metaCoverId }
            if (coverItem != null) {
                val imgRe = Regex("\\.(png|jpe?g|gif|webp)$")
                if (imgRe.containsMatchIn(coverItem.href.lowercase())) {
                    coverBytes = readEntryBytes(z, resolvePath(baseDir, coverItem.href))
                }
            }

            val hrefMap = manifest.associate { it.id to it.href }
            val blocks = mutableListOf<Block>()
            for (idref in spine) {
                val href = hrefMap[idref] ?: continue
                val lower = href.lowercase()
                if (!lower.endsWith(".xhtml") && !lower.endsWith(".html") && !lower.endsWith(".htm")) continue
                val entryPath = resolvePath(baseDir, href)
                val html = readEntry(z, entryPath) ?: continue
                extractXhtml(html, blocks)
            }
            if (blocks.isEmpty()) throw ParseException("EPUB 内未提取到文本")
            return ParsedDoc("epub", blocks, buildText(blocks), coverBytes)
        }
    }

    // ---------- DOCX ----------

    private fun parseDocx(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            val docXml = readEntry(z, "word/document.xml")
                ?: throw ParseException("不是有效的 Word 文档（缺少 word/document.xml）")
            val blocks = mutableListOf<Block>()
            xmlPull(docXml) { xp ->
                var para = StringBuilder()
                var headingLevel = 0
                var inT = false
                loop@ while (true) {
                    val ev = xp.next()
                    when (ev) {
                        XmlPullParser.END_DOCUMENT -> break@loop
                        XmlPullParser.START_TAG -> when (xp.name) {
                            "p" -> { para = StringBuilder(); headingLevel = 0; inT = false }
                            "t" -> inT = true
                            "tab" -> para.append(' ')
                            "br" -> para.append('\n')
                            "pStyle" -> {
                                val v = attrOf(xp, "val") ?: ""
                                if (v.lowercase().startsWith("heading") ||
                                    v.equals("title", true)) headingLevel = 2
                            }
                        }
                        XmlPullParser.TEXT -> if (inT) para.append(xp.text)
                        XmlPullParser.END_TAG -> when (xp.name) {
                            "t" -> inT = false
                            "p" -> {
                                val t = para.toString().trim()
                                if (t.isNotEmpty()) {
                                    blocks.add(if (headingLevel > 0) Block(Block.HEADING, t)
                                               else Block(Block.TEXT, t))
                                }
                                para = StringBuilder()
                            }
                        }
                    }
                }
            }
            if (blocks.isEmpty()) throw ParseException("Word 文档内未提取到文本")
            return build("docx", blocks)
        }
    }

    // ---------- PPTX ----------

    private fun parsePptx(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            val slideNames = z.entries().toList()
                .map { it.name }
                .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }

            if (slideNames.isEmpty()) throw ParseException("不是有效的 PPT 文档（缺少幻灯片）")

            val blocks = mutableListOf<Block>()
            slideNames.forEachIndexed { idx, name ->
                val xml = readEntry(z, name) ?: return@forEachIndexed
                blocks.add(Block(Block.HEADING, "— 第 ${idx + 1} 页 —"))
                xmlPull(xml) { xp ->
                    var line = StringBuilder()
                    var inT = false
                    loop@ while (true) {
                        val ev = xp.next()
                        when (ev) {
                            XmlPullParser.END_DOCUMENT -> break@loop
                            XmlPullParser.START_TAG -> when (xp.name) {
                                "p" -> { line = StringBuilder(); inT = false }
                                "t" -> inT = true
                            }
                            XmlPullParser.TEXT -> if (inT) line.append(xp.text)
                            XmlPullParser.END_TAG -> when (xp.name) {
                                "t" -> inT = false
                                "p" -> {
                                    val t = line.toString().trim()
                                    if (t.isNotEmpty()) blocks.add(Block(Block.TEXT, t))
                                }
                            }
                        }
                    }
                }
            }
            if (blocks.none { it.type == Block.TEXT }) throw ParseException("PPT 内未提取到文本")
            return build("pptx", blocks)
        }
    }

    // ---------- 公共工具 ----------

    private fun build(format: String, blocks: List<Block>): ParsedDoc {
        val ft = StringBuilder()
        for (b in blocks) {
            if (ft.length > 400_000) break
            ft.append(b.text).append('\n')
        }
        return ParsedDoc(format, blocks, ft.toString().take(400_000))
    }

    private fun buildText(blocks: List<Block>): String {
        val ft = StringBuilder()
        for (b in blocks) {
            if (ft.length > 400_000) break
            ft.append(b.text).append('\n')
        }
        return ft.toString().take(400_000)
    }

    private fun readEntry(z: ZipFile, path: String): String? {
        val e: ZipEntry = z.getEntry(path) ?: return null
        return z.getInputStream(e).use { ins -> ins.readBytes().toString(Charsets.UTF_8) }
    }

    private fun readEntryBytes(z: ZipFile, path: String): ByteArray? {
        val e: ZipEntry = z.getEntry(path) ?: return null
        return z.getInputStream(e).use { ins -> ins.readBytes() }
    }

    private inline fun xmlPull(xml: String, body: (XmlPullParser) -> Unit) {
        val xp = Xml.newPullParser()
        xp.setInput(StringReader(xml))
        body(xp)
    }

    private fun attrOf(xp: XmlPullParser, localName: String): String? {
        for (i in 0 until xp.attributeCount) {
            if (xp.getAttributeName(i) == localName) return xp.getAttributeValue(i)
        }
        return null
    }

    private fun firstTagAttr(xml: String, tag: String, attr: String): String? {
        var result: String? = null
        xmlPull(xml) { xp ->
            while (true) {
                val ev = xp.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && xp.name == tag) {
                    result = attrOf(xp, attr)
                    break
                }
            }
        }
        return result
    }

    /** 提取 XHTML 可读文本：h1-h6 → 标题块，p/li/blockquote → 段落块，忽略 script/style */
    private fun extractXhtml(html: String, out: MutableList<Block>) {
        val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val PARA = setOf("p", "li", "blockquote", "div")
        val SKIP = setOf("script", "style")
        var buf = StringBuilder()
        var curHeading = false
        var skipDepth = 0

        xmlPull(html) { xp ->
            loop@ while (true) {
                val ev = xp.next()
                when (ev) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> {
                        val n = xp.name.lowercase()
                        if (n in SKIP) skipDepth++
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth == 0) buf.append(xp.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val n = xp.name.lowercase()
                        if (n in SKIP && skipDepth > 0) { skipDepth--; continue@loop }
                        if (skipDepth > 0) continue@loop
                        if (n in HEADINGS || n in PARA || n == "title") {
                            val t = buf.toString().trim()
                            buf = StringBuilder()
                            if (t.isEmpty()) continue@loop
                            if (curHeading || n in HEADINGS || n == "title") {
                                out.add(Block(Block.HEADING, t))
                                curHeading = false
                            } else if (n != "div") {
                                out.add(Block(Block.TEXT, t))
                            } else {
                                // 裸 div 文本也收进正文
                                out.add(Block(Block.TEXT, t))
                            }
                        }
                        if (n in HEADINGS) curHeading = false
                    }
                }
            }
        }
    }

    /** 相对路径解析（OPF 目录 + ../..） */
    internal fun resolvePath(baseDir: String, href: String): String {
        val parts = ArrayDeque(baseDir.split('/').filter { it.isNotEmpty() && it != "." })
        for (seg in href.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }
}
