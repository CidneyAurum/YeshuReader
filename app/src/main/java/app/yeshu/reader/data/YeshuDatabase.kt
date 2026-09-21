package app.yeshu.reader.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "books", indices = [Index(value = ["file_name"], unique = true)])
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val format: String = "txt",
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val progress: Float = 0f,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long = 0,
    @ColumnInfo(name = "folder_id") val folderId: Long = 0,
    @ColumnInfo(name = "total_read_ms") val totalReadMs: Long = 0,
    val author: String = "",
    @ColumnInfo(name = "item_type") val itemType: String = "book",
    val status: String = "unread",
    val favorite: Boolean = false,
    val tags: String = "",
    @ColumnInfo(name = "content_hash") val contentHash: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: Long = 0
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val kind: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 出处锚点，如 CHAPTER:3 / PAGE:12 / PARAGRAPH:40；空表示没有可定位的来源。 */
    @ColumnInfo(defaultValue = "''") val anchor: String = "",
    /** "" 正常；"unvalidated" 表示模型输出未通过校验但用户选择保留。 */
    @ColumnInfo(defaultValue = "''") val status: String = ""
)

/**
 * 书签：只记录「位置」，与笔记/摘录分开。
 *
 * anchor 复用笔记的锚点语法（CHAPTER:3 / PAGE:12 / PARAGRAPH:40 / IMAGE:5），
 * 因此跳转可以完全复用 ReaderView 已有的锚点定位逻辑，不必再写一套映射。
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val anchor: String,
    /** 人类可读的位置标签，如「第 3 章 · 第 12 段」。 */
    val label: String,
    /** 该位置首行摘录，便于在列表里确认是不是想找的那一处。 */
    val excerpt: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long = 0
)

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "read_log")
data class ReadLogEntity(@PrimaryKey val day: String, val ms: Long = 0)

