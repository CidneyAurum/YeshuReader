package app.yeshu.reader.backup

import android.content.Context
import android.net.Uri
import app.yeshu.reader.CoverStore
import app.yeshu.reader.Db
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.NoteEntity
import app.yeshu.reader.data.ReadLogEntity
import app.yeshu.reader.data.SettingEntity
import app.yeshu.reader.data.YeshuDatabase
import app.yeshu.reader.preferences.UserPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupService {
    private const val LIBRARY_JSON = "library.json"
    private const val MAX_ZIP_ENTRIES = 4096
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    private const val MAX_LIBRARY_JSON_BYTES = 16L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val MAX_DETECTION_BYTES = 64 * 1024
    private val backupSettingKeys = setOf(
        "reader_font_sp", "reader_brightness", "night_mode",
        "shelf_sort", "shelf_filter", "shelf_view",
        "ai_base_url", "ai_model", "ai_vision_model", "ai_allow_private_http"
    )

    fun export(context: Context, uri: Uri): String = runCatching {
        val db = Db(context)
        val room = YeshuDatabase.get(context)
        val books = db.listBooks()
        val appearance = runBlocking { UserPreferences(context).snapshot() }
        val root = JSONObject()
            .put("app", "yeshu")
            .put("version", 3)
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
                    .put("tags", book.tags).put("contentHash", book.contentHash)
                    .put("totalReadMs", book.totalReadMs)
                    .put("coverSource", if (CoverStore.isCustom(context, book.id)) "custom" else "auto"))
            }
        })
        root.put("readLogs", JSONArray().apply {
            room.dao().allReadLogs().forEach { row ->
                put(JSONObject().put("day", row.day).put("ms", row.ms))
            }
        })
        root.put("notes", JSONArray().apply {
            val bookIds = books.mapTo(hashSetOf()) { it.id }
            room.dao().allNotes().filter { it.bookId in bookIds }.forEach { note ->
                put(JSONObject().put("id", note.id).put("bookId", note.bookId).put("kind", note.kind)
                    .put("content", note.content).put("createdAt", note.createdAt))
            }
        })
        root.put("artifacts", JSONArray().apply {
            val bookIds = books.mapTo(hashSetOf()) { it.id }
            room.dao().allArtifacts().filter { it.bookId in bookIds }.forEach { artifact ->
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
        root.put("appearance", JSONObject()
            .put("themeMode", appearance.themeMode)
            .put("showIllustrations", appearance.showIllustrations))

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
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份")
        BufferedInputStream(input).use { buffered ->
            buffered.mark(MAX_DETECTION_BYTES)
            val first = firstNonWhitespace(buffered)
            buffered.reset()
            if (first == '{'.code) {
                val legacy = JSONObject(readLimited(buffered, MAX_LIBRARY_JSON_BYTES).toString(Charsets.UTF_8))
                return restoreLegacyJson(context, legacy)
            }
            restoreZip(context, buffered)
        }
    }.getOrElse { error ->
        val message = error.message.orEmpty()
        val userMessage = if (message.contains("Invalid zip entry path", ignoreCase = true)) {
            "备份包含不安全路径"
        } else {
            message
        }
        "恢复失败：$userMessage"
    }

    private fun restoreZip(context: Context, input: InputStream): String {
        val staging = File(context.cacheDir, "restore_${System.currentTimeMillis()}_${System.nanoTime()}")
        require(staging.mkdirs()) { "无法创建恢复临时目录" }
        try {
            val archive = File(staging, "backup.zip")
            archive.outputStream().use { copyLimited(input, it, MAX_ARCHIVE_BYTES) }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= MAX_ZIP_ENTRIES) { "备份条目过多" }
                val names = hashSetOf<String>()
                var totalBytes = 0L
                var metadataEntry: java.util.zip.ZipEntry? = null
                entries.forEach { entry ->
                    val name = safeEntryName(entry.name)
                    require(names.add(name)) { "备份包含重复条目" }
                    val size = entry.size
                    val compressed = entry.compressedSize
                    require(size >= 0 && compressed >= 0) { "备份条目大小无效" }
                    val entryLimit = if (name == LIBRARY_JSON) MAX_LIBRARY_JSON_BYTES else MAX_ENTRY_UNCOMPRESSED_BYTES
                    require(size <= entryLimit) { "备份单项过大" }
                    require(size <= MAX_TOTAL_UNCOMPRESSED_BYTES - totalBytes) { "备份解压总量过大" }
                    totalBytes += size
                    if (size > 0) {
                        require(compressed > 0 && size <= compressed * MAX_COMPRESSION_RATIO) { "备份压缩比过高" }
                    }
                    if (name == LIBRARY_JSON) {
                        require(!entry.isDirectory && metadataEntry == null) { "备份包含无效的 $LIBRARY_JSON" }
                        metadataEntry = entry
                    }
                }

                val metadataZipEntry = metadataEntry ?: error("备份缺少 $LIBRARY_JSON")
                val metadata = zip.getInputStream(metadataZipEntry).use {
                    JSONObject(readLimited(it, MAX_LIBRARY_JSON_BYTES).toString(Charsets.UTF_8))
                }
                require(metadata.optString("app") in setOf("yeshu", "shuge")) { "不是页枢备份文件" }

                entries.forEach { entry ->
                    val name = safeEntryName(entry.name)
                    if (entry.isDirectory || name == LIBRARY_JSON || !isRestorableFile(name)) return@forEach
                    val destination = File(staging, name)
                    require(destination.canonicalFile.path.startsWith(staging.canonicalFile.path + File.separator)) {
                        "备份包含不安全路径"
                    }
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { source ->
                        destination.outputStream().use { target ->
                            val copied = copyLimited(source, target, MAX_ENTRY_UNCOMPRESSED_BYTES)
                            require(copied == entry.size) { "备份条目损坏" }
                        }
                    }
                }
                return restoreMetadata(context, metadata, staging)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun restoreMetadata(context: Context, root: JSONObject, staging: File): String {
        val db = Db(context)
        val room = YeshuDatabase.get(context)
        val dao = room.dao()
        val createdFiles = mutableListOf<File>()
        val background = File(context.filesDir, "bg.img")
        val backgroundBackup = File(staging, "rollback/bg.img")
        var backgroundChanged = false
        val appearanceJson = root.optJSONObject("appearance")
        val userPreferences = UserPreferences(context)
        val previousAppearance = appearanceJson?.let { runBlocking { userPreferences.snapshot() } }
        var appearanceChanged = false
        return try {
            val counts = room.runInTransaction<Pair<Int, Int>> {
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
                        if (name.isBlank()) {
                            iterator.remove()
                            continue
                        }
                        val existing = db.listFolders(newParent).firstOrNull { it.name == name }
                        folderMap[folder.optLong("id")] = existing?.id ?: db.addFolder(name, newParent)
                        iterator.remove()
                        progressed = true
                    }
                    if (!progressed) break
                }

                val bookMap = mutableMapOf(0L to 0L)
                var added = 0
                var skipped = 0
                val books = root.optJSONArray("books") ?: JSONArray()
                for (index in 0 until books.length()) {
                    val item = books.getJSONObject(index)
                    val title = item.optString("title", "未命名")
                    val size = item.optLong("sizeBytes", 0)
                    val oldId = item.optLong("id", index.toLong() + 1)
                    val contentHash = item.optString("contentHash").trim().lowercase(Locale.ROOT)
                    val totalReadMs = item.optLong("totalReadMs", 0L).coerceAtLeast(0L)
                    // A supplied hash is authoritative. Falling back to title/size after a
                    // hash miss can incorrectly merge two different documents.
                    val existingId = if (contentHash.isNotBlank()) {
                        dao.findBookByContentHash(contentHash)?.id
                    } else {
                        db.findBook(title, size)?.id
                    }
                    if (existingId != null) {
                        bookMap[oldId] = existingId
                        dao.mergeTotalReadMs(existingId, totalReadMs)
                        skipped++
                        continue
                    }
                    val oldFileName = File(item.optString("fileName")).name
                    val staged = File(staging, "library/$oldFileName")
                    if (!staged.isFile) {
                        skipped++
                        continue
                    }
                    val newFileName = "restore_${System.currentTimeMillis()}_${System.nanoTime()}_${index}_$oldFileName"
                    val destination = File(context.filesDir, newFileName)
                    createdFiles += destination
                    staged.copyTo(destination, overwrite = false)
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
                        contentHash = contentHash,
                        totalReadMs = totalReadMs
                    )
                    require(newId > 0) { "恢复书籍记录失败" }
                    bookMap[oldId] = newId
                    File(staging, "covers/cover_$oldId.img").takeIf { it.isFile }?.let { stagedCover ->
                        val cover = CoverStore.file(context, newId)
                        createdFiles += cover
                        stagedCover.copyTo(cover, overwrite = false)
                        val customCover = item.optString("coverSource") == "custom"
                        if (CoverStore.restoreCustomFlag(context, newId, customCover) && customCover) {
                            createdFiles += File(context.filesDir, "cover_$newId.custom")
                        }
                    }
                    added++
                }

                val notes = root.optJSONArray("notes") ?: JSONArray()
                for (index in 0 until notes.length()) {
                    val note = notes.getJSONObject(index)
                    val newBook = bookMap[note.optLong("bookId")] ?: continue
                    val kind = note.optString("kind", "note")
                    val content = note.optString("content")
                    val createdAt = note.optLong("createdAt", 0L)
                    if (dao.findNote(newBook, kind, content, createdAt) == null) {
                        dao.addNote(NoteEntity(bookId = newBook, kind = kind, content = content, createdAt = createdAt))
                    }
                }
                val artifacts = root.optJSONArray("artifacts") ?: JSONArray()
                for (index in 0 until artifacts.length()) {
                    val artifact = artifacts.getJSONObject(index)
                    val newBook = bookMap[artifact.optLong("bookId")] ?: continue
                    val kind = artifact.optString("kind")
                    val documentHash = artifact.optString("documentHash")
                    val model = artifact.optString("model")
                    val promptVersion = artifact.optInt("promptVersion", 1)
                    val updatedAt = artifact.optLong("updatedAt", 0L)
                    val existing = dao.findArtifactEntity(newBook, kind, documentHash, model, promptVersion)
                    if (existing != null && existing.updatedAt >= updatedAt) continue
                    dao.saveArtifact(AiArtifactEntity(
                        id = existing?.id ?: 0L,
                        bookId = newBook,
                        kind = kind,
                        status = artifact.optString("status", "complete"),
                        content = artifact.optString("content"),
                        citationsJson = artifact.optString("citationsJson", "[]"),
                        documentHash = documentHash,
                        model = model,
                        promptVersion = promptVersion,
                        createdAt = artifact.optLong("createdAt", 0L),
                        updatedAt = updatedAt
                    ))
                }
                val readLogs = root.optJSONArray("readLogs") ?: JSONArray()
                for (index in 0 until readLogs.length()) {
                    val row = readLogs.getJSONObject(index)
                    val day = row.optString("day").trim()
                    val ms = row.optLong("ms", 0L).coerceAtLeast(0L)
                    if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue
                    dao.insertReadLog(ReadLogEntity(day, 0L))
                    dao.mergeReadLog(day, ms)
                }
                val settings = root.optJSONArray("settings") ?: JSONArray()
                for (index in 0 until settings.length()) {
                    val setting = settings.getJSONObject(index)
                    val key = setting.optString("key")
                    if (key in backupSettingKeys) dao.setSetting(SettingEntity(key, setting.optString("value")))
                }
                appearanceJson?.let { appearance ->
                    runBlocking {
                        userPreferences.restore(
                            UserPreferences.Snapshot(
                                themeMode = appearance.optString("themeMode", "system"),
                                showIllustrations = appearance.optBoolean("showIllustrations", true)
                            )
                        )
                    }
                    appearanceChanged = true
                }
                File(staging, "ui/bg.img").takeIf { it.isFile }?.let { stagedBackground ->
                    if (background.isFile) {
                        backgroundBackup.parentFile?.mkdirs()
                        background.copyTo(backgroundBackup, overwrite = true)
                    }
                    backgroundChanged = true
                    stagedBackground.copyTo(background, overwrite = true)
                }
                added to skipped
            }
            "恢复完成：新增 ${counts.first} 项，跳过 ${counts.second} 项，API Key 未导入"
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach { it.delete() }
            if (backgroundChanged) {
                if (backgroundBackup.isFile) backgroundBackup.copyTo(background, overwrite = true) else background.delete()
            }
            if (appearanceChanged && previousAppearance != null) {
                runCatching { runBlocking { userPreferences.restore(previousAppearance) } }
            }
            throw error
        }
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

    private fun firstNonWhitespace(input: InputStream): Int {
        var read = 0
        while (read++ < MAX_DETECTION_BYTES) {
            val value = input.read()
            if (value < 0) return -1
            if (!value.toChar().isWhitespace()) return value
        }
        error("备份头过大")
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toByteArray()
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, priorBytes: Long = 0L): Long {
        require(limit >= 0 && priorBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "备份大小无效" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(copied <= limit - count && priorBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES - copied - count) {
                "备份解压大小超限"
            }
            output.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    private fun safeEntryName(rawName: String): String {
        val raw = rawName.replace('\\', '/')
        require(raw.isNotBlank() && !raw.startsWith('/') && ".." !in raw.split('/')) {
            "备份包含不安全路径"
        }
        val name = raw.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        require(name.isNotBlank()) { "备份包含不安全路径" }
        return name
    }

    private fun isRestorableFile(name: String): Boolean =
        name.startsWith("library/") || name.startsWith("covers/") || name == "ui/bg.img"

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
