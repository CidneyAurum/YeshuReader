package app.yeshu.reader.backup

import android.content.Context
import android.net.Uri
import app.yeshu.reader.CoverStore
import app.yeshu.reader.Db
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.BookmarkEntity
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
        "reader_font_sp", "reader_brightness", "night_mode", "reader_volume_flip",
        "shelf_sort", "shelf_filter", "shelf_view",
        "ai_base_url", "ai_model", "ai_vision_model", "ai_allow_private_http",
        "ai_chat_path", "ai_models_path", "ai_auth_header", "ai_auth_prefix",
        "ai_profiles_json", "ai_active_profile_id"
    )

    /** 恢复结果分类：新增、与本地已有条目合并，以及跳过时各自的真实原因。 */
    private class RestoreCounts {
        var added = 0
        var merged = 0
        var existing = 0
        var missingFiles = 0
        var unrecognized = 0
        val skipped: Int get() = existing + missingFiles + unrecognized
    }

    /** 本地已有条目的合并基准；内容哈希命中与标题/大小回退命中都要用它。 */
    private data class LocalBookState(
        val id: Long,
        val progress: Float,
        val totalReadMs: Long,
        val folderId: Long
    )

    fun export(context: Context, uri: Uri): String = runCatching {
        val db = Db(context)
        val room = YeshuDatabase.get(context)
        val books = db.listBooks()
        val appearance = runBlocking { UserPreferences(context).snapshot() }
        val root = JSONObject()
            .put("app", "yeshu")
            .put("version", 4)
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
                // anchor/status 必须一起导出：缺了它们，恢复后「金句」找不到出处、
                // 「校验未通过但已保留」的结果会被当成校验通过。
                put(JSONObject().put("id", note.id).put("bookId", note.bookId).put("kind", note.kind)
                    .put("content", note.content).put("createdAt", note.createdAt)
                    .put("anchor", note.anchor).put("status", note.status))
            }
        })
        root.put("bookmarks", JSONArray().apply {
            val bookIds = books.mapTo(hashSetOf()) { it.id }
            db.recentBookmarks().filter { it.bookId in bookIds }.forEach { mark ->
                put(JSONObject().put("bookId", mark.bookId).put("anchor", mark.anchor)
                    .put("label", mark.label).put("excerpt", mark.excerpt)
                    .put("createdAt", mark.createdAt))
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

        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("无法写入所选位置，请重新选择备份保存位置")
        output.use { raw ->
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

    /** 恢复前的只读清单：用户在覆盖前应当知道会导入多少内容。 */
    data class BackupSummary(
        val version: Int,
        val exportedAt: Long,
        val books: Int,
        val notes: Int,
        val artifacts: Int,
        val bookmarks: Int,
        val includesOriginals: Boolean
    ) {
        fun describe(): String = buildString {
            append("包含 ${books} 本书目、${notes} 条笔记、${artifacts} 份 AI 成果")
            if (bookmarks > 0) append("、${bookmarks} 个书签")
            append('。')
            if (includesOriginals) append("含书籍原文件与封面。")
            append("同名书籍按内容合并，不会删除本地多余条目；API Key 不会导入。")
        }
    }

    /**
     * 只读取备份清单，不写任何数据。
     *
     * 恢复是不可逆的批量写入，先让用户看到「会导入什么」再确认；
     * 解析失败时返回 null，调用方回退到「直接恢复」并照常报错。
     */
    fun inspect(context: Context, uri: Uri): BackupSummary? = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        BufferedInputStream(input).use { buffered ->
            buffered.mark(MAX_DETECTION_BYTES)
            val first = firstNonWhitespace(buffered)
            buffered.reset()
            if (first == '{'.code) {
                // 旧版 JSON 备份：没有条目清单，仍按元数据里的数组长度给出数量
                val legacy = JSONObject(readLimited(buffered, MAX_LIBRARY_JSON_BYTES).toString(Charsets.UTF_8))
                return BackupSummary(
                    version = 0,
                    exportedAt = legacy.optLong("exportedAt", 0L),
                    books = legacy.optJSONArray("books")?.length() ?: 0,
                    notes = legacy.optJSONArray("notes")?.length() ?: 0,
                    artifacts = 0,
                    bookmarks = 0,
                    includesOriginals = false
                )
            }
            val archive = File(context.cacheDir, "inspect_${System.nanoTime()}.zip")
            try {
                archive.outputStream().use { copyLimited(buffered, it, MAX_ARCHIVE_BYTES) }
                ZipFile(archive).use { zip ->
                    val entry = zip.entries().asSequence()
                        .firstOrNull { safeEntryName(it.name) == LIBRARY_JSON } ?: return null
                    val meta = zip.getInputStream(entry).use {
                        JSONObject(readLimited(it, MAX_LIBRARY_JSON_BYTES).toString(Charsets.UTF_8))
                    }
                    require(meta.optString("app") in setOf("yeshu", "shuge")) { "不是页枢备份文件" }
                    BackupSummary(
                        version = meta.optInt("version", 0),
                        exportedAt = meta.optLong("exportedAt", 0L),
                        books = meta.optJSONArray("books")?.length() ?: 0,
                        notes = meta.optJSONArray("notes")?.length() ?: 0,
                        artifacts = meta.optJSONArray("artifacts")?.length() ?: 0,
                        bookmarks = meta.optJSONArray("bookmarks")?.length() ?: 0,
                        includesOriginals = meta.optBoolean("includesOriginalFiles", false)
                    )
                }
            } finally {
                archive.delete()
            }
        }
    }.getOrNull()

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

                // 中央目录声明的 size 可以被伪造，因此总量上限必须按实际写出的字节数累计，
                // 而不是只看 entry.size，否则伪造 0 字节的条目仍可向 cacheDir 灌入大量数据。
                var extractedBytes = 0L
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
                            val copied = copyLimited(
                                source,
                                target,
                                MAX_ENTRY_UNCOMPRESSED_BYTES,
                                priorBytes = extractedBytes
                            )
                            require(copied == entry.size) { "备份条目损坏" }
                            extractedBytes += copied
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
        // 合并路径可能覆盖本地已有的封面，先留一份回滚副本，事务失败时不会让用户丢掉本机封面。
        val coverRollback = mutableMapOf<Long, File>()
        val background = File(context.filesDir, "bg.img")
        val backgroundBackup = File(staging, "rollback/bg.img")
        var backgroundChanged = false
        val appearanceJson = root.optJSONObject("appearance")
        val userPreferences = UserPreferences(context)
        val previousAppearance = appearanceJson?.let { runBlocking { userPreferences.snapshot() } }
        var appearanceChanged = false
        return try {
            // DataStore 的 edit 是挂起操作，放在 Room 事务之外执行，避免持锁期间等待磁盘 IO。
            // 顺序上先恢复偏好再跑数据库事务：任一步失败都会在 catch 中回滚已改动的偏好，
            // 因此不会出现偏好已恢复而数据库未提交的半恢复状态。
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
            val restored = room.runInTransaction<RestoreCounts> {
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
                val counts = RestoreCounts()
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
                    val local = if (contentHash.isNotBlank()) {
                        dao.findBookByContentHash(contentHash)?.let {
                            LocalBookState(it.id, it.progress, it.totalReadMs, it.folderId)
                        }
                    } else {
                        db.findBook(title, size)?.let {
                            LocalBookState(it.id, it.progress, it.totalReadMs, it.folderId)
                        }
                    }
                    if (local != null) {
                        // 本地已有同一条目时不再简单跳过：进度、时长、文件夹与封面都要按“取更完整的一方”合并，
                        // 否则在已有书库的设备上恢复备份会退回旧的阅读位置并丢掉备份里的封面与分组。
                        bookMap[oldId] = local.id
                        var changed = false
                        if (totalReadMs > local.totalReadMs) {
                            dao.mergeTotalReadMs(local.id, totalReadMs)
                            changed = true
                        }
                        val backupProgress = item.optDouble("progress", 0.0).toFloat()
                        if (backupProgress > local.progress) {
                            db.updateProgress(local.id, backupProgress)
                            changed = true
                        }
                        val mappedFolder = folderMap[item.optLong("folderId", 0)] ?: 0L
                        if (mappedFolder != 0L && mappedFolder != local.folderId) {
                            dao.moveBook(local.id, mappedFolder)
                            changed = true
                        }
                        if (mergeArchivedCover(context, staging, oldId, local.id, item, createdFiles, coverRollback)) {
                            changed = true
                        }
                        if (changed) counts.merged++ else counts.existing++
                        continue
                    }
                    val oldFileName = File(item.optString("fileName")).name
                    if (oldFileName.isBlank()) {
                        // 元数据里没有可用的文件名：既无法定位备份内的原文件，也无法安全地新建记录。
                        counts.unrecognized++
                        continue
                    }
                    val staged = File(staging, "library/$oldFileName")
                    if (!staged.isFile) {
                        counts.missingFiles++
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
                    counts.added++
                }

                val notes = root.optJSONArray("notes") ?: JSONArray()
                for (index in 0 until notes.length()) {
                    val note = notes.getJSONObject(index)
                    val newBook = bookMap[note.optLong("bookId")] ?: continue
                    val kind = note.optString("kind", "note")
                    val content = note.optString("content")
                    val createdAt = note.optLong("createdAt", 0L)
                    if (dao.findNote(newBook, kind, content, createdAt) == null) {
                        dao.addNote(NoteEntity(
                            bookId = newBook,
                            kind = kind,
                            content = content,
                            createdAt = createdAt,
                            anchor = note.optString("anchor", ""),
                            status = note.optString("status", "")
                        ))
                    }
                }
                // 书签按 (bookId, anchor) 去重，重复恢复同一份备份不会堆出一串相同位置
                val bookmarks = root.optJSONArray("bookmarks") ?: JSONArray()
                for (index in 0 until bookmarks.length()) {
                    val mark = bookmarks.getJSONObject(index)
                    val newBook = bookMap[mark.optLong("bookId")] ?: continue
                    val anchor = mark.optString("anchor").trim()
                    if (anchor.isBlank()) continue
                    if (dao.listBookmarks(newBook).any { it.anchor == anchor }) continue
                    dao.addBookmark(BookmarkEntity(
                        bookId = newBook,
                        anchor = anchor,
                        label = mark.optString("label"),
                        excerpt = mark.optString("excerpt"),
                        createdAt = mark.optLong("createdAt", System.currentTimeMillis())
                    ))
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
                File(staging, "ui/bg.img").takeIf { it.isFile }?.let { stagedBackground ->
                    if (background.isFile) {
                        backgroundBackup.parentFile?.mkdirs()
                        background.copyTo(backgroundBackup, overwrite = true)
                    }
                    backgroundChanged = true
                    stagedBackground.copyTo(background, overwrite = true)
                }
                counts
            }
            // 日累计与总量是两套合并策略，恢复后必须校正一次，否则统计页会出现日合计大于总量
            runCatching { Db(context).reconcileReadTotals() }
            "恢复完成：${restoreSummary(restored)}，API Key 未导入"
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach { it.delete() }
            coverRollback.forEach { (bookId, backup) ->
                val target = CoverStore.file(context, bookId)
                if (backup.isFile) backup.copyTo(target, overwrite = true) else target.delete()
            }
            if (backgroundChanged) {
                if (backgroundBackup.isFile) backgroundBackup.copyTo(background, overwrite = true) else background.delete()
            }
            if (appearanceChanged && previousAppearance != null) {
                runCatching { runBlocking { userPreferences.restore(previousAppearance) } }
            }
            throw error
        }
    }

    /**
     * 把备份里的封面合并到本地已存在的书目上，返回是否真的改动了封面。
     *
     * 本地已有的自定义封面是用户在本机的选择，备份里的自动封面绝不能覆盖它；只有备份封面
     * 同样是自定义封面时才替换本地自动封面（或本地没有封面时直接落盘）。
     */
    private fun mergeArchivedCover(
        context: Context,
        staging: File,
        oldId: Long,
        bookId: Long,
        item: JSONObject,
        createdFiles: MutableList<File>,
        coverRollback: MutableMap<Long, File>
    ): Boolean {
        val stagedCover = File(staging, "covers/cover_$oldId.img").takeIf { it.isFile } ?: return false
        val archivedCustom = item.optString("coverSource") == "custom"
        if (CoverStore.isCustom(context, bookId) && !archivedCustom) return false
        val cover = CoverStore.file(context, bookId)
        val marker = File(context.filesDir, "cover_$bookId.custom")
        val hadCover = cover.isFile
        val hadMarker = marker.isFile
        if (hadCover && bookId !in coverRollback) {
            val backup = File(staging, "rollback/cover_$bookId.img")
            backup.parentFile?.mkdirs()
            cover.copyTo(backup, overwrite = true)
            coverRollback[bookId] = backup
        }
        if (!hadCover) createdFiles += cover
        stagedCover.copyTo(cover, overwrite = true)
        // 覆盖了磁盘上的封面，内存缓存必须失效，否则界面继续显示旧封面。
        CoverStore.invalidate(bookId)
        if (CoverStore.restoreCustomFlag(context, bookId, archivedCustom) && archivedCustom && !hadMarker) {
            createdFiles += marker
        }
        return true
    }

    /** 恢复结果文案：跳过时按“已存在 / 文件缺失 / 无法识别”分别给出数量，零项不显示。 */
    private fun restoreSummary(counts: RestoreCounts): String {
        val reasons = mutableListOf<String>()
        if (counts.existing > 0) reasons += "已存在 ${counts.existing}"
        if (counts.missingFiles > 0) reasons += "文件缺失 ${counts.missingFiles}"
        if (counts.unrecognized > 0) reasons += "无法识别 ${counts.unrecognized}"
        val skipped = if (reasons.isEmpty()) "" else {
            "，跳过 ${counts.skipped} 项（${reasons.joinToString(" / ")}）"
        }
        return "新增 ${counts.added} 项，合并 ${counts.merged} 项$skipped"
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