@Entity(tableName = "ai_artifacts")
data class AiArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val kind: String,
    val status: String,
    val content: String,
    @ColumnInfo(name = "citations_json") val citationsJson: String = "[]",
    @ColumnInfo(name = "document_hash") val documentHash: String,
    val model: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface YeshuDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertBook(item: LibraryItemEntity): Long

    @Query("SELECT * FROM books WHERE deleted_at=0 ORDER BY last_read_at DESC, added_at DESC")
    fun listBooks(): List<LibraryItemEntity>

    @Query("SELECT * FROM books WHERE deleted_at=0 AND folder_id=:folderId ORDER BY last_read_at DESC, added_at DESC")
    fun listBooks(folderId: Long): List<LibraryItemEntity>

    @Query("SELECT * FROM books WHERE deleted_at=0 AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY last_read_at DESC, added_at DESC")
    fun searchBooks(query: String): List<LibraryItemEntity>

    @Query("SELECT * FROM books WHERE id=:id AND deleted_at=0 LIMIT 1")
    fun getBook(id: Long): LibraryItemEntity?

    @Query("SELECT * FROM books WHERE deleted_at=0 AND title=:title AND size_bytes=:sizeBytes LIMIT 1")
    fun findBook(title: String, sizeBytes: Long): LibraryItemEntity?

    @Query("SELECT * FROM books WHERE deleted_at=0 AND lower(content_hash)=lower(:contentHash) LIMIT 1")
    fun findBookByContentHash(contentHash: String): LibraryItemEntity?

    /** 导入去重专用：不过滤 deleted_at，未删除的条目优先（deleted_at=0 排在最前）。 */
    @Query("SELECT * FROM books WHERE lower(content_hash)=lower(:contentHash) ORDER BY deleted_at ASC, id DESC LIMIT 1")
    fun findAnyBookByContentHash(contentHash: String): LibraryItemEntity?

    @Query("UPDATE books SET last_read_at=:openedAt WHERE id=:id")
    fun markOpened(id: Long, openedAt: Long)

    @Query("UPDATE books SET progress=:progress, status=:status WHERE id=:id")
    fun updateProgress(id: Long, progress: Float, status: String)

    @Query("UPDATE books SET folder_id=:folderId WHERE id=:id")
    fun moveBook(id: Long, folderId: Long)

    @Query("UPDATE books SET favorite=:favorite WHERE id=:id")
    fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE books SET author=:author, tags=:tags WHERE id=:id")
    fun updateMetadata(id: Long, author: String, tags: String)

    @Query("UPDATE books SET deleted_at=:deletedAt WHERE id=:id")
    fun softDeleteBook(id: Long, deletedAt: Long)

    @Query("UPDATE books SET deleted_at=0 WHERE id=:id")
    fun restoreDeletedBook(id: Long)

    @Query("SELECT * FROM books WHERE deleted_at>0 ORDER BY deleted_at DESC")
    fun listDeletedBooks(): List<LibraryItemEntity>

    @Query("DELETE FROM books WHERE id=:id")
    fun purgeBook(id: Long)

    @Query("DELETE FROM notes WHERE book_id=:bookId")
    fun deleteNotesForBook(bookId: Long)

    @Insert
    fun addBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE book_id=:bookId ORDER BY created_at DESC")
    fun listBookmarks(bookId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC LIMIT :limit")
    fun recentBookmarks(limit: Int): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE id=:id")
    fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE book_id=:bookId")
    fun deleteBookmarksForBook(bookId: Long)

    @Query("DELETE FROM ai_artifacts WHERE book_id=:bookId")
    fun deleteArtifactsForBook(bookId: Long)

    @Query("UPDATE books SET total_read_ms=total_read_ms+:delta WHERE id=:id")
    fun addReadTime(id: Long, delta: Long): Int

    @Query("SELECT total_read_ms FROM books WHERE id=:id")
    fun totalReadMs(id: Long): Long?

    @Query("UPDATE books SET total_read_ms=CASE WHEN total_read_ms<:totalReadMs THEN :totalReadMs ELSE total_read_ms END WHERE id=:id")
    fun mergeTotalReadMs(id: Long, totalReadMs: Long)

    @Query("SELECT COALESCE(SUM(total_read_ms),0) FROM books WHERE deleted_at=0")
    fun totalAllReadMs(): Long

    @Query("SELECT title, total_read_ms FROM books WHERE deleted_at=0 AND total_read_ms>0 ORDER BY total_read_ms DESC LIMIT :limit")
    fun topBooks(limit: Int): List<TopBookRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertReadLog(row: ReadLogEntity)

    @Query("UPDATE read_log SET ms=ms+:delta WHERE day=:day")
    fun incrementReadLog(day: String, delta: Long)

    @Query("SELECT * FROM read_log ORDER BY day DESC LIMIT :limit")
    fun recentReadLog(limit: Int): List<ReadLogEntity>

    @Query("SELECT * FROM read_log ORDER BY day")
    fun allReadLogs(): List<ReadLogEntity>

    @Query("UPDATE read_log SET ms=CASE WHEN ms<:ms THEN :ms ELSE ms END WHERE day=:day")
    fun mergeReadLog(day: String, ms: Long)

    @Query("UPDATE read_log SET ms=:ms WHERE day=:day")
    fun setReadLog(day: String, ms: Long)

    @Query("SELECT COUNT(*) FROM read_log WHERE ms>0")
    fun activeDays(): Int

    @Insert
    fun insertFolder(folder: FolderEntity): Long

    @Query("UPDATE folders SET name=:name WHERE id=:id")
    fun renameFolder(id: Long, name: String)

    @Query("SELECT * FROM folders WHERE id=:id LIMIT 1")
    fun getFolder(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE parent_id=:parentId ORDER BY name COLLATE NOCASE")
    fun listFolders(parentId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun allFolders(): List<FolderEntity>

    @Query("UPDATE folders SET parent_id=:newParent WHERE id=:id")
    fun moveFolder(id: Long, newParent: Long)

    @Query("UPDATE folders SET parent_id=:parent WHERE parent_id=:id")
    fun liftChildFolders(id: Long, parent: Long)

    @Query("UPDATE books SET folder_id=:parent WHERE folder_id=:id")
    fun liftBooks(id: Long, parent: Long)

    @Query("DELETE FROM folders WHERE id=:id")
    fun deleteFolder(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setSetting(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key=:key LIMIT 1")
    fun getSetting(key: String): String?

    @Query("SELECT * FROM settings ORDER BY key")
    fun allSettings(): List<SettingEntity>

    @Query("DELETE FROM settings WHERE key=:key")
    fun deleteSetting(key: String)

    @Insert
    fun addNote(note: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE book_id=:bookId ORDER BY id DESC")
    fun listNotes(bookId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE book_id=:bookId AND kind=:kind ORDER BY id DESC")
    fun listNotes(bookId: Long, kind: String): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY created_at DESC LIMIT :limit")
    fun recentNotes(limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY id")
    fun allNotes(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes")
    fun noteCount(): Int

    @Query("DELETE FROM notes WHERE id=:id")
    fun deleteNote(id: Long)

    @Query("UPDATE notes SET content=:content WHERE id=:id")
    fun updateNoteContent(id: Long, content: String)

    @Query("UPDATE notes SET status=:status WHERE id=:id")
    fun updateNoteStatus(id: Long, status: String)

    @Query("UPDATE ai_artifacts SET content=:content, updated_at=:updatedAt WHERE id=:id")
    fun updateArtifactContent(id: Long, content: String, updatedAt: Long)

    @Query("SELECT id FROM notes WHERE book_id=:bookId AND kind=:kind AND content=:content AND created_at=:createdAt LIMIT 1")
    fun findNote(bookId: Long, kind: String, content: String, createdAt: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveArtifact(artifact: AiArtifactEntity): Long

    @Query("SELECT * FROM ai_artifacts WHERE book_id=:bookId ORDER BY updated_at DESC")
    fun listArtifacts(bookId: Long): List<AiArtifactEntity>

    @Query("SELECT * FROM ai_artifacts ORDER BY id")
    fun allArtifacts(): List<AiArtifactEntity>

    @Query("SELECT id FROM ai_artifacts WHERE book_id=:bookId AND kind=:kind AND lower(document_hash)=lower(:documentHash) AND model=:model AND prompt_version=:promptVersion LIMIT 1")
    fun findArtifact(bookId: Long, kind: String, documentHash: String, model: String, promptVersion: Int): Long?

    @Query("SELECT * FROM ai_artifacts WHERE book_id=:bookId AND kind=:kind AND lower(document_hash)=lower(:documentHash) AND model=:model AND prompt_version=:promptVersion LIMIT 1")
    fun findArtifactEntity(bookId: Long, kind: String, documentHash: String, model: String, promptVersion: Int): AiArtifactEntity?
}

data class TopBookRow(
    val title: String,
    @ColumnInfo(name = "total_read_ms") val totalReadMs: Long
)

@Database(
    entities = [
        LibraryItemEntity::class,
        NoteEntity::class,
        FolderEntity::class,
        SettingEntity::class,
        ReadLogEntity::class,
        AiArtifactEntity::class,
        BookmarkEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class YeshuDatabase : RoomDatabase() {
    abstract fun dao(): YeshuDao

    companion object {
        @Volatile private var instance: YeshuDatabase? = null

        fun get(context: Context): YeshuDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                YeshuDatabase::class.java,
                "bookshelf.db"
            )
                .addMigrations(
                    MIGRATION_1_7,
                    MIGRATION_2_7,
                    MIGRATION_3_7,
                    MIGRATION_4_7,
                    MIGRATION_5_7,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9
                )
                .allowMainThreadQueries()
                .build()
                .also { instance = it }
        }

        // 旧版 SQLiteOpenHelper 的表结构按版本逐步 ALTER 而来，任何 1~6 的旧库都统一重建为
        // Room v7 结构，避免停留在 v1~v5 的安装在启动时抛 "migration was required but not found"。
        val MIGRATION_1_7 = legacyMigration(1)
        val MIGRATION_2_7 = legacyMigration(2)
        val MIGRATION_3_7 = legacyMigration(3)
        val MIGRATION_4_7 = legacyMigration(4)
        val MIGRATION_5_7 = legacyMigration(5)
        val MIGRATION_6_7 = legacyMigration(6)

        /**
         * v8：笔记补上出处锚点与状态。
         * - anchor：金句/引文来自哪一段（如 CHAPTER:3 / PAGE:12 / PARAGRAPH:40），
         *   否则「金句」列表只是一堆无法定位的文本。
         * - status：校验未通过但仍被用户保留的结果会标为 unvalidated，
         *   便于界面提示与后续重新校验。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `anchor` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `status` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v9：新增书签表。纯新增表，不触碰任何既有列，老库升级不会丢数据。
         * anchor 复用笔记的锚点语法，跳转直接走 ReaderView 已有的锚点定位。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `bookmarks`(
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `book_id` INTEGER NOT NULL,
                        `anchor` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `excerpt` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL)"""
                )
            }
        }

        private fun legacyMigration(from: Int) = object : Migration(from, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = rebuildLegacySchema(db)
        }

        /**
         * 把 legacy 表整体重建为 Room v7 结构。Room 对可空性与默认值校验很严，而 SQLite 会把
         * legacy 的 INTEGER PRIMARY KEY 报告为可空；旧库各版本列集合也不一致，因此这里按
         * PRAGMA table_info 实际存在的列拷贝，缺失列用等价值补齐，既不会因结构差异崩溃，
         * 也不会丢弃用户已有的书目、笔记、文件夹、设置与阅读记录。
         */
        private fun rebuildLegacySchema(db: SupportSQLiteDatabase) {
            val booksColumns = legacyColumns(db, "books")
            db.execSQL("DROP TABLE IF EXISTS books_new")
            db.execSQL(
                """CREATE TABLE books_new(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    format TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    progress REAL NOT NULL,
                    added_at INTEGER NOT NULL,
                    last_read_at INTEGER NOT NULL,
                    folder_id INTEGER NOT NULL,
                    total_read_ms INTEGER NOT NULL,
                    author TEXT NOT NULL,
                    item_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    favorite INTEGER NOT NULL,
                    tags TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    deleted_at INTEGER NOT NULL)"""
            )
            if (booksColumns.isNotEmpty()) {
                val format = booksColumns.valueOrDefault("format", "'txt'")
                val progress = booksColumns.valueOrDefault("progress", "0")
                db.execSQL(
                    """INSERT INTO books_new(
                        id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id,total_read_ms,
                        author,item_type,status,favorite,tags,content_hash,deleted_at)
                        SELECT ${booksColumns.valueOrDefault("id", "NULL")},
                        ${booksColumns.valueOrDefault("title", "''")},
                        ${booksColumns.valueOrDefault("file_name", "''")},
                        $format,${booksColumns.valueOrDefault("size_bytes", "0")},$progress,
                        ${booksColumns.valueOrDefault("added_at", "0")},
                        ${booksColumns.valueOrDefault("last_read_at", booksColumns.valueOrDefault("added_at", "0"))},
                        ${booksColumns.valueOrDefault("folder_id", "0")},
                        ${booksColumns.valueOrDefault("total_read_ms", "0")},
                        '',CASE WHEN lower($format) IN ('txt','md','markdown','epub') THEN 'book' ELSE 'document' END,
                        CASE WHEN $progress>=0.99 THEN 'done' WHEN $progress>0.005 THEN 'reading' ELSE 'unread' END,
                        0,'','',0 FROM books"""
                )
            }
            db.execSQL("DROP TABLE IF EXISTS books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_file_name ON books(file_name)")

            val notesColumns = legacyColumns(db, "notes")
            db.execSQL("DROP TABLE IF EXISTS notes_new")
            db.execSQL(
                """CREATE TABLE notes_new(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    book_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL)"""
            )
            if (notesColumns.isNotEmpty()) {
                db.execSQL(
                    """INSERT INTO notes_new(id,book_id,kind,content,created_at) SELECT
                        ${notesColumns.valueOrDefault("id", "NULL")},
                        ${notesColumns.valueOrDefault("book_id", "0")},
                        ${notesColumns.valueOrDefault("kind", "'note'")},
                        ${notesColumns.valueOrDefault("content", "''")},
                        ${notesColumns.valueOrDefault("created_at", "0")} FROM notes"""
                )
            }
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("ALTER TABLE notes_new RENAME TO notes")

            val folderColumns = legacyColumns(db, "folders")
            db.execSQL("DROP TABLE IF EXISTS folders_new")
            db.execSQL(
                """CREATE TABLE folders_new(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    parent_id INTEGER NOT NULL)"""
            )
            if (folderColumns.isNotEmpty()) {
                db.execSQL(
                    """INSERT INTO folders_new(id,name,parent_id) SELECT
                        ${folderColumns.valueOrDefault("id", "NULL")},
                        ${folderColumns.valueOrDefault("name", "''")},
                        ${folderColumns.valueOrDefault("parent_id", "0")} FROM folders"""
                )
            }
            db.execSQL("DROP TABLE IF EXISTS folders")
            db.execSQL("ALTER TABLE folders_new RENAME TO folders")

            val settingColumns = legacyColumns(db, "settings")
            db.execSQL("DROP TABLE IF EXISTS settings_new")
            db.execSQL("CREATE TABLE settings_new(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            if (settingColumns.isNotEmpty()) {
                db.execSQL(
                    """INSERT INTO settings_new(key,value) SELECT
                        ${settingColumns.valueOrDefault("key", "''")},
                        ${settingColumns.valueOrDefault("value", "''")} FROM settings"""
                )
            }
            db.execSQL("DROP TABLE IF EXISTS settings")
            db.execSQL("ALTER TABLE settings_new RENAME TO settings")

            val readLogColumns = legacyColumns(db, "read_log")
            db.execSQL("DROP TABLE IF EXISTS read_log_new")
            db.execSQL("CREATE TABLE read_log_new(day TEXT NOT NULL PRIMARY KEY, ms INTEGER NOT NULL)")
            if (readLogColumns.isNotEmpty()) {
                db.execSQL(
                    """INSERT INTO read_log_new(day,ms) SELECT
                        ${readLogColumns.valueOrDefault("day", "''")},
                        ${readLogColumns.valueOrDefault("ms", "0")} FROM read_log"""
                )
            }
            db.execSQL("DROP TABLE IF EXISTS read_log")
            db.execSQL("ALTER TABLE read_log_new RENAME TO read_log")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS ai_artifacts(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    book_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    content TEXT NOT NULL,
                    citations_json TEXT NOT NULL,
                    document_hash TEXT NOT NULL,
                    model TEXT NOT NULL,
                    prompt_version INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL)"""
            )
        }

        /** 返回 legacy 表实际存在的列名；表不存在时返回空集合。 */
        private fun legacyColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) columns += cursor.getString(nameIndex).orEmpty()
                }
            }
            return columns
        }

        /** 列存在时直接引用，缺失时用等价的默认表达式补齐，保证 INSERT ... SELECT 始终可执行。 */
        private fun Set<String>.valueOrDefault(column: String, fallback: String): String =
            if (column in this) column else fallback
    }
}
