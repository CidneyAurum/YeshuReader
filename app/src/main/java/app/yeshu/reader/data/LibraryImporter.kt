package app.yeshu.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.yeshu.reader.CoverStore
import app.yeshu.reader.Db
import app.yeshu.reader.parse.DocParser
import java.io.File
import java.security.MessageDigest

data class ImportResult(val id: Long, val title: String, val format: String, val error: String? = null)

object LibraryImporter {
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

    fun import(context: Context, uri: Uri): ImportResult {
        val displayName = queryName(context, uri)
            ?.takeIf { it.isNotBlank() }
            ?: "document_${System.currentTimeMillis()}.txt"
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120)
        val format = DocParser.detect(safeName).ifBlank {
            when (context.contentResolver.getType(uri)) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> "txt"
            }
        }
        if (format !in setOf("pdf", "epub", "docx", "pptx", "txt", "md", "markdown", "jpg", "jpeg", "png")) {
            return ImportResult(-1, safeName, format, "暂不支持 .$format 格式")
        }
        val title = safeName.substringBeforeLast('.', safeName)
        val destination = File(context.filesDir, "${System.currentTimeMillis()}_$safeName")
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
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
            val db = Db(context)
            val existing = db.listBooks().firstOrNull { it.contentHash.isNotBlank() && it.contentHash == hash }
            if (existing != null) {
                destination.delete()
                return ImportResult(existing.id, existing.title, existing.format, "该文件已在书架中")
            }
            val id = db.insertBook(title, destination.name, format, size, hash)
            require(id > 0) { "写入书库失败" }
            CoverStore.generateAsync(context, id, destination, format)
            ImportResult(id, title, format)
        }.getOrElse {
            destination.delete()
            ImportResult(-1, title, format, it.message ?: "导入失败")
        }
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
