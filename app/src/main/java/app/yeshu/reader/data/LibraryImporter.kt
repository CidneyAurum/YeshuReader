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
    private val mimeFormats = mapOf(
        "application/pdf" to "pdf",
        "application/x-pdf" to "pdf",
        "application/epub+zip" to "epub",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-word.document.macroenabled.12" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to "pptx",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png"
    )

    val mimeTypes = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "text/markdown",
        "image/jpeg",
        "image/png"
    )

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
        val temporary = File(context.filesDir, ".import_${UUID.randomUUID()}.tmp")
        var destination: File? = null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
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
            val existing = db.listBooks().firstOrNull { it.contentHash.isNotBlank() && it.contentHash == hash }
            if (existing != null) {
                temporary.delete()
                return ImportResult(existing.id, existing.title, existing.format, "该文件已在书架中")
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
        }.getOrElse {
            temporary.delete()
            destination?.delete()
            ImportResult(-1, title, hintedFormat, it.message ?: "导入失败")
        }
    }

    internal fun formatForMime(mimeType: String?): String = mimeFormats[mimeType?.lowercase()].orEmpty()

    private fun extensionFor(format: String): String = when (format.lowercase()) {
        "markdown" -> "md"
        "jpeg" -> "jpg"
        else -> format.lowercase().ifBlank { "txt" }
    }

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
