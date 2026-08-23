package com.example.helloandroid

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Book(
    val id: Long,
    val title: String,
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val progress: Float,
    val addedAt: Long,
    val lastReadAt: Long,
    val folderId: Long = 0
)

data class NoteRow(val id: Long, val kind: String, val content: String)

/** 文件夹（多级分层）：parentId==0 表示根目录 */
data class Folder(val id: Long, val name: String, val parentId: Long)

class Db(context: Context) : SQLiteOpenHelper(context, "bookshelf.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                file_name TEXT NOT NULL UNIQUE,
                format TEXT NOT NULL DEFAULT 'txt',
                size_bytes INTEGER NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                last_read_at INTEGER NOT NULL DEFAULT 0,
                folder_id INTEGER NOT NULL DEFAULT 0,
                total_read_ms INTEGER NOT NULL DEFAULT 0)"""
        )
        createAux(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'txt'")
            createAux(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN last_read_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE books ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0")
            createAux(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE books ADD COLUMN total_read_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS read_log(
                day TEXT PRIMARY KEY,
                ms INTEGER NOT NULL DEFAULT 0)"""
            )
        }
    }

    private fun createAux(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS folders(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS read_log(
                day TEXT PRIMARY KEY,
                ms INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    fun insertBook(title: String, fileName: String, format: String, sizeBytes: Long): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title)
            put("file_name", fileName)
            put("format", format)
            put("size_bytes", sizeBytes)
            put("added_at", now)
            put("last_read_at", now)
        }
        return writableDatabase.insert("books", null, cv)
    }

    /** 打开阅读时刷新最近阅读时间 */
    fun markOpened(id: Long) {
        val cv = ContentValues().apply { put("last_read_at", System.currentTimeMillis()) }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    /** 备份恢复用：直接插入带元数据的书（不触发封面生成，文件需已存在于 filesDir） */
    fun restoreBook(title: String, fileName: String, format: String, sizeBytes: Long,
                    progress: Float, addedAt: Long, lastReadAt: Long, folderId: Long): Long {
        val cv = ContentValues().apply {
            put("title", title)
            put("file_name", fileName)
            put("format", format)
            put("size_bytes", sizeBytes)
            put("progress", progress)
            put("added_at", addedAt)
            put("last_read_at", lastReadAt)
            put("folder_id", folderId)
        }
        return writableDatabase.insert("books", null, cv)
    }

    /** 按标题+大小查书（恢复去重用） */
    fun findBook(title: String, sizeBytes: Long): Book? =
        readBooks("SELECT $cols FROM books WHERE title=? AND size_bytes=?", arrayOf(title, sizeBytes.toString())).firstOrNull()

    /** 累计阅读毫秒：进入阅读器 start，离开时 flush；顺带记录每日日志供统计 */
    fun addReadTime(id: Long, deltaMs: Long) {
        if (deltaMs <= 0) return
        writableDatabase.execSQL(
            "UPDATE books SET total_read_ms = total_read_ms + ? WHERE id=?",
            arrayOf<Any>(deltaMs, id)
        )
        try {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            writableDatabase.execSQL(
                "INSERT INTO read_log(day, ms) VALUES(?, ?) " +
                    "ON CONFLICT(day) DO UPDATE SET ms = ms + ?",
                arrayOf<Any>(day, deltaMs, deltaMs)
            )
        } catch (_: Exception) {}
    }

    fun totalReadMs(id: Long): Long =
        readableDatabase.rawQuery("SELECT total_read_ms FROM books WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** 全库总阅读毫秒 */
    fun totalAllReadMs(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(total_read_ms),0) FROM books", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** 近 n 天每日阅读毫秒（缺天补 0），返回 Pair(day, ms) 列表，旧→新 */
    fun dailyReadMs(n: Int): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val map = mutableMapOf<String, Long>()
        readableDatabase.rawQuery(
            "SELECT day, ms FROM read_log ORDER BY day DESC LIMIT ?", arrayOf(n.toString())
        ).use { c -> while (c.moveToNext()) map[c.getString(0)] = c.getLong(1) }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val cal = java.util.Calendar.getInstance()
        repeat(n) {
            val key = fmt.format(cal.time)
            out.add(0, key to (map[key] ?: 0L))
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    /** 有阅读记录的天数 */
    fun activeDays(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM read_log WHERE ms>0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    data class TopBook(val title: String, val ms: Long)

    /** 阅读时长 Top N 书籍 */
    fun topBooks(n: Int): List<TopBook> {
        val out = mutableListOf<TopBook>()
        readableDatabase.rawQuery(
            "SELECT title, total_read_ms FROM books WHERE total_read_ms>0 " +
                "ORDER BY total_read_ms DESC LIMIT ?", arrayOf(n.toString())
        ).use { c -> while (c.moveToNext()) out.add(TopBook(c.getString(0), c.getLong(1))) }
        return out
    }

    fun noteCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private val cols = "id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id"

    private fun readBooks(sql: String, args: Array<String>? = null): List<Book> {
        val out = mutableListOf<Book>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(Book(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getFloat(5), c.getLong(6), c.getLong(7), c.getLong(8)))
            }
        }
        return out
    }

    /** 当前文件夹内的书 */
    fun listBooks(folderId: Long = -1): List<Book> {
        return if (folderId < 0)
            readBooks("SELECT $cols FROM books ORDER BY last_read_at DESC, added_at DESC")
        else
            readBooks(
                "SELECT $cols FROM books WHERE folder_id=? ORDER BY last_read_at DESC, added_at DESC",
                arrayOf(folderId.toString())
            )
    }

    /** 全局搜索（跨文件夹） */
    fun searchBooks(q: String): List<Book> =
        readBooks(
            "SELECT $cols FROM books WHERE title LIKE ? ORDER BY last_read_at DESC, added_at DESC",
            arrayOf("%$q%")
        )

    fun getBook(id: Long): Book? =
        readBooks("SELECT $cols FROM books WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun updateProgress(id: Long, progress: Float) {
        val cv = ContentValues().apply { put("progress", progress) }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun moveBook(bookId: Long, folderId: Long) {
        val cv = ContentValues().apply { put("folder_id", folderId) }
        writableDatabase.update("books", cv, "id=?", arrayOf(bookId.toString()))
    }

    fun deleteBook(id: Long) {
        writableDatabase.delete("books", "id=?", arrayOf(id.toString()))
    }

    // ---------- 文件夹（多级分层） ----------

    fun addFolder(name: String, parentId: Long): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("parent_id", parentId)
        }
        return writableDatabase.insert("folders", null, cv)
    }

    fun renameFolder(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        writableDatabase.update("folders", cv, "id=?", arrayOf(id.toString()))
    }

    fun getFolder(id: Long): Folder? {
        if (id == 0L) return null
        var f: Folder? = null
        readableDatabase.rawQuery(
            "SELECT id,name,parent_id FROM folders WHERE id=?", arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) f = Folder(c.getLong(0), c.getString(1), c.getLong(2)) }
        return f
    }

    fun listFolders(parentId: Long): List<Folder> {
        val out = mutableListOf<Folder>()
        readableDatabase.rawQuery(
            "SELECT id,name,parent_id FROM folders WHERE parent_id=? ORDER BY name COLLATE NOCASE",
            arrayOf(parentId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Folder(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    /** 全部文件夹（移动目标树用） */
    fun allFolders(): List<Folder> {
        val out = mutableListOf<Folder>()
        readableDatabase.rawQuery("SELECT id,name,parent_id FROM folders ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(Folder(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    /** 面包屑路径：根 → … → id（含自身） */
    fun folderPath(id: Long): List<Folder> {
        val chain = mutableListOf<Folder>()
        var cur = id
        var guard = 0
        while (cur != 0L && guard++ < 32) {
            val f = getFolder(cur) ?: break
            chain.add(f)
            cur = f.parentId
        }
        return chain.reversed()
    }

    /** target 是否为 folderId 自身或其子孙（移动防环） */
    fun isSelfOrDescendant(folderId: Long, target: Long): Boolean {
        if (folderId == target) return true
        var cur = target
        var guard = 0
        while (cur != 0L && guard++ < 64) {
            val f = getFolder(cur) ?: return false
            cur = f.parentId
            if (cur == folderId) return true
        }
        return false
    }

    /** 移动文件夹到新父级（防环：不可移到自身或其子孙下） */
    fun moveFolderTo(id: Long, newParent: Long) {
        if (id == newParent || id == 0L) return
        if (isSelfOrDescendant(id, newParent)) return
        val cv = ContentValues().apply { put("parent_id", newParent) }
        writableDatabase.update("folders", cv, "id=?", arrayOf(id.toString()))
    }

    /** 删除文件夹：子文件夹提升到其父级，书归入其父级 */
    fun deleteFolder(id: Long) {
        val f = getFolder(id) ?: return
        val parent = f.parentId
        val db = writableDatabase
        db.execSQL("UPDATE folders SET parent_id=? WHERE parent_id=?", arrayOf(parent.toString(), id.toString()))
        db.execSQL("UPDATE books SET folder_id=? WHERE folder_id=?", arrayOf(parent.toString(), id.toString()))
        db.delete("folders", "id=?", arrayOf(id.toString()))
    }

    // ---------- settings / notes ----------

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String): String? {
        var v: String? = null
        readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key=?", arrayOf(key)
        ).use { c ->
            if (c.moveToFirst()) v = c.getString(0)
        }
        return v
    }

    fun addNote(bookId: Long, kind: String, content: String): Long {
        val cv = ContentValues().apply {
            put("book_id", bookId)
            put("kind", kind)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("notes", null, cv)
    }

    fun listNotes(bookId: Long, kind: String? = null): List<NoteRow> {
        val out = mutableListOf<NoteRow>()
        val sql = if (kind == null)
            "SELECT id,kind,content FROM notes WHERE book_id=? ORDER BY id DESC"
        else
            "SELECT id,kind,content FROM notes WHERE book_id=? AND kind=? ORDER BY id DESC"
        val args = if (kind == null) arrayOf(bookId.toString())
                   else arrayOf(bookId.toString(), kind)
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(NoteRow(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }
}
