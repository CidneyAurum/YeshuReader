package app.yeshu.reader

import android.content.Context
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.FolderEntity
import app.yeshu.reader.data.LibraryItemEntity
import app.yeshu.reader.data.NoteEntity
import app.yeshu.reader.data.ReadLogEntity
import app.yeshu.reader.data.SettingEntity
import app.yeshu.reader.data.YeshuDatabase
import app.yeshu.reader.security.SecureKeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Compatibility facade over Room. Legacy Views can keep their synchronous API while the
 * Compose screens use the same single source of truth. New code should keep database work
 * off the main thread; allowMainThreadQueries exists only for the gradual migration window.
 */
class Db(context: Context) {
    private val appContext = context.applicationContext
    private val room = YeshuDatabase.get(appContext)
    private val dao = room.dao()

    private fun LibraryItemEntity.toModel() = LibraryItem(
        id = id,
        title = title,
        fileName = fileName,
        format = format,
        sizeBytes = sizeBytes,
        progress = progress,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        folderId = folderId,
        author = author,
        itemType = itemType,
        status = status,
        favorite = favorite,
        tags = tags,
        contentHash = contentHash,
        totalReadMs = totalReadMs
    )

    private fun NoteEntity.toModel() = NoteRow(id, kind, content, bookId, createdAt)
    private fun FolderEntity.toModel() = Folder(id, name, parentId)

    fun insertBook(
        title: String,
        fileName: String,
        format: String,
        sizeBytes: Long,
        contentHash: String = ""
    ): Long {
        val now = System.currentTimeMillis()
        val type = if (format.lowercase() in setOf("txt", "md", "markdown", "epub")) "book" else "document"
        return dao.insertBook(
            LibraryItemEntity(
                title = title,
                fileName = fileName,
                format = format,
                sizeBytes = sizeBytes,
                addedAt = now,
                lastReadAt = now,
                itemType = type,
                contentHash = contentHash
            )
        )
    }

    fun markOpened(id: Long) = dao.markOpened(id, System.currentTimeMillis())

    fun restoreBook(
        title: String,
        fileName: String,
        format: String,
        sizeBytes: Long,
        progress: Float,
        addedAt: Long,
        lastReadAt: Long,
        folderId: Long,
        author: String = "",
        itemType: String = if (format.lowercase() in setOf("txt", "md", "markdown", "epub")) "book" else "document",
        status: String = statusFor(progress),
        favorite: Boolean = false,
        tags: String = "",
        contentHash: String = "",
        totalReadMs: Long = 0
    ): Long = dao.insertBook(
        LibraryItemEntity(
            title = title,
            fileName = fileName,
            format = format,
            sizeBytes = sizeBytes,
            progress = progress,
            addedAt = addedAt,
            lastReadAt = lastReadAt,
            folderId = folderId,
            author = author,
            itemType = itemType,
            status = status,
            favorite = favorite,
            tags = tags,
            contentHash = contentHash,
            totalReadMs = totalReadMs
        )
    )

    fun findBook(title: String, sizeBytes: Long): Book? = dao.findBook(title, sizeBytes)?.toModel()

    /** 导入去重的命中结果；[fromRecycleBin] 为真时调用方应先恢复文件再调用 [restoreDeletedBook]。 */
    data class ImportMatch(val book: Book, val fromRecycleBin: Boolean)

    /**
     * 导入去重：按内容哈希查找，包含回收站里的条目。[listBooks] / [findBook] 都过滤了
     * `deleted_at = 0`，所以只看它们会把回收站中的同一份文档重新导入成第二条记录、磁盘上
     * 再多出一份文件。命中已软删除的记录时应当恢复它（[deleteBook] 只写 deleted_at，
     * [restoreDeletedBook] 把它清回 0 即可逆），从而复用原有的进度、笔记与 AI 结果。
     */
    fun findImportMatch(contentHash: String): ImportMatch? {
        val existing = dao.findAnyBookByContentHash(contentHash) ?: return null
        return ImportMatch(existing.toModel(), existing.deletedAt > 0)
    }

