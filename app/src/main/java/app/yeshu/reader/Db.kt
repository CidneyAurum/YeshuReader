package app.yeshu.reader

import android.content.Context
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.BookmarkEntity
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
    private fun NoteEntity.toDetail() = NoteDetail(id, bookId, kind, content, createdAt, anchor, status)
    private fun FolderEntity.toModel() = Folder(id, name, parentId)

    private fun AiArtifactEntity.toModel() = AiArtifact(
        id, bookId, kind, status, content, citationsJson, documentHash, model, promptVersion, createdAt, updatedAt
    )

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

    // ---------- 数据完整性自检（R74）----------

    /**
     * 自检结果。都是「不该存在但可能因异常退出或旧版本缺陷而残留」的状态。
     *
     * [orphanNotes] 不含 bookId = 0：那是全局阅读报告的哨兵值，本来就不属于任何一本书。
     */
    data class IntegrityReport(
        val orphanNotes: Int = 0,
        val danglingBookmarks: Int = 0,
        val invalidProgress: Int = 0,
        val danglingHighlights: Int = 0,
    ) {
        val clean: Boolean get() = orphanNotes == 0 && danglingBookmarks == 0 && invalidProgress == 0 && danglingHighlights == 0
    }

    fun integrityReport(): IntegrityReport {
        val bookIds = listBooks().map { it.id }.toSet()
        val notes = dao.allNotes()
        val orphanNotes = notes.count { it.bookId != 0L && it.bookId !in bookIds }
        val bookmarks = dao.listAllBookmarks()
        val danglingBookmarks = bookmarks.count { it.bookId !in bookIds }
        // 进度越界（<0 或 >1）会让进度条画到框外、也让「读完」判定失真。
        val invalidProgress = listBooks().count { it.progress < 0f || it.progress > 1f }
        // 划重点必须带锚点，否则笔记列表里跳不回原文，等于一条死记录。
        val danglingHighlights = notes.count { it.kind == "highlight" && it.anchor.isBlank() }
        return IntegrityReport(orphanNotes, danglingBookmarks, invalidProgress, danglingHighlights)
    }

    /**
     * 修复自检发现的问题。
     *
     * 只做无争议的清理：越界进度夹回 [0,1]，孤儿笔记与悬空书签归到「全局」（bookId = 0）
     * 而不是删除——用户写下的内容不该被一次自检悄悄丢掉。
     */
    fun repairIntegrity(): String {
        val before = integrityReport()
        if (before.clean) return "没有发现需要修复的问题"
        val bookIds = listBooks().map { it.id }.toSet()
        var fixedNotes = 0
        var fixedBookmarks = 0
        var fixedProgress = 0
        dao.allNotes().forEach { note ->
            if (note.bookId != 0L && note.bookId !in bookIds) {
                dao.updateNote(note.copy(bookId = 0L)); fixedNotes++
            }
        }
        dao.listAllBookmarks().forEach { mark ->
            if (mark.bookId !in bookIds) {
                dao.updateBookmark(mark.copy(bookId = 0L)); fixedBookmarks++
            }
        }
        listBooks().forEach { book ->
            if (book.progress < 0f || book.progress > 1f) {
                dao.updateBookProgress(book.id, book.progress.coerceIn(0f, 1f), statusFor(book.progress.coerceIn(0f, 1f))); fixedProgress++
            }
        }
        return buildString {
            if (fixedNotes > 0) append("归置孤儿笔记 $fixedNotes 条；")
            if (fixedBookmarks > 0) append("归置悬空书签 $fixedBookmarks 条；")
            if (fixedProgress > 0) append("修正越界进度 $fixedProgress 本；")
            if (isEmpty()) append("已检查，没有需要修复的问题")
        }.trimEnd('；')
    }

    /** 今天的阅读时长（毫秒）。阅读目标与「今日已读」提示都用它。 */
    fun todayReadMs(): Long {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(java.util.Date())
        return dao.recentReadLog(1).firstOrNull { it.day == today }?.ms ?: 0L
    }

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

    /** 阅读时长最长的一天；没有任何记录时返回 null。 */
    fun longestDay(): Pair<String, Long>? =
        dao.allReadLogs().filter { it.ms > 0 }.maxByOrNull { it.ms }?.let { it.day to it.ms }

    /**
     * 恢复备份后校正日累计。
     *
     * `mergeReadLog` 与 `mergeTotalReadMs` 各自只取较大值，两条独立策略叠加后
     * 会出现「近 7 天合计 > 全部阅读时长」这种自相矛盾的统计。
     * 这里把超出的部分按天等比回缩到总量以内，保证 `sum(read_log) <= total`。
     */
    fun reconcileReadTotals() {
        room.runInTransaction {
            val total = dao.totalAllReadMs()
            val logs = dao.allReadLogs()
            val sum = logs.sumOf { it.ms }
            if (sum <= 0 || sum <= total) return@runInTransaction
            val scale = total.toDouble() / sum.toDouble()
            logs.forEach { row -> dao.setReadLog(row.day, (row.ms * scale).toLong()) }
        }
    }
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
            dao.deleteBookmarksForBook(id)
            dao.purgeBook(id)
        }
    }

    // ---------- 书签 ----------

    data class Bookmark(
        val id: Long,
        val bookId: Long,
        val anchor: String,
        val label: String,
        val excerpt: String,
        val createdAt: Long
    )

    private fun BookmarkEntity.toModel() = Bookmark(id, bookId, anchor, label, excerpt, createdAt)

    /**
     * 新建书签。同一位置重复添加会先删旧条目，避免用户连点后列表里出现一串相同位置。
     * 返回是否真的写入了新书签。
     */
    fun addBookmark(bookId: Long, anchor: String, label: String, excerpt: String = ""): Boolean {
        if (bookId <= 0 || anchor.isBlank()) return false
        dao.listBookmarks(bookId).firstOrNull { it.anchor == anchor }?.let { dao.deleteBookmark(it.id) }
        return dao.addBookmark(
            BookmarkEntity(
                bookId = bookId,
                anchor = anchor,
                label = label.trim(),
                excerpt = excerpt.trim().take(80),
                createdAt = System.currentTimeMillis()
            )
        ) > 0
    }

    fun listBookmarks(bookId: Long): List<Bookmark> = dao.listBookmarks(bookId).map { it.toModel() }

    fun recentBookmarks(limit: Int = 200): List<Bookmark> = dao.recentBookmarks(limit).map { it.toModel() }

    fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    /** 当前位置是否已有书签，供阅读器切换「加书签 / 取消书签」。 */
    fun hasBookmark(bookId: Long, anchor: String): Boolean =
        anchor.isNotBlank() && dao.listBookmarks(bookId).any { it.anchor == anchor }

    // ---------- 笔记内容编辑 ----------

    /** 修改一条笔记的正文（编辑 AI 结果或手改错别字），不动 kind/anchor/createdAt。 */
    fun updateNoteContent(id: Long, content: String) {
        if (id <= 0) return
        dao.updateNoteContent(id, content)
    }

    /** 同步更新 ai_artifacts 的正文，保证「笔记」与「AI 成果」两处一致。 */
    fun updateArtifactContent(bookId: Long, kind: String, oldContent: String, newContent: String) {
        dao.listArtifacts(bookId).firstOrNull { it.kind == kind && it.content == oldContent }?.let { artifact ->
            dao.updateArtifactContent(artifact.id, newContent, System.currentTimeMillis())
        }
    }

    /**
     * 用户手动编辑 AI 结果后落库。
     *
     * 结果可能已经自动落成笔记（kind 非空），也可能只存在于缓存（命中缓存时不落笔记）。
     * 因此：能按旧正文匹配到笔记就改它并标 edited；匹配不到就补一条，避免用户改完却看不到。
     * ai_artifacts 同步改，否则「笔记」与「AI 成果」会显示两份不同的内容。
     */
    fun applyEditedContent(bookId: Long, kind: String, oldContent: String, newContent: String) {
        if (newContent.isBlank()) return
        room.runInTransaction {
            val matched = dao.listNotes(bookId, kind).filter { it.content == oldContent }
            if (matched.isEmpty()) {
                dao.addNote(
                    NoteEntity(bookId = bookId, kind = kind, content = newContent, createdAt = System.currentTimeMillis())
                )
            } else {
                matched.forEach { note ->
                    dao.updateNoteContent(note.id, newContent)
                    dao.updateNoteStatus(note.id, NOTE_STATUS_EDITED)
                }
            }
            updateArtifactContent(bookId, kind, oldContent, newContent)
        }
    }

    /** 手动编辑过的笔记会带上这个状态，界面据此显示「已手动编辑」，避免被当成模型原文。 */
    fun editedStatus(): String = NOTE_STATUS_EDITED

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

    /**
     * 一个配置的 Key 真实状态（未保存/可用/来源不符/解不开）。
     * 界面必须用它而不是「密文是否存在」，否则 Keystore 失效后会假报「已安全保存」。
     */
    fun aiKeyState(profileId: String, baseUrl: String): app.yeshu.reader.security.KeyState {
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        return SecureKeyStore(appContext).profileKeyState(profileId, origin)
    }

    fun setAiKey(profileId: String, value: String, baseUrl: String) {
        val origin = runCatching { AiClient.endpointOrigin(baseUrl) }.getOrDefault("")
        SecureKeyStore(appContext).writeProfileApiKey(profileId, value.trim(), origin)
        dao.deleteSetting("ai_key")
    }

    fun removeAiKey(profileId: String) = SecureKeyStore(appContext).removeProfileApiKey(profileId)

    /**
     * 笔记详情：比 [NoteRow] 多带出处锚点与校验状态，供笔记中枢的详情面板使用。
     * 列表仍用 [NoteRow]，避免每个列表项都携带锚点解析的负担。
     */
    data class NoteDetail(
        val id: Long,
        val bookId: Long,
        val kind: String,
        val content: String,
        val createdAt: Long,
        val anchor: String,
        val status: String
    )

    fun addNote(
        bookId: Long,
        kind: String,
        content: String,
        id: Long = 0L,
        createdAt: Long = System.currentTimeMillis(),
        anchor: String = "",
        status: String = ""
    ): Long = dao.addNote(
        NoteEntity(
            id = id,
            bookId = bookId,
            kind = kind,
            content = content,
            createdAt = createdAt,
            anchor = anchor,
            status = status
        )
    )

    fun listNotes(bookId: Long, kind: String? = null): List<NoteRow> =
        (if (kind == null) dao.listNotes(bookId) else dao.listNotes(bookId, kind)).map { it.toModel() }

    /** 按 kind 取笔记详情（带 anchor/status）：重点的颜色与位置都存在这两列里。 */
    /** 某本书的全部笔记（含 anchor 与 status）。导出划线时需要这两个字段。 */
    fun allNoteDetails(bookId: Long): List<NoteDetail> = dao.listNotes(bookId).map { it.toDetail() }

    fun listNoteDetails(bookId: Long, kind: String): List<NoteDetail> =
        dao.listNotes(bookId, kind).map { it.toDetail() }

    /** 笔记中枢列表：带出处锚点，便于卡片上直接显示来源位置。 */
    fun recentNoteDetails(limit: Int = 80): List<NoteDetail> = dao.recentNotes(limit).map { it.toDetail() }

    /**
     * 按 id 取一条笔记的完整内容与锚点。DAO 没有单条查询，但笔记表规模有限且详情面板
     * 只在用户点按时打开一次，整表扫描一次可以接受。
     */
    fun getNoteDetail(id: Long): NoteDetail? = dao.allNotes().firstOrNull { it.id == id }?.toDetail()

    fun recentNotes(limit: Int = 20): List<NoteRow> = dao.recentNotes(limit).map { it.toModel() }
    fun deleteNote(id: Long) = dao.deleteNote(id)

    /**
     * 删除笔记并清理它对应的 AI 成果：ai_artifacts 没有外键级联，只删笔记会让成果
     * 永久留在库里却再无入口（生成时笔记与成果写的是同一份校验后正文，按内容即可配对）。
     * 返回被一并删除的成果，供调用方在“撤销”时原样恢复。
     */
    fun deleteNoteWithArtifact(id: Long): AiArtifact? {
        var removed: AiArtifact? = null
        room.runInTransaction {
            val note = dao.allNotes().firstOrNull { it.id == id }
            if (note != null) {
                val artifact = dao.listArtifacts(note.bookId).firstOrNull {
                    it.kind == note.kind && it.content == note.content
                }
                if (artifact != null) {
                    removed = artifact.toModel()
                    deleteArtifact(artifact.id)
                }
            }
            dao.deleteNote(id)
        }
        return removed
    }

    /**
     * 把只存在于缓存里的成果补一条笔记：命中 ai_artifacts 缓存时不会重新生成，也就不会
     * 落笔记，用户因此看不到这次的结果。内容相同即视为已有笔记，不重复堆积。
     */
    fun ensureNoteForArtifact(artifact: AiArtifact): Long {
        if (artifact.content.isBlank()) return 0L
        val existing = dao.listNotes(artifact.bookId).firstOrNull {
            it.kind == artifact.kind && it.content == artifact.content
        }
        if (existing != null) return existing.id
        return dao.addNote(
            NoteEntity(
                bookId = artifact.bookId,
                kind = artifact.kind,
                content = artifact.content,
                createdAt = artifact.updatedAt
            )
        )
    }

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

    fun listArtifacts(bookId: Long): List<AiArtifact> = dao.listArtifacts(bookId).map { it.toModel() }

    /** 笔记中枢的“AI 成果”区按全局范围读取：成果的归属书可能已被删除。 */
    fun listAllArtifacts(): List<AiArtifact> = dao.allArtifacts().map { it.toModel() }

    /**
     * 按 id 删除单条 AI 成果。DAO 只提供按书删除，这里直接用底层连接执行，
     * 避免为了删一条成果而清掉同一本书的其他成果。
     */
    fun deleteArtifact(id: Long) {
        if (id <= 0) return
        room.openHelper.writableDatabase.execSQL("DELETE FROM ai_artifacts WHERE id = ?", arrayOf(id))
    }

    companion object {
        /** 用户手动编辑过内容的状态标记。 */
        const val NOTE_STATUS_EDITED = "edited"

        private fun statusFor(progress: Float): String = when {
            progress >= 0.99f -> "done"
            progress > 0.005f -> "reading"
            else -> "unread"
        }
    }
}
