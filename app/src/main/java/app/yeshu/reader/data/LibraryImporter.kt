package app.yeshu.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.yeshu.reader.CoverStore
import app.yeshu.reader.Db
import app.yeshu.reader.parse.DocParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ImportResult(val id: Long, val title: String, val format: String, val error: String? = null)

object LibraryImporter {
    private val supportedFormats = setOf("pdf", "epub", "docx", "pptx", "txt", "md", "jpg", "png")
    /**
     * MIME → 格式。与 `DocParser` 支持的格式保持一致。
     *
     * 各内容提供者对冷门格式（fb2 / odt / cbz / wps）经常报出任意 MIME，甚至报
     * application/octet-stream，所以这里除了精确映射，还保留 octet-stream 这一项：
     * 选择器允许选中，真正的格式由文件内容判定，判不出来时给出可读原因。
     * 否则用户会在选择器里看到文件是灰的，根本选不中。
     */
    private val mimeFormats = mapOf(
        "application/pdf" to "pdf",
        "application/x-pdf" to "pdf",
        "application/epub+zip" to "epub",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-word.document.macroenabled.12" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.text-template" to "odt",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-excel.sheet.macroenabled.12" to "xlsx",
        "text/html" to "html",
        "application/xhtml+xml" to "html",
        "application/x-fictionbook+xml" to "fb2",
        "application/fb2" to "fb2",
        "application/rtf" to "rtf",
        "text/rtf" to "rtf",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/csv" to "csv",
        "text/tab-separated-values" to "csv",
        "application/vnd.comicbook+zip" to "cbz",
        "application/x-cbz" to "cbz",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/bmp" to "bmp",
        "application/octet-stream" to ""
    )

    /** 与 [mimeFormats] 保持一致，避免选择器把应用已支持（如 DOCM/PPSX/text/x-markdown）的文件置灰。 */
    val mimeTypes: Array<String> = mimeFormats.keys.toTypedArray()

    private const val MAX_CONSECUTIVE_ZERO_READS = 64
    private const val TEMP_FILE_PREFIX = ".import_"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    /**
     * 导入临时文件的保留窗口。超过这个时长仍然存在的 `.import_*.tmp` 只可能来自被系统杀死
     * 的上一次导入，可以安全清理；窗口内的文件视为可能仍在写入，扫描时不动。
     */
    private const val STALE_TEMP_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    @Synchronized
    fun import(context: Context, uri: Uri): ImportResult {
        val mimeType = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()?.substringBefore(';')?.trim()?.lowercase()
        val mimeFormat = formatForMime(mimeType)
        val generatedExtension = extensionFor(mimeFormat.ifBlank { "txt" })
        val displayName = queryName(context, uri)?.takeIf { it.isNotBlank() }
            ?: "document_${System.currentTimeMillis()}.$generatedExtension"
        val safeName = sanitizeName(displayName)
        val nameFormat = DocParser.detect(safeName)
        val hintedFormat = nameFormat.ifBlank { mimeFormat }
        val title = safeName.substringBeforeLast('.', safeName).ifBlank { "未命名文档" }
        val temporary = File(context.filesDir, "$TEMP_FILE_PREFIX${UUID.randomUUID()}$TEMP_FILE_SUFFIX")
        var destination: File? = null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var zeroReads = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            // 部分 provider 会返回 0 而不是阻塞；限制连续空读，避免导入任务永久空转。
                            if (++zeroReads >= MAX_CONSECUTIVE_ZERO_READS) error("读取文件失败：数据源无响应")
                            continue
                        }
                        zeroReads = 0
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
            } ?: error("无法读取所选文件")
            require(size > 0) { "文件为空" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val format = DocParser.detectContent(temporary, hintedFormat)
            require(format in supportedFormats) {
                if (nameFormat.isNotBlank()) "文件内容与 .$nameFormat 格式不匹配或已损坏"
                else "无法识别文件格式，仅支持 PDF、EPUB、DOCX、PPTX、TXT、Markdown、JPG 和 PNG"
            }
            val db = Db(context)
            val match = db.findImportMatch(hash)
            if (match != null) {
                val record = match.book
                val message = if (match.fromRecycleBin) {
                    // 回收站里已有同一份文档：优先恢复原记录（进度、笔记与 AI 结果都挂在原 id 上），
                    // 而不是再插一条记录并在磁盘上多存一份同样的文件。
                    val source = File(context.filesDir, record.fileName)
                    if (record.fileName.isNotBlank() && !source.isFile) {
                        if (!temporary.renameTo(source)) temporary.copyTo(source, overwrite = false)
                    }
                    db.restoreDeletedBook(record.id)
                    "该文件此前已删除，已从回收站恢复"
                } else {
                    "该文件已在书架中"
                }
                return ImportResult(record.id, record.title, record.format, message)
            }
            val fileBase = title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(80)
                .ifBlank { "document" }
            destination = File(
                context.filesDir,
                "${System.currentTimeMillis()}_${hash.take(12)}_$fileBase.${extensionFor(format)}"
            )
            val finalFile = destination ?: error("无法创建目标文件")
            if (!temporary.renameTo(finalFile)) {
                temporary.copyTo(finalFile, overwrite = false)
                check(temporary.delete()) { "无法清理导入临时文件" }
            }
            val id = db.insertBook(title, finalFile.name, format, size, hash)
            require(id > 0) { "写入书库失败" }
            CoverStore.generateAsync(context, id, finalFile, format)
            ImportResult(id, title, format)
        } catch (t: Throwable) {
            destination?.delete()
            ImportResult(-1, title, hintedFormat, t.message ?: "导入失败")
        } finally {
            // 成功时临时文件已被 rename 成正式文件，这里的 delete 是空操作；失败、Worker 被停止
            // 或进程被杀之外的所有异常路径都必须清掉它，否则 filesDir 会永久留下一份完整文档副本。
            temporary.delete()
        }
    }

    /**
     * 清理上一次导入被进程杀死后残留的 `.import_*.tmp` 文件，返回删除的数量。
     * 比 [maxAgeMs] 新的文件视为可能仍在写入的导入任务，不会被删除。
     *
     * 建议由应用启动路径调用（MainActivity.onCreate 里已有的 IO 协程，
     * 即 `lifecycleScope.launch(Dispatchers.IO)` 那一段）。
     */
    fun sweepStaleTemporaryFiles(context: Context, maxAgeMs: Long = STALE_TEMP_MAX_AGE_MS): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs.coerceAtLeast(0L)
        val stale = context.filesDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(TEMP_FILE_PREFIX) &&
                file.name.endsWith(TEMP_FILE_SUFFIX) &&
                file.lastModified() < cutoff
        } ?: return 0
        return stale.count { it.delete() }
    }

    internal fun formatForMime(mimeType: String?): String = mimeFormats[mimeType?.lowercase()].orEmpty()

    private fun extensionFor(format: String): String = when (format.lowercase()) {
        "markdown" -> "md"
        "jpeg" -> "jpg"
        else -> format.lowercase().ifBlank { "txt" }
    }

    /** 扩展名 → 格式。MIME 缺失或不可信时的兜底判型依据。 */
    internal fun formatForExtension(fileName: String): String =
        app.yeshu.reader.parse.DocParser.detect(fileName)

    private fun sanitizeName(value: String): String = value
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim(' ', '.')
        .take(120)
        .ifBlank { "document_${System.currentTimeMillis()}" }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