    fun addReadTime(id: Long, deltaMs: Long) {
        if (deltaMs <= 0) return
        room.runInTransaction {
            // 书目被删除或清理后 UPDATE 会影响 0 行，此时不能再累加 read_log：
            // 否则“近 7 天阅读时长”会超过书目累计时长，统计页出现日总量大于总量。
            if (dao.addReadTime(id, deltaMs) > 0) {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                dao.insertReadLog(ReadLogEntity(day, 0))
                dao.incrementReadLog(day, deltaMs)
            }
        }
    }

    fun totalReadMs(id: Long): Long = dao.totalReadMs(id) ?: 0L
    fun totalAllReadMs(): Long = dao.totalAllReadMs()

    fun dailyReadMs(n: Int): List<Pair<String, Long>> {
        val values = dao.recentReadLog(n).associate { it.day to it.ms }
        val out = mutableListOf<Pair<String, Long>>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        repeat(n) {
            val key = fmt.format(cal.time)
            out.add(0, key to (values[key] ?: 0L))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    fun activeDays(): Int = dao.activeDays()
    data class TopBook(val title: String, val ms: Long)
    fun topBooks(n: Int): List<TopBook> = dao.topBooks(n).map { TopBook(it.title, it.totalReadMs) }
    fun noteCount(): Int = dao.noteCount()

    fun listBooks(folderId: Long = -1): List<Book> =
        (if (folderId < 0) dao.listBooks() else dao.listBooks(folderId)).map { it.toModel() }

    fun searchBooks(q: String): List<Book> = dao.searchBooks(q.trim()).map { it.toModel() }
    fun getBook(id: Long): Book? = dao.getBook(id)?.toModel()

    fun updateProgress(id: Long, progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        dao.updateProgress(id, value, statusFor(value))
    }

    fun moveBook(bookId: Long, folderId: Long) = dao.moveBook(bookId, folderId)
    fun setFavorite(bookId: Long, favorite: Boolean) = dao.setFavorite(bookId, favorite)
    fun updateMetadata(bookId: Long, author: String, tags: String) = dao.updateMetadata(bookId, author.trim(), tags.trim())

    /** Move to the in-app recycle bin; the original file is retained until purge. */
    fun deleteBook(id: Long) = dao.softDeleteBook(id, System.currentTimeMillis())
    fun listDeletedBooks(): List<Book> = dao.listDeletedBooks().map { it.toModel() }
    fun restoreDeletedBook(id: Long) = dao.restoreDeletedBook(id)
    fun purgeBook(id: Long) {
        room.runInTransaction {
            dao.deleteNotesForBook(id)
            dao.deleteArtifactsForBook(id)
            dao.purgeBook(id)
        }
    }

    fun addFolder(name: String, parentId: Long): Long = dao.insertFolder(FolderEntity(name = name.trim(), parentId = parentId))
    fun renameFolder(id: Long, name: String) = dao.renameFolder(id, name.trim())
    fun getFolder(id: Long): Folder? = if (id == 0L) null else dao.getFolder(id)?.toModel()
    fun listFolders(parentId: Long): List<Folder> = dao.listFolders(parentId).map { it.toModel() }
    fun allFolders(): List<Folder> = dao.allFolders().map { it.toModel() }

    fun folderPath(id: Long): List<Folder> {
        val chain = mutableListOf<Folder>()
        var current = id
        var guard = 0
        while (current != 0L && guard++ < 32) {
            val folder = getFolder(current) ?: break
            chain += folder
            current = folder.parentId
        }
        return chain.reversed()
    }

    fun isSelfOrDescendant(folderId: Long, target: Long): Boolean {
        if (folderId == target) return true
        var current = target
        var guard = 0
        while (current != 0L && guard++ < 64) {
            current = getFolder(current)?.parentId ?: return false
            if (current == folderId) return true
        }
        return false
    }

    fun moveFolderTo(id: Long, newParent: Long) {
        if (id == 0L || id == newParent || isSelfOrDescendant(id, newParent)) return
        dao.moveFolder(id, newParent)
    }

    fun deleteFolder(id: Long) {
        val folder = getFolder(id) ?: return
        room.runInTransaction {
            dao.liftChildFolders(id, folder.parentId)
            dao.liftBooks(id, folder.parentId)
            dao.deleteFolder(id)
        }
    }

    fun setSetting(key: String, value: String) = dao.setSetting(SettingEntity(key, value))
    fun getSetting(key: String): String? = dao.getSetting(key)
    fun deleteSetting(key: String) = dao.deleteSetting(key)

    /**
     * One-time migration from the legacy plaintext settings row into Android Keystore.
     * 只要“当前来源还没有可用密钥”就把明文迁移进 Keystore：先写入、确认写入成功后再删除明文行，
     * 因此不会因为其他来源已有绑定密钥而丢掉旧密钥，重复调用也是幂等的；返回值取迁移后的结果。
     */
    fun getAiKey(baseUrl: String = dao.getSetting("ai_base_url").orEmpty()): String {
        val secure = SecureKeyStore(appContext)
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        val legacy = dao.getSetting("ai_key").orEmpty()
        var stored = secure.readApiKey(origin)
        if (legacy.isNotBlank() && stored.isBlank()) {
            val migrated = if (origin.isNotBlank()) {
                // 旧明文没有记录来源；有可用服务地址时直接绑定，写入成功后即可使用。
                runCatching { secure.writeApiKey(legacy, origin) }.isSuccess &&
                    secure.readApiKey(origin).isNotBlank()
            } else {
                // 缺少可用服务地址：保留为待绑定密钥。已有绑定密钥时绝不覆盖，也不删除明文。
                !secure.hasApiKey() &&
                    runCatching { secure.writeUnboundApiKey(legacy) }.isSuccess &&
                    secure.hasUnboundApiKey()
            }
            if (migrated) {
                stored = secure.readApiKey(origin)
                dao.deleteSetting("ai_key")
            }
        }
        return stored
    }

    fun hasUnboundAiKey(): Boolean = SecureKeyStore(appContext).hasUnboundApiKey()

    fun bindUnboundAiKey(baseUrl: String): Boolean {
        val origin = AiClient.endpointOrigin(baseUrl)
        return SecureKeyStore(appContext).bindUnboundApiKey(origin)
    }

    fun setAiKey(value: String, baseUrl: String = dao.getSetting("ai_base_url").orEmpty()) {
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        SecureKeyStore(appContext).writeApiKey(value.trim(), origin)
        dao.deleteSetting("ai_key")
    }

    /** Profile-scoped Key storage allows several providers, including two on the same host. */
    fun getAiKey(profileId: String, baseUrl: String): String {
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        return SecureKeyStore(appContext).readProfileApiKey(profileId, origin)
    }

    fun hasAiKey(profileId: String): Boolean = SecureKeyStore(appContext).hasProfileApiKey(profileId)

    fun setAiKey(profileId: String, value: String, baseUrl: String) {
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        SecureKeyStore(appContext).writeProfileApiKey(profileId, value.trim(), origin)
        dao.deleteSetting("ai_key")
    }

    fun removeAiKey(profileId: String) = SecureKeyStore(appContext).removeProfileApiKey(profileId)

    fun addNote(
        bookId: Long,
        kind: String,
        content: String,
        id: Long = 0L,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = dao.addNote(
        NoteEntity(id = id, bookId = bookId, kind = kind, content = content, createdAt = createdAt)
    )

    fun listNotes(bookId: Long, kind: String? = null): List<NoteRow> =
        (if (kind == null) dao.listNotes(bookId) else dao.listNotes(bookId, kind)).map { it.toModel() }

    fun recentNotes(limit: Int = 20): List<NoteRow> = dao.recentNotes(limit).map { it.toModel() }
    fun deleteNote(id: Long) = dao.deleteNote(id)

    fun saveArtifact(artifact: AiArtifact): Long = dao.saveArtifact(
        AiArtifactEntity(
            id = artifact.id,
            bookId = artifact.bookId,
            kind = artifact.kind,
            status = artifact.status,
            content = artifact.content,
            citationsJson = artifact.citationsJson,
            documentHash = artifact.documentHash,
            model = artifact.model,
            promptVersion = artifact.promptVersion,
            createdAt = artifact.createdAt,
            updatedAt = artifact.updatedAt
        )
    )

    fun listArtifacts(bookId: Long): List<AiArtifact> = dao.listArtifacts(bookId).map {
        AiArtifact(it.id, it.bookId, it.kind, it.status, it.content, it.citationsJson, it.documentHash, it.model, it.promptVersion, it.createdAt, it.updatedAt)
    }

    companion object {
        private fun statusFor(progress: Float): String = when {
            progress >= 0.99f -> "done"
            progress > 0.005f -> "reading"
            else -> "unread"
        }
    }
}
