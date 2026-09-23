package app.yeshu.reader.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
    val coverBytes: ByteArray? = null,
    /** 图片集（CBZ）的页条目名，按阅读顺序排列；其它格式为空。 */
    val pageEntries: List<String> = emptyList()
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
        "txt" to "txt", "md" to "md", "markdown" to "md", "log" to "txt", "text" to "txt",
        "csv" to "csv", "tsv" to "csv",
        "epub" to "epub",
        "docx" to "docx", "docm" to "docx",
        "pptx" to "pptx", "ppsx" to "pptx",
        "pdf" to "pdf",
        // WPS Office 的三种格式与 OOXML 同构，只是扩展名不同
        "wps" to "docx", "et" to "xlsx", "dps" to "pptx",
        "odt" to "odt", "ott" to "odt",
        "xlsx" to "xlsx", "xlsm" to "xlsx",
        "html" to "html", "htm" to "html", "xhtml" to "html",
        "fb2" to "fb2",
        "rtf" to "rtf",
        "cbz" to "cbz", "zip" to "zip",
        "jpg" to "jpg", "jpeg" to "jpg", "png" to "png",
        "webp" to "webp", "gif" to "gif", "bmp" to "bmp"
    )

    /** 纯图片格式（含漫画页），阅读器按图片通道展示。 */
    private val IMAGE_FORMATS = setOf("jpg", "png", "webp", "gif", "bmp")

    /** 按扩展名判断格式；未知返回空串 */
    fun detect(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXT_MAP[ext] ?: ""
    }

    /**
     * ZIP 结构嗅探：无扩展名或扩展名不可信时用内容判型。
     * 顺序有意为之：OOXML/ODF 有明确的标志性部件，先判它们；
     * 漫画与「压缩包里装着一本书」放在最后，避免误吞真正的文档包。
     */
    private fun sniffZip(file: File): String? {
        return try {
            ZipFile(file).use { z ->
                validateArchive(z)
                val names = z.entries().toList().map { it.name }
                when {
                    "word/document.xml" in names -> "docx"
                    names.any { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) } -> "pptx"
                    "xl/workbook.xml" in names -> "xlsx"
                    "META-INF/container.xml" in names -> "epub"
                    // ODF 的正文固定叫 content.xml（OOXML 不用这个名字），
                    // 不额外要求 META-INF 存在——部分导出工具不写它。
                    "content.xml" in names -> "odt"
                    else -> sniffImageArchive(names) ?: sniffWrappedDocument(names)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 全部条目都是图片 → 漫画/图片集。 */
    private fun sniffImageArchive(names: List<String>): String? {
        val images = names.filter { !it.endsWith("/") }
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_FORMATS }
        return if (images.isNotEmpty() && images.size == names.count { !it.endsWith("/") }) "cbz" else null
    }

    /**
     * 压缩包里只装着一本书时直接下钻。
     * 网上分享的电子书经常先打成 zip，用户不该因此看到「不支持的格式」。
     */
    private fun sniffWrappedDocument(names: List<String>): String? {
        val candidates = names.filter { !it.endsWith("/") }
            .filter { it.substringAfterLast('.', "").lowercase() in EXT_MAP }
            .filter { it.substringAfterLast('.', "").lowercase() != "zip" }
        return if (candidates.size == 1) "zip" else null
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
        signatureFormat(head)?.let { return it }
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return sniffZip(file).orEmpty()
        }
        if (head.size >= 5 && head.copyOfRange(0, 5).contentEquals("{rtf".toByteArray())) return "rtf"

        val hint = when (hintedFormat.lowercase()) {
            "markdown" -> "md"
            "jpeg" -> "jpg"
            else -> hintedFormat.lowercase()
        }
        if (looksLikeText(head)) return if (hint in setOf("md", "txt", "csv", "html", "fb2", "rtf")) hint else "txt"
        return ""
    }

    /** 二进制签名判型（含 XML 文本型格式的头部特征）。 */
    private fun signatureFormat(head: ByteArray): String? {
        if (head.size >= 5 && head.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) return "pdf"
        if (head.size >= 8 && head.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            )) return "png"
        if (head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) {
            return "jpg"
        }
        // GIF87a / GIF89a
        if (head.size >= 6 && (head.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                head.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()))) return "gif"
        // BMP
        if (head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return "bmp"
        // RIFF....WEBP
        if (head.size >= 12 && head.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            head.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return "webp"
        return null
    }

    /** 文本类格式统一入口（PDF 由阅读器单独处理） */
    fun parseText(file: File): ParsedDoc {
        val fmt = detectContent(file, detect(file.name))
        return when (fmt) {
            "txt" -> parseTxt(file)
            "md" -> parseMarkdown(file)
            "csv" -> parseDelimited(file)
            "html" -> parseHtml(file)
            "fb2" -> parseFb2(file)
            "rtf" -> parseRtf(file)
            "epub" -> parseEpub(file)
            "docx" -> parseDocx(file)
            "pptx" -> parsePptx(file)
            "odt" -> parseOdt(file)
            "xlsx" -> parseXlsx(file)
            "cbz" -> parseImageArchive(file)
            "zip" -> parseWrappedArchive(file)
            "pdf" -> throw ParseException("请使用 PDF 阅读通道打开")
            in IMAGE_FORMATS -> throw ParseException("这是图片文件，请用图片通道打开")
            else -> throw ParseException(unsupportedMessage(file))
        }
    }

    /**
     * 归档读不出内容时说明缺了什么。
     * 原先一律回「不支持的格式」，用户拿着一份损坏的 docx 完全不知道问题在哪。
     */
    private fun unsupportedMessage(file: File): String {
        val isZip = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
        if (!isZip) return "不支持的格式"
        return runCatching {
            ZipFile(file).use { z ->
                val names = z.entries().toList().map { it.name }
                when {
                    names.any { it.startsWith("word/") } && "word/document.xml" !in names ->
                        "不是有效的 Word 文档（缺少 word/document.xml）"
                    names.any { it.startsWith("ppt/") } -> "不是有效的 PowerPoint 文档（缺少幻灯片内容）"
                    names.any { it.startsWith("xl/") } -> "不是有效的 Excel 文档（缺少工作表）"
                    names.isEmpty() -> "压缩包是空的"
                    else -> "压缩包内没有可读取的文档"
                }
            }
        }.getOrElse { "压缩包已损坏或无法读取" }
    }

    // ---------- TXT ----------

    private val CHAPTER_RE = Regex(
        "^\\s*(第[0-9０-９零一二三四五六七八九十百千两]+[章节卷回部篇集]|序章|序言|楔子|引子|后记|尾声|终章|附录|番外|Chapter\\s+\\d+).{0,40}$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 单块字符上限。没有换行的长文本（网页另存、程序导出）如果整段塞进一个块，
     * 阅读器就无法分页——一页会撑成几万字。按句末标点切开，切不动再硬切。
     */
    private const val MAX_BLOCK_CHARS = 800
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', '\n')

    private fun splitLongParagraph(text: String): List<String> {
        if (text.length <= MAX_BLOCK_CHARS) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + MAX_BLOCK_CHARS, text.length)
            if (hardEnd == text.length) {
                out.add(text.substring(start))
                break
            }
            // 从上限往回找一个句末标点，找不到就硬切
            var cut = -1
            for (i in hardEnd downTo (start + MAX_BLOCK_CHARS / 3)) {
                if (text[i - 1] in SENTENCE_END) { cut = i; break }
            }
            val end = if (cut > start) cut else hardEnd
            out.add(text.substring(start, end).trim())
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun parseTxt(file: File): ParsedDoc {
        val s = readTextFile(file)
        val blocks = mutableListOf<Block>()
        outer@ for (para in s.lineSequence()) {
            val t = para.trim()
            if (t.isEmpty()) continue
            // 章节标题自动识别（第X章/卷、序章、楔子等）
            if (CHAPTER_RE.matches(t) && t.length <= 50) {
                blocks.add(Block(Block.HEADING, t))
                if (blocks.size >= MAX_BLOCKS) break@outer
                continue
            }
            for (piece in splitLongParagraph(t)) {
                blocks.add(Block(Block.TEXT, piece))
                if (blocks.size >= MAX_BLOCKS) break@outer
            }
        }
        if (blocks.isEmpty()) throw ParseException("文本文件内没有可读取的内容")
        return build("txt", blocks)
    }

    // ---------- CSV / TSV ----------

    private fun parseDelimited(file: File): ParsedDoc {
        val text = readTextFile(file)
        val delimiter = if (file.name.substringAfterLast('.', "").equals("tsv", true)) '\t' else ','
        val blocks = mutableListOf<Block>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            // 用全角竖线展示，避免和正文里的半角竖线混淆
            blocks.add(Block(Block.TEXT, t.split(delimiter).joinToString(" ｜ ") { it.trim() }))
            if (blocks.size >= MAX_BLOCKS) break
        }
        if (blocks.isEmpty()) throw ParseException("表格文件内没有可读取的内容")
        return build("csv", blocks)
    }

    // ---------- HTML ----------

    private fun parseHtml(file: File): ParsedDoc {
        val raw = readTextFile(file)
        val blocks = mutableListOf<Block>()
        // 真实网页大多不是合法 XML（<br>、<meta> 不闭合），先按 XHTML 严格解析，
        // 失败再退回容错提取——否则「另存为网页」的书一律打不开。
        val strict = runCatching { extractXhtml(raw, blocks) }
        if (strict.isFailure || blocks.isEmpty()) {
            blocks.clear()
            extractHtmlTolerant(raw, blocks)
        }
        if (blocks.isEmpty()) throw ParseException("网页文件内没有可读取的内容")
        return build("html", blocks)
    }

    private val SCRIPT_STYLE_RE = Regex("(?is)<(script|style|noscript)\\b.*?</\\1\\s*>")
    private val BLOCK_TAG_RE = Regex("(?i)</(p|div|li|h[1-6]|tr|blockquote|section|article)\\s*>|<br\\s*/?>")
    private val HEADING_TAG_RE = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1\\s*>")
    private val TAG_RE = Regex("(?s)<[^>]*>")

    /** 容错提取：先抠掉脚本样式，再把块级标签当断点，最后去标签解实体。 */
    private fun extractHtmlTolerant(html: String, out: MutableList<Block>) {
        val cleaned = SCRIPT_STYLE_RE.replace(html, "\n")
        // 标题单独抽出来，保留层级信息
        val headingSpans = mutableListOf<Pair<Int, Int>>()
        HEADING_TAG_RE.findAll(cleaned).forEach { m ->
            val text = decodeEntities(TAG_RE.replace(m.groupValues[2], " ")).trim()
            if (text.isNotEmpty() && out.size < MAX_BLOCKS) {
                out.add(Block(Block.HEADING, text))
                headingSpans.add(m.range.first to m.range.last)
            }
        }
        val withoutHeadings = StringBuilder(cleaned)
        // 从后往前替换，避免位移
        headingSpans.sortedByDescending { it.first }.forEach { (s, e) ->
            withoutHeadings.replace(s, e, "\n")
        }
        val broken = BLOCK_TAG_RE.replace(withoutHeadings, "\n")
        for (chunk in broken.split('\n')) {
            val text = decodeEntities(TAG_RE.replace(chunk, " ")).trim()
            if (text.isEmpty()) continue
            for (piece in splitLongParagraph(text)) {
                out.add(Block(Block.TEXT, piece))
                if (out.size >= MAX_BLOCKS) return
            }
        }
    }

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&hellip;", "…")
        .replace("&amp;", "&")

    // ---------- FB2 ----------

    private fun parseFb2(file: File): ParsedDoc {
        val xml = readTextFile(file)
        val blocks = mutableListOf<Block>()
        var inTitle = 0
        var inBinary = 0
        var buf = StringBuilder()
        xmlPull(xml) { xp ->
            loop@ while (true) {
                if (blocks.size >= MAX_BLOCKS) break@loop
                when (xp.next()) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> when (xp.name) {
                        "title" -> { inTitle++; buf = StringBuilder() }
                        "binary" -> inBinary++          // base64 封面等，跳过
                        "p" -> buf = StringBuilder()
                    }
                    XmlPullParser.TEXT -> if (inBinary == 0 && inTitle >= 0) buf.append(xp.text)
                    XmlPullParser.END_TAG -> when (xp.name) {
                        "binary" -> if (inBinary > 0) inBinary--
                        "title" -> {
                            if (inTitle > 0) inTitle--
                            val t = buf.toString().trim()
                            buf = StringBuilder()
                            if (t.isNotEmpty()) blocks.add(Block(Block.HEADING, t))
                        }
                        "p" -> {
                            val t = buf.toString().trim()
                            buf = StringBuilder()
                            if (t.isEmpty() || inBinary > 0) continue@loop
                            blocks.add(Block(if (inTitle > 0) Block.HEADING else Block.TEXT, t))
                        }
                    }
                }
            }
        }
        if (blocks.isEmpty()) throw ParseException("FB2 文件内没有可读取的内容")
        return build("fb2", blocks)
    }

    // ---------- RTF ----------

    /**
     * RTF 文本提取。RTF 是带控制字的纯文本格式：
     *  - `\\par` 段落、`\\tab` 制表、`\\line` 换行
     *  - `\\uNNNN?` Unicode 码位（带一个可跳过的替换字符）
     *  - `\\'hh` 当前代码页的十六进制字节（中文 RTF 常见 GBK 序列，需成对合并）
     *  - `{\\*...}` 目标组、字体表/颜色表/样式表等非正文内容要跳过
     */
    private fun parseRtf(file: File): ParsedDoc {
        val raw = readTextFile(file)
        val text = rtfToPlainText(raw)
        val blocks = mutableListOf<Block>()
        for (para in text.split('\n')) {
            val t = para.trim()
            if (t.isEmpty()) continue
            if (CHAPTER_RE.matches(t) && t.length <= 50) {
                blocks.add(Block(Block.HEADING, t))
            } else {
                for (piece in splitLongParagraph(t)) blocks.add(Block(Block.TEXT, piece))
            }
            if (blocks.size >= MAX_BLOCKS) break
        }
        if (blocks.isEmpty()) throw ParseException("RTF 文件内没有可读取的内容")
        return build("rtf", blocks)
    }

    private val RTF_SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "datastore",
        "themedata", "latentstyles", "listtable", "listoverridetable", "rsidtbl", "generator"
    )

    internal fun rtfToPlainText(raw: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        // 记录每层是否处于「要跳过」的组内
        val skipStack = ArrayDeque<Boolean>()
        var skipping = false
        var pendingHex = StringBuilder()

        fun flushHex() {
            if (pendingHex.isEmpty()) return
            val bytes = ByteArray(pendingHex.length / 2) { idx ->
                pendingHex.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
            }
            pendingHex = StringBuilder()
            // 中文 RTF 的 \'hh 是 GBK 双字节，直接按 GBK 解码
            val decoded = runCatching { String(bytes, charset("GBK")) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
            if (!skipping) out.append(decoded)
        }

        while (i < raw.length) {
            val c = raw[i]
            when (c) {
                '{' -> {
                    flushHex()
                    depth++
                    skipStack.addLast(skipping)
                    // {\* 开头的是目标组，一律跳过
                    if (i + 2 < raw.length && raw[i + 1] == '\\' && raw[i + 2] == '*') {
                        skipping = true
                    }
                    i++
                }
                '}' -> {
                    flushHex()
                    if (skipStack.isNotEmpty()) skipping = skipStack.removeLast()
                    if (depth > 0) depth--
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= raw.length) break
                    val next = raw[i]
                    if (!next.isLetter()) {
                        // 控制符号：\\ { } 以及 \'hh 十六进制字节
                        if (next == '\'') {
                            // 关键：连续的 \'hh 必须累积成完整字节序列再解码。
                            // 逐个字节按 GBK 解会得到一串替换字符——中文 RTF 几乎全靠
                            // 这种成对字节表示，逐字节解码等于中文全废。
                            if (i + 2 < raw.length) {
                                pendingHex.append(raw.substring(i + 1, i + 3))
                                i += 3
                            } else {
                                i++
                            }
                            continue
                        }
                        flushHex()
                        when (next) {
                            '\\', '{', '}' -> if (!skipping) out.append(next)
                            '~' -> if (!skipping) out.append(' ')
                            else -> Unit
                        }
                        i++
                        continue
                    }
                    // 控制字之前先把累积的十六进制字节冲出去
                    flushHex()
                    val wordStart = i
                    while (i < raw.length && raw[i].isLetter()) i++
                    val word = raw.substring(wordStart, i)
                    // 可选数字参数（可为负）
                    var sign = 1
                    if (i < raw.length && raw[i] == '-') { sign = -1; i++ }
                    val numStart = i
                    while (i < raw.length && raw[i].isDigit()) i++
                    val num = if (i > numStart) raw.substring(numStart, i).toIntOrNull()?.times(sign) else null
                    // 控制字与后续文本之间用一个空格分隔，要吃掉
                    if (i < raw.length && raw[i] == ' ') i++

                    when (word) {
                        "par", "line", "sect" -> if (!skipping) out.append('\n')
                        "tab" -> if (!skipping) out.append('\t')
                        "u" -> {
                            val raw0 = num ?: 0
                            // RTF 的 \u 用有符号 16 位表示码位：负数需加回 65536
                            val code = if (raw0 < 0) raw0 + 0x10000 else raw0
                            if (!skipping && code in 1..0x10FFFF) {
                                if (code > 0xFFFF) {
                                    out.appendCodePoint(code)
                                } else {
                                    out.append(code.toChar())
                                }
                            }
                            // \uN 后面紧跟一个可跳过的替换字符
                            if (i < raw.length && raw[i] == '?') i++
                        }
                        "ansicpg", "deff", "fs", "f", "cf", "highlight", "b", "i", "ul", "strike",
                        "super", "sub", "scaps", "caps", "v", "expnd", "expndtw", "kerning",
                        "q", "sl", "slmult", "sa", "sb", "li", "fi", "ri", "plain", "pard", "s",
                        "lang", "langfe", "loch", "hich", "dbch", "chcbpat", "outl", "cs", "nosupersub" -> Unit
                        in RTF_SKIP_DESTINATIONS -> skipping = true
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    flushHex()
                    if (!skipping) out.append(c)
                    i++
                }
            }
        }
        flushHex()
        return out.toString()
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
            // container 可能列出多个 rootfile（常见于同时打包 EPUB2/3 的书），
            // 取第一个「实际存在」的，而不是第一个声明的——否则会在缺失项上直接失败。
            val opfCandidates = allTagAttrs(containerXml, "rootfile", "full-path")
                .map { resolvePath("", it) }
            val opfPath = opfCandidates.firstOrNull { z.getEntry(it) != null }
                ?: throw ParseException(
                    if (opfCandidates.isEmpty()) "EPUB container.xml 格式异常"
                    else "EPUB 声明的 OPF 文件不存在：${opfCandidates.first()}"
                )
            val opfXml = readEntry(z, opfPath)
                ?: throw ParseException("EPUB 缺少 OPF: $opfPath")
            rejectProtectedEpub(z, opfPath)

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

            // spine: 按顺序的 idref。linear="no" 表示非顺序内容（封面页、版权页等），
            // 不应混进正文流——否则正文开头会先出现一句封面占位。
            val spine = mutableListOf<String>()
            xmlPull(opfXml) { xp ->
                while (true) {
                    val ev = xp.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.START_TAG && xp.name == "itemref") {
                        val linear = attrOf(xp, "linear")
                        if (linear != null && linear.equals("no", ignoreCase = true)) continue
                        attrOf(xp, "idref")?.let { spine.add(it) }
                    }
                }
            }
            if (spine.isEmpty()) {
                throw ParseException("EPUB 的 spine 为空，无法确定阅读顺序")
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
            // EPUB3 的导航文档（properties="nav"）常常也在 spine 里。它不是正文：
            // 把它的 <h2> 和 <li> 当内容会往目录最前面塞一条书名，并且让后面所有
            // 段落的编号整体后移——AI 引用 [PARAGRAPH:n] 因此指到错误位置。
            val navHrefs = manifest.filter { it.props.split(' ').contains("nav") }
                .map { resolvePath(baseDir, it.href) }
                .toSet()
            val blocks = mutableListOf<Block>()
            for (idref in spine) {
                val href = hrefMap[idref] ?: continue
                val entryPath = resolvePath(baseDir, href)
                if (entryPath in navHrefs) continue
                val lower = entryPath.lowercase()
                if (!lower.endsWith(".xhtml") && !lower.endsWith(".html") && !lower.endsWith(".htm")) continue
                val html = readEntryDecoded(z, entryPath) ?: continue
                extractXhtmlSafe(html, blocks)
                if (blocks.size >= MAX_BLOCKS) break
            }

            if (blocks.isEmpty()) {
                // 纯图片的固定版式 EPUB（漫画、扫描书）：spine 里全是包着 <img> 的页面，
                // 没有任何正文文字。直接抛「未提取到文本」会让用户以为文件坏了。
                val pages = imageEntriesInSpine(z, manifest.map { it.href }, spine, hrefMap, baseDir)
                if (pages.isNotEmpty()) {
                    val summary = "图片型书籍：共 ${pages.size} 页（无可提取的文字）"
                    return ParsedDoc("epub", listOf(Block(Block.TEXT, summary)), summary, coverBytes, pages)
                }
                throw ParseException("EPUB 内未提取到文本")
            }
            return ParsedDoc("epub", blocks, buildText(blocks), coverBytes)
        }
    }

    /** spine 中引用的图片资源（按 spine 顺序），用于识别纯图片书。 */
    private fun imageEntriesInSpine(
        z: ZipFile,
        manifestHrefs: List<String>,
        spine: List<String>,
        hrefMap: Map<String, String>,
        baseDir: String,
    ): List<String> {
        val direct = spine.mapNotNull { hrefMap[it] }
            .map { resolvePath(baseDir, it) }
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_FORMATS }
        if (direct.isNotEmpty()) return direct
        // 页面是 XHTML 外壳、图片在 manifest 里：取 manifest 中的图片资源
        return manifestHrefs
            .map { resolvePath(baseDir, it) }
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_FORMATS }
            .filter { z.getEntry(it) != null }
            .sortedWith(compareBy({ it.substringBeforeLast('/') }, { naturalKey(it) }))
    }

    /**
     * 读取 XHTML 条目并按内容判定编码。
     *
     * EPUB 规范要求 UTF-8，但现实里有大量 GBK 编码的中文电子书；统一按 UTF-8 读
     * 会得到满屏乱码。这里复用文本解码的判定链，并去掉 BOM 残留。
     */
    private fun readEntryDecoded(z: ZipFile, path: String): String? {
        val entry = z.getEntry(path) ?: return null
        val bytes = z.getInputStream(entry).use { readLimited(it, MAX_XML_ENTRY_BYTES, "文档 XML") }
        val declared = xmlDeclaredCharset(bytes)
        val text = when {
            declared != null -> runCatching { String(bytes, declared) }.getOrNull()
            else -> null
        } ?: decodeText(bytes)
        return text.removePrefix("\uFEFF")
    }

    /** 从 `<?xml ... encoding="..."?>` 里读出声明编码；没有声明或名字不认识时返回 null。 */
    private fun xmlDeclaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        if (!head.trimStart().startsWith("<?xml")) return null
        val name = Regex("encoding\\s*=\\s*[\"']([^\"]+)[\"']", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return runCatching { charset(name) }.getOrNull()
    }

    /** 提取 XHTML：先做规范化再严格解析，仍失败则退回容错提取。 */
    private fun extractXhtmlSafe(html: String, out: MutableList<Block>) {
        val normalized = normalizeXhtml(html)
        val strict = runCatching { extractXhtml(normalized, out) }
        if (strict.isSuccess && out.isNotEmpty()) return
        val before = out.size
        out.subList(before, out.size).clear()
        extractHtmlTolerant(normalized, out)
    }

    /**
     * 把真实世界的 XHTML 规范化为可被严格 XML 解析器接受的形态。
     *
     * 两处最常见的「非法」：
     *  1. 未声明的 HTML 实体（&nbsp; &mdash; &hellip; …）。它们在 XML 里必须由 DTD 声明，
     *     而电子书里通常没有声明，严格解析直接抛 unresolved entity——整本书打不开。
     *  2. 空元素不闭合（<br> <hr> <img …>）。HTML 允许，XML 不允许。
     */
    internal fun normalizeXhtml(html: String): String {
        var text = html
        // 已知实体 → 直接替换成字符，避免依赖 DTD
        for ((entity, char) in HTML_ENTITIES) {
            if (text.contains(entity)) text = text.replace(entity, char.toString())
        }
        // 空元素自闭合
        text = VOID_ELEMENT_RE.replace(text) { m ->
            val tag = m.groupValues[1]
            val attrs = m.groupValues[2].trimEnd()
            if (attrs.endsWith("/")) m.value else "<$tag$attrs/>"
        }
        return text
    }

    private val HTML_ENTITIES = listOf(
        "&nbsp;" to '\u00A0', "&mdash;" to '\u2014', "&ndash;" to '\u2013',
        "&hellip;" to '\u2026', "&copy;" to '\u00A9', "&reg;" to '\u00AE',
        "&trade;" to '\u2122', "&ldquo;" to '\u201C', "&rdquo;" to '\u201D',
        "&lsquo;" to '\u2018', "&rsquo;" to '\u2019', "&middot;" to '\u00B7',
        "&bull;" to '\u2022', "&deg;" to '\u00B0', "&times;" to '\u00D7',
        "&laquo;" to '\u00AB', "&raquo;" to '\u00BB', "&sect;" to '\u00A7',
        "&para;" to '\u00B6', "&dagger;" to '\u2020', "&permil;" to '\u2030',
        "&prime;" to '\u2032', "&Prime;" to '\u2033', "&ensp;" to '\u2002',
        "&emsp;" to '\u2003', "&thinsp;" to '\u2009', "&shy;" to '\u00AD',
    )

    private val VOID_ELEMENT_RE = Regex(
        "(?i)<(br|hr|img|meta|link|input|area|base|col|embed|source|track|wbr)((?:\\s[^<>]*?)?)\\s*/?>"
    )

    /**
     * 受 DRM 保护的 EPUB 给出明确提示。
     *
     * 注意区分：EPUB 允许对字体做「混淆」（encryption.xml 里指向 .otf/.ttf），
     * 那是完全正常的，不影响阅读；只有加密对象落在正文文档上才算真的受保护。
     * 不做这个区分会把大量正常电子书误判成加密书。
     */
    private fun rejectProtectedEpub(z: ZipFile, opfPath: String) {
        val xml = readEntry(z, "META-INF/encryption.xml") ?: return
        val encrypted = allTagAttrs(xml, "CipherReference", "URI")
            .filter { it.isNotBlank() }
        if (encrypted.isEmpty()) return
        val contentEncrypted = encrypted.any { uri ->
            val lower = uri.lowercase()
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
                lower.endsWith(".opf") || lower.endsWith(".ncx")
        }
        if (contentEncrypted) {
            throw ParseException("这本 EPUB 受 DRM 保护（正文已加密），需要先去 DRM 才能阅读")
        }
    }

    /** 收集某个标签的全部指定属性值（用于 container 的多个 rootfile、encryption 的多个 URI）。 */
    private fun allTagAttrs(xml: String, tag: String, attr: String): List<String> {
        val out = mutableListOf<String>()
        xmlPull(xml) { xp ->
            loop@ while (true) {
                when (xp.next()) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> if (xp.name == tag) {
                        attrOf(xp, attr)?.let { out.add(it) }
                    }
                }
            }
        }
        return out
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

    // ---------- ODT ----------

    /**
     * ODT/OTT：OpenDocument 文本。正文在 content.xml，标题是 `text:h`（带
     * `text:outline-level`），段落是 `text:p`，`text:s`/`text:tab`/`text:line-break`
     * 分别是空格、制表与换行。
     */
    private fun parseOdt(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
            val content = readEntry(z, "content.xml")
                ?: throw ParseException("不是有效的 ODT 文档（缺少 content.xml）")
            val blocks = mutableListOf<Block>()
            var buf = StringBuilder()
            var heading = false
            xmlPull(content) { xp ->
                loop@ while (true) {
                    if (blocks.size >= MAX_BLOCKS) break@loop
                    when (xp.next()) {
                        XmlPullParser.END_DOCUMENT -> break@loop
                        XmlPullParser.START_TAG -> when (xp.name) {
                            "h" -> { heading = true; buf = StringBuilder() }
                            "p" -> { heading = false; buf = StringBuilder() }
                            "s" -> buf.append(' ')
                            "tab" -> buf.append('\t')
                            "line-break" -> buf.append('\n')
                        }
                        XmlPullParser.TEXT -> buf.append(xp.text)
                        XmlPullParser.END_TAG -> when (xp.name) {
                            "h", "p" -> {
                                val t = buf.toString().trim()
                                buf = StringBuilder()
                                if (t.isEmpty()) continue@loop
                                for (piece in if (heading) listOf(t) else splitLongParagraph(t)) {
                                    blocks.add(Block(if (heading) Block.HEADING else Block.TEXT, piece))
                                }
                                heading = false
                            }
                        }
                    }
                }
            }
            if (blocks.isEmpty()) throw ParseException("ODT 文档内没有可读取的内容")
            return build("odt", blocks)
        }
    }

    // ---------- XLSX ----------

    /**
     * XLSX：单元格文本分布在 sharedStrings.xml 与各工作表的 XML 里。
     * 文本型单元格用 t="s" 引用共享字符串下标，其余直接取 v；inlineStr 内联文本。
     */
    private fun parseXlsx(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
            if (z.getEntry("xl/workbook.xml") == null) {
                throw ParseException("不是有效的 Excel 文档（缺少 xl/workbook.xml）")
            }
            val shared = parseSharedStrings(z)
            val sheetNames = parseSheetNames(z)
            val sheetEntries = z.entries().toList().map { it.name }
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: 0 }
            if (sheetEntries.isEmpty()) throw ParseException("Excel 文档内没有工作表")

            val blocks = mutableListOf<Block>()
            sheetEntries.forEachIndexed { index, entry ->
                if (blocks.size >= MAX_BLOCKS) return@forEachIndexed
                val name = sheetNames.getOrNull(index)
                if (!name.isNullOrBlank()) blocks.add(Block(Block.HEADING, name))
                val xml = readEntry(z, entry) ?: return@forEachIndexed
                var row = StringBuilder()
                var cellType = ""
                var value = StringBuilder()
                var inValue = false
                xmlPull(xml) { xp ->
                    loop@ while (true) {
                        if (blocks.size >= MAX_BLOCKS) break@loop
                        when (xp.next()) {
                            XmlPullParser.END_DOCUMENT -> break@loop
                            XmlPullParser.START_TAG -> when (xp.name) {
                                "row" -> row = StringBuilder()
                                "c" -> {
                                    cellType = attrOf(xp, "t").orEmpty()
                                    value = StringBuilder()
                                }
                                "v", "t" -> inValue = true
                            }
                            XmlPullParser.TEXT -> if (inValue) value.append(xp.text)
                            XmlPullParser.END_TAG -> when (xp.name) {
                                "v", "t" -> inValue = false
                                "c" -> {
                                    val raw = value.toString()
                                    val text = if (cellType == "s") {
                                        shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                                    } else raw
                                    if (text.isNotBlank()) {
                                        if (row.isNotEmpty()) row.append(" ｜ ")
                                        row.append(text.trim())
                                    }
                                }
                                "row" -> {
                                    val t = row.toString().trim()
                                    if (t.isNotEmpty()) blocks.add(Block(Block.TEXT, t))
                                }
                            }
                        }
                    }
                }
            }
            if (blocks.isEmpty()) throw ParseException("Excel 文档内没有可读取的文本")
            return build("xlsx", blocks)
        }
    }

    private fun parseSharedStrings(z: ZipFile): List<String> {
        val xml = readEntry(z, "xl/sharedStrings.xml") ?: return emptyList()
        val out = mutableListOf<String>()
        var buf = StringBuilder()
        var inItem = false
        xmlPull(xml) { xp ->
            loop@ while (true) {
                when (xp.next()) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> if (xp.name == "si") { inItem = true; buf = StringBuilder() }
                    XmlPullParser.TEXT -> if (inItem) buf.append(xp.text)
                    XmlPullParser.END_TAG -> if (xp.name == "si") {
                        inItem = false
                        out.add(buf.toString())
                    }
                }
            }
        }
        return out
    }

    /** 工作表显示名（含中文名）来自 workbook.xml，按声明顺序返回。 */
    private fun parseSheetNames(z: ZipFile): List<String> {
        val xml = readEntry(z, "xl/workbook.xml") ?: return emptyList()
        val names = mutableListOf<String>()
        xmlPull(xml) { xp ->
            loop@ while (true) {
                when (xp.next()) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> if (xp.name == "sheet") {
                        attrOf(xp, "name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
            }
        }
        return names
    }

    // ---------- 漫画 / 图片集（CBZ） ----------

    /**
     * CBZ（图片压缩包）：按文件名排序取出图片页。
     * 页清单通过 [ParsedDoc.pageEntries] 交给阅读器，正文只放一行摘要。
     */
    private fun parseImageArchive(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
            val pages = z.entries().toList()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_FORMATS }
                .sortedWith(compareBy({ it.substringBeforeLast('/') }, { naturalKey(it) }))
            if (pages.isEmpty()) throw ParseException("压缩包内没有图片")
            val blocks = listOf(Block(Block.TEXT, "图片集：共 ${pages.size} 页"))
            return ParsedDoc("cbz", blocks, "图片集：共 ${pages.size} 页", null, pages)
        }
    }

    /** 让 page_2 排在 page_10 前面。 */
    private fun naturalKey(name: String): String =
        Regex("\\d+").replace(name) { m -> m.value.padStart(8, '0') }

    // ---------- 压缩包内单文档 ----------

    /**
     * 压缩包里只装着一本书时自动下钻。
     * 网上分享的电子书常先打成 zip，用户不该因此看到「不支持的格式」。
     */
    private fun parseWrappedArchive(file: File): ParsedDoc {
        ZipFile(file).use { z ->
            validateArchive(z)
            val candidate = z.entries().toList()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { it.substringAfterLast('.', "").lowercase() in EXT_MAP }
                .filter { it.substringAfterLast('.', "").lowercase() != "zip" }
                .firstOrNull()
                ?: throw ParseException("压缩包内没有可读取的文档")
            val bytes = readEntryBytes(z, candidate)
                ?: throw ParseException("无法读取压缩包内的文档")
            val temp = File.createTempFile("wrapped-", "-" + candidate.substringAfterLast('/'))
            try {
                temp.writeBytes(bytes)
                val inner = parseText(temp)
                // 保留外层是压缩包这一事实，便于界面提示来源
                return inner
            } finally {
                temp.delete()
            }
        }
    }

    // ---------- 公共工具 ----------

    private fun build(format: String, blocks: List<Block>): ParsedDoc =
        ParsedDoc(format, blocks, buildText(blocks))

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
        return decodeText(bytes)
    }

    /**
     * 文本解码：BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）；无 BOM 时先做严格 UTF-8，
     * 再判断 UTF-16，最后回退 GBK（兼容老中文文本文件）。
     *
     * 顺序有意为之：严格 UTF-8 能成功解码时几乎不可能是别的编码，先判它最稳；
     * 不成立时才需要区分「无 BOM 的 UTF-16」和「GBK」。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strictUtf8(bytes)?.let { return it }
        detectBomlessUtf16(bytes)?.let { return String(bytes, it) }
        return try {
            String(bytes, charset("GBK"))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    /** 严格 UTF-8：遇到非法字节返回 null，而不是用 U+FFFD 静默替换后再去猜编码。 */
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    /**
     * 无 BOM 的 UTF-16 启发式：ASCII 字符在 UTF-16LE 中表现为奇数位大量 0x00，UTF-16BE 则相反。
     * 只统计前 4 KB；要求某一位序的 0x00 明显占优，避免把普通二进制误判成文本。
     * 纯中文且无 ASCII 的无 BOM UTF-16 无法可靠判别，仍按 GBK/UTF-8 处理。
     */
    /**
     * 无 BOM 的 UTF-16 判定。
     *
     * 只用 NUL 字节间隔是不够的：中文 UTF-16 的每个码位两个字节都非零
     * （汉字落在 U+4E00–U+9FFF），只有 ASCII 才产生 NUL，所以纯中文的 UTF-16 文件
     * 几乎看不到 NUL，旧判据会把整份文件当成二进制而拒绝导入。
     *
     * 因此补一条针对汉字的判据：UTF-16LE 下第 i+1 字节是高位，中文文本里它应
     * 密集落在 0x4E–0x9F；UTF-16BE 则看第 i 字节。阈值取三分之二，
     * 以免把 GBK 字节流（其高位字节分布更散）误判成 UTF-16。
     */
    private fun detectBomlessUtf16(bytes: ByteArray): Charset? {
        val limit = minOf(bytes.size, 4096)
        if (limit < 16) return null
        var evenZero = 0
        var oddZero = 0
        var leCjkHigh = 0
        var beCjkHigh = 0
        var pairs = 0
        var i = 0
        while (i + 1 < limit) {
            val low = bytes[i].toInt() and 0xff
            val high = bytes[i + 1].toInt() and 0xff
            if (low == 0) evenZero++
            if (high == 0) oddZero++
            if (high in 0x4E..0x9F) leCjkHigh++
            if (low in 0x4E..0x9F) beCjkHigh++
            pairs++
            i += 2
        }
        val minZeros = limit / 8
        return when {
            oddZero >= minZeros && oddZero > evenZero * 4 -> Charsets.UTF_16LE
            evenZero >= minZeros && evenZero > oddZero * 4 -> Charsets.UTF_16BE
            pairs >= 8 && leCjkHigh * 3 >= pairs * 2 -> Charsets.UTF_16LE
            pairs >= 8 && beCjkHigh * 3 >= pairs * 2 -> Charsets.UTF_16BE
            else -> null
        }
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
        // UTF-16 文本含 NUL 字节，先按 BOM/间隔特征放行，交给 decodeText 正确解码
        if (hasUtf16Bom(bytes) || detectBomlessUtf16(bytes) != null) return true
        var controls = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value == 0) return false
            if (value < 0x20 && value !in setOf(0x09, 0x0a, 0x0d, 0x0c)) controls++
        }
        return controls * 20 <= bytes.size
    }

    private fun hasUtf16Bom(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )

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
        var skipDepth = 0
        // <head> 里全是元数据（title/meta/link），不是正文。
        // 之前把 <title> 也当成标题块，于是每个章节在目录里出现两次
        // （一次来自 <title>、一次来自 <h1>），章节计数也被抬高一位。
        var headDepth = 0

        xmlPull(html) { xp ->
            loop@ while (true) {
                if (out.size >= MAX_BLOCKS) break@loop
                val ev = xp.next()
                when (ev) {
                    XmlPullParser.END_DOCUMENT -> break@loop
                    XmlPullParser.START_TAG -> {
                        val n = xp.name.lowercase()
                        if (n in SKIP) skipDepth++
                        if (n == "head") headDepth++
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth == 0 && headDepth == 0) buf.append(xp.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val n = xp.name.lowercase()
                        if (n == "head" && headDepth > 0) {
                            headDepth--
                            // 丢弃 head 期间累积的空白/元数据，避免漏进下一个块
                            buf = StringBuilder()
                            continue@loop
                        }
                        if (n in SKIP && skipDepth > 0) { skipDepth--; continue@loop }
                        if (skipDepth > 0 || headDepth > 0) continue@loop
                        if (n in HEADINGS || n in PARA) {
                            val t = buf.toString().trim()
                            buf = StringBuilder()
                            if (t.isEmpty()) continue@loop
                            // h1-h6 记为标题块；p/li/blockquote/裸 div 都收进正文
                            val type = if (n in HEADINGS) Block.HEADING else Block.TEXT
                            out.add(Block(type, t))
                        }
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
