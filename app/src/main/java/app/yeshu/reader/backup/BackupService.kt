package app.yeshu.reader.backup

import android.content.Context
import android.net.Uri
import app.yeshu.reader.CoverStore
import app.yeshu.reader.Db
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.NoteEntity
import app.yeshu.reader.data.SettingEntity
import app.yeshu.reader.data.YeshuDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupService {
    private const val LIBRARY_JSON = "library.json"
    private val backupSettingKeys = setOf(
        "reader_font_sp", "reader_brightness", "night_mode",
        "shelf_sort", "shelf_filter", "shelf_view"
    )

    fun export(context: Context, uri: Uri): String = runCatching {
        val db = Db(context)
        val room = YeshuDatabase.get(context)
        val books = db.listBooks()
        val root = JSONObject()
            .put("app", "yeshu")
            .put("version", 2)
            .put("exportedAt", System.currentTimeMillis())
            .put("includesOriginalFiles", true)
            .put("includesSecrets", false)

        root.put("folders", JSONArray().apply {
            db.allFolders().forEach { folder ->
                put(JSONObject().put("id", folder.id).put("name", folder.name).put("parentId", folder.parentId))
            }
        })
        root.put("books", JSONArray().apply {
            books.forEach { book ->
                put(JSONObject()
                    .put("id", book.id).put("title", book.title).put("fileName", book.fileName)
                    .put("format", book.format).put("sizeBytes", book.sizeBytes)
                    .put("progress", book.progress.toDouble()).put("addedAt", book.addedAt)
                    .put("lastReadAt", book.lastReadAt).put("folderId", book.folderId)
                    .put("author", book.author).put("itemType", book.itemType)
                    .put("status", book.status).put("favorite", book.favorite)
                    .put("tags", book.tags).put("contentHash", book.contentHash))
            }
        })
        root.put("notes", JSONArray().apply {
            room.dao().allNotes().forEach { note ->
                put(JSONObject().put("id", note.id).put("bookId", note.bookId).put("kind", note.kind)
                    .put("content", note.content).put("createdAt", note.createdAt))
            }
        })
        root.put("artifacts", JSONArray().apply {
            room.dao().allArtifacts().forEach { artifact ->
                put(JSONObject().put("id", artifact.id).put("bookId", artifact.bookId)
                    .put("kind", artifact.kind).put("status", artifact.status).put("content", artifact.content)
                    .put("citationsJson", artifact.citationsJson).put("documentHash", artifact.documentHash)
                    .put("model", artifact.model).put("promptVersion", artifact.promptVersion)
                    .put("createdAt", artifact.createdAt).put("updatedAt", artifact.updatedAt))
            }
        })
        root.put("settings", JSONArray().apply {
            room.dao().allSettings().filter { it.key in backupSettingKeys }.forEach { setting ->
                put(JSONObject().put("key", setting.key).put("value", setting.value))
            }
        })

        context.contentResolver.openOutputStream(uri, "w")!!.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(LIBRARY_JSON))
                zip.write(root.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                books.forEach { book ->
                    val original = File(context.filesDir, book.fileName)
                    if (original.isFile) addFile(zip, original, "library/${original.name}")
                    val cover = CoverStore.file(context, book.id)
                    if (cover.isFile) addFile(zip, cover, "covers/${cover.name}")
                }
                File(context.filesDir, "bg.img").takeIf { it.isFile }?.let { addFile(zip, it, "ui/bg.img") }
            }
        }
        "备份完成：${books.size} 项，API Key 未包含"
    }.getOrElse { "备份失败：${it.message}" }

    fun restore(context: Context, uri: Uri): String = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        if (first == '{') return restoreLegacyJson(context, JSONObject(bytes.toString(Charsets.UTF_8)))

        val staging = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            var metadata: JSONObject? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && ".." !in name.split('/')) { "备份包含不安全路径" }
                    if (name == LIBRARY_JSON) {
                        metadata = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    } else if (!entry.isDirectory && (name.startsWith("library/") || name.startsWith("covers/") || name == "ui/bg.img")) {
                        val destination = File(staging, name)
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            val root = metadata ?: error("备份缺少 $LIBRARY_JSON")
            require(root.optString("app") in setOf("yeshu", "shuge")) { "不是页枢备份文件" }
            restoreMetadata(context, root, staging)
        } finally {
            staging.deleteRecursively()
        }
    }.getOrElse { "恢复失败：${it.message}" }

    private fun restoreMetadata(context: Context, root: JSONObject, staging: File): String {
        val db = Db(context)
        val dao = YeshuDatabase.get(context).dao()
        val folderMap = mutableMapOf<Long, Long>(0L to 0L)
        val pending = mutableListOf<JSONObject>()
        val folders = root.optJSONArray("folders") ?: JSONArray()
        for (index in 0 until folders.length()) pending += folders.getJSONObject(index)
        var guard = 0
        while (pending.isNotEmpty() && guard++ < 128) {
            val iterator = pending.iterator()
            var progressed = false
            while (iterator.hasNext()) {
                val folder = iterator.next()
                val oldParent = folder.optLong("parentId", 0)
                val newParent = folderMap[oldParent] ?: continue
                val name = folder.optString("name").trim()
                val existing = db.listFolders(newParent).firstOrNull { it.name == name }
                folderMap[folder.getLong("id")] = existing?.id ?: db.addFolder(name, newParent)
                iterator.remove()
                progressed = true
            }
            if (!progressed) break
        }

        val bookMap = mutableMapOf<Long, Long>()
        var added = 0
        var skipped = 0
        val books = root.optJSONArray("books") ?: JSONArray()
        for (index in 0 until books.length()) {
            val item = books.getJSONObject(index)
            val title = item.optString("title", "未命名")
            val size = item.optLong("sizeBytes", 0)
            val oldId = item.optLong("id", index.toLong() + 1)
            val existing = db.findBook(title, size)
            if (existing != null) {
                bookMap[oldId] = existing.id
                skipped++
                continue
            }
            val oldFileName = File(item.optString("fileName")).name
            val staged = File(staging, "library/$oldFileName")
            if (!staged.isFile) {
                skipped++
                continue
            }
            val newFileName = "${System.currentTimeMillis()}_${index}_$oldFileName"
            staged.copyTo(File(context.filesDir, newFileName), overwrite = false)
            val newId = db.restoreBook(
                title = title,
                fileName = newFileName,
                format = item.optString("format", "txt"),
                sizeBytes = size,
                progress = item.optDouble("progress", 0.0).toFloat(),
                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                lastReadAt = item.optLong("lastReadAt", 0),
                folderId = folderMap[item.optLong("folderId", 0)] ?: 0,
                author = item.optString("author"),
                itemType = item.optString("itemType", "book"),
                status = item.optString("status", "unread"),
                favorite = item.optBoolean("favorite", false),
                tags = item.optString("tags"),
                contentHash = item.optString("contentHash")
            )
            if (newId > 0) {
                bookMap[oldId] = newId
                File(staging, "covers/cover_$oldId.img").takeIf { it.isFile }
                    ?.copyTo(CoverStore.file(context, newId), overwrite = true)
                added++
            } else skipped++
        }

        val notes = root.optJSONArray("notes") ?: JSONArray()
        for (index in 0 until notes.length()) {
            val note = notes.getJSONObject(index)
            val newBook = bookMap[note.optLong("bookId")] ?: continue
            dao.addNote(NoteEntity(
                bookId = newBook,
                kind = note.optString("kind", "note"),
                content = note.optString("content"),
                createdAt = note.optLong("createdAt", System.currentTimeMillis())
            ))
        }
        val artifacts = root.optJSONArray("artifacts") ?: JSONArray()
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(index)
            val newBook = bookMap[artifact.optLong("bookId")] ?: continue
            dao.saveArtifact(AiArtifactEntity(
                bookId = newBook,
                kind = artifact.optString("kind"),
                status = artifact.optString("status", "complete"),
                content = artifact.optString("content"),
                citationsJson = artifact.optString("citationsJson", "[]"),
                documentHash = artifact.optString("documentHash"),
                model = artifact.optString("model"),
                promptVersion = artifact.optInt("promptVersion", 1),
                createdAt = artifact.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = artifact.optLong("updatedAt", System.currentTimeMillis())
            ))
        }
        val settings = root.optJSONArray("settings") ?: JSONArray()
        for (index in 0 until settings.length()) {
            val setting = settings.getJSONObject(index)
            val key = setting.optString("key")
            if (key in backupSettingKeys) dao.setSetting(SettingEntity(key, setting.optString("value")))
        }
        File(staging, "ui/bg.img").takeIf { it.isFile }?.copyTo(File(context.filesDir, "bg.img"), overwrite = true)
        return "恢复完成：新增 $added 项，跳过 $skipped 项，API Key 未导入"
    }

    private fun restoreLegacyJson(context: Context, root: JSONObject): String {
        require(root.optString("app") == "shuge") { "不是书阁/页枢备份文件" }
        // V1 never embedded originals. It can still restore metadata when matching files are
        // already present (for example during an in-place upgrade or manual data migration).
        val staging = File(context.cacheDir, "legacy_restore_${System.currentTimeMillis()}")
        val library = File(staging, "library").apply { mkdirs() }
        return try {
            val books = root.optJSONArray("books") ?: JSONArray()
            for (index in 0 until books.length()) {
                val name = File(books.getJSONObject(index).optString("fileName")).name
                File(context.filesDir, name).takeIf { it.isFile }?.copyTo(File(library, name), overwrite = true)
            }
            restoreMetadata(context, root, staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
