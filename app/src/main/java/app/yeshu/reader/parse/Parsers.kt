package app.yeshu.reader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.URLDecoder
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

    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024
    private const val MAX_XML_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_COVER_ENTRY_BYTES = 24 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 12_000
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 500L
    private const val MAX_BLOCKS = 50_000

    private val EXT_MAP = mapOf(
        "txt" to "txt", "md" to "md", "markdown" to "md", "log" to "txt",
        "epub" to "epub",
        "docx" to "docx", "docm" to "docx",
        "pptx" to "pptx", "ppsx" to "pptx",
        "pdf" to "pdf",
        "jpg" to "jpg", "jpeg" to "jpg", "png" to "png"
    )

    /** 按扩展名判断格式；未知返回空串 */
    fun detect(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_MAP[ext] ?: ""
    }

    /** ZIP 结构嗅探：无扩展名或扩展名不可信时用内容判型。 */
    private fun sniffZip(file: File): String? {
        return try {
            ZipFile(file).use { z ->
                validateArchive(z)
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

    /**
     * 复制完成后的内容复核。二进制签名和 ZIP 结构优先于扩展名/MIME，
     * 防止被重命名的 PDF、Office 文档或图片以 TXT 乱码入库。
     */
    fun detectContent(file: File, hintedFormat: String = detect(file.name)): String {
        if (!file.isFile || file.length() <= 0) return ""
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        if (head.size >= 5 && head.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) return "pdf"
        if (head.size >= 8 && head.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            )) return "png"
        if (head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) {
            return "jpg"
        }
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return sniffZip(file).orEmpty()
        }

        val hint = when (hintedFormat.lowercase()) {
            "markdown" -> "md"
            "jpeg" -> "jpg"
            else -> hintedFormat.lowercase()
        }
        if (looksLikeText(head)) return if (hint in setOf("md", "txt")) hint else "txt"
        return ""
    }

    /** 文本类格式统一入口（PDF 由阅读器单独处理） */
    fun parseText(file: File): ParsedDoc {
        val fmt = detectContent(file, detect(file.name))
        return when (fmt) {
            "txt" -> parseTxt(file)
            "md" -> parseMarkdown(file)
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
        val s = readTextFile(file)
        val blocks = mutableListOf<Block>()
        for (para in s.lineSequence()) {
            val t = para.trim()
            if (t.isEmpty()) continue
            // 章节标题自动识别（第X章/卷、序章、楔子等）
            blocks.add(if (CHAPTER_RE.matches(t) && t.length <= 50) Block(Block.HEADING, t)
                       else Block(Block.TEXT, t))
            if (blocks.size >= MAX_BLOCKS) break
        }
        return build("txt", blocks)
    }

    // ---------- Markdown ----------

    private val MARKDOWN_ATX_RE = Regex("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$")

    private fun parseMarkdown(file: File): ParsedDoc {
        val text = readTextFile(file)
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val value = paragraph.toString().trim()
            paragraph.setLength(0)
            if (value.isNotEmpty() && blocks.size < MAX_BLOCKS) blocks.add(Block(Block.TEXT, value))
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val heading = MARKDOWN_ATX_RE.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()
            val chapterHeading = line.trim().takeIf { CHAPTER_RE.matches(it) && it.length <= 50 }
            when {
                heading != null -> {
                    flushParagraph()
                    if (heading.isNotEmpty() && blocks.size < MAX_BLOCKS) {
                        blocks.add(Block(Block.HEADING, heading))
                    }
                }
                chapterHeading != null -> {
                    flushParagraph()
                    if (blocks.size < MAX_BLOCKS) blocks.add(Block(Block.HEADING, chapterHeading))
                }
                line.isBlank() -> flushParagraph()
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line.trim())
                }
            }
            if (blocks.size >= MAX_BLOCKS) break
        }
        flushParagraph()
        if (blocks.isEmpty()) throw ParseException("Markdown 文档内未提取到文本")
        return build("md", blocks)
    }

    // ---------- EPUB ----------

    private fun parseEpub(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
            val containerXml = readEntry(z, "META-INF/container.xml")
                ?: throw ParseException("EPUB 缺少 container.xml")
            val opfPath = firstTagAttr(containerXml, "rootfile", "full-path")
                ?.let { resolvePath("", it) }
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
                val coverPath = resolvePath(baseDir, coverItem.href)
                if (imgRe.containsMatchIn(coverPath.lowercase())) {
                    coverBytes = readEntryBytes(z, coverPath)
                }
            }

            val hrefMap = manifest.associate { it.id to it.href }
            val blocks = mutableListOf<Block>()
            for (idref in spine) {
                val href = hrefMap[idref] ?: continue
                val entryPath = resolvePath(baseDir, href)
                val lower = entryPath.lowercase()
                if (!lower.endsWith(".xhtml") && !lower.endsWith(".html") && !lower.endsWith(".htm")) continue
                val html = readEntry(z, entryPath) ?: continue
                extractXhtml(html, blocks)
                if (blocks.size >= MAX_BLOCKS) break
            }
            if (blocks.isEmpty()) throw ParseException("EPUB 内未提取到文本")
            return ParsedDoc("epub", blocks, buildText(blocks), coverBytes)
        }
    }

    // ---------- DOCX ----------

    private fun parseDocx(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
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
                                if (blocks.size >= MAX_BLOCKS) break@loop
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
            validateArchive(z)
            val slideNames = orderedSlideNames(z)

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
                                    if (blocks.size >= MAX_BLOCKS) break@loop
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

    /** PPTX 的实际页序由 presentation.xml + rels 决定，不能依赖 slideN 文件名。 */
    private fun orderedSlideNames(z: ZipFile): List<String> {
        val presentation = readEntry(z, "ppt/presentation.xml")
        val relationships = readEntry(z, "ppt/_rels/presentation.xml.rels")
        if (presentation != null && relationships != null) {
            val relationTargets = mutableMapOf<String, String>()
            xmlPull(relationships) { xp ->
                while (true) {
                    val event = xp.next()
                    if (event == XmlPullParser.END_DOCUMENT) break
                    if (event == XmlPullParser.START_TAG && xp.name == "Relationship") {
                        val id = attrOf(xp, "Id") ?: attrOf(xp, "id")
                        val target = attrOf(xp, "Target") ?: attrOf(xp, "target")
                        val type = attrOf(xp, "Type").orEmpty()
                        val external = attrOf(xp, "TargetMode").equals("External", ignoreCase = true)
                        if (id != null && target != null && !external && type.endsWith("/slide")) {
                            relationTargets[id] = target
                        }
                    }
                }
            }
            val orderedIds = mutableListOf<String>()
            xmlPull(presentation) { xp ->
                while (true) {
                    val event = xp.next()
                    if (event == XmlPullParser.END_DOCUMENT) break
                    if (event == XmlPullParser.START_TAG && xp.name == "sldId") {
                        relationshipIdOf(xp)?.let(orderedIds::add)
                    }
                }
            }
            val ordered = orderedIds.mapNotNull { relationTargets[it] }
                .map { target ->
                    if (target.startsWith('/')) resolvePath("", target.removePrefix("/"))
                    else resolvePath("ppt", target)
                }
                .filter { z.getEntry(it) != null }
                .distinct()
            if (ordered.isNotEmpty()) return ordered
        }
        return z.entries().toList()
            .map { it.name }
            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
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
        return z.getInputStream(e).use { ins ->
            readLimited(ins, MAX_XML_ENTRY_BYTES, "文档 XML").toString(Charsets.UTF_8)
        }
    }

    private fun readEntryBytes(z: ZipFile, path: String): ByteArray? {
        val e: ZipEntry = z.getEntry(path) ?: return null
        return z.getInputStream(e).use { ins -> readLimited(ins, MAX_COVER_ENTRY_BYTES, "封面图片") }
    }

    private fun readTextFile(file: File): String {
        if (file.length() > MAX_TEXT_BYTES) throw ParseException("文本文件过大（上限 32 MB）")
        val bytes = file.inputStream().use { readLimited(it, MAX_TEXT_BYTES, "文本文件") }
        var value = String(bytes, Charsets.UTF_8)
        if (value.contains('\uFFFD')) {
            try { value = String(bytes, charset("GBK")) } catch (_: Exception) { }
        }
        return value.removePrefix("\uFEFF")
    }

    private fun readLimited(input: InputStream, limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) throw ParseException("$label 超过安全大小限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateArchive(z: ZipFile) {
        var count = 0
        var total = 0L
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (++count > MAX_ARCHIVE_ENTRIES) throw ParseException("压缩文档包含过多条目")
            if (entry.isDirectory) continue
            val size = entry.size
            val compressed = entry.compressedSize
            if (size > 0) {
                total += size
                if (total > MAX_ARCHIVE_BYTES) throw ParseException("压缩文档解压后过大")
                if (compressed > 0 && size / compressed > MAX_COMPRESSION_RATIO) {
                    throw ParseException("压缩文档的压缩比异常")
                }
            }
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var controls = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value == 0) return false
            if (value < 0x20 && value !in setOf(0x09, 0x0a, 0x0d, 0x0c)) controls++
        }
        return controls * 20 <= bytes.size
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

    private fun relationshipIdOf(xp: XmlPullParser): String? {
        for (i in 0 until xp.attributeCount) {
            val namespace = xp.getAttributeNamespace(i).orEmpty()
            if (xp.getAttributeName(i) == "id" && namespace.contains("relationships")) {
                return xp.getAttributeValue(i)
            }
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
                if (out.size >= MAX_BLOCKS) break@loop
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

    /** URL 解码并规范化 ZIP 内相对路径；拒绝越过压缩包根目录。 */
    internal fun resolvePath(baseDir: String, href: String): String {
        val cut = href.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) href.length else it }
        val rawPath = href.substring(0, cut).replace('\\', '/')
        val decoded = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            throw ParseException("文档包含无效的 URL 编码路径", e)
        }
        val parts = ArrayDeque<String>()
        for (seg in baseDir.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else throw ParseException("文档路径越过压缩包根目录")
                else -> parts.addLast(seg)
            }
        }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else throw ParseException("文档路径越过压缩包根目录")
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }
}
