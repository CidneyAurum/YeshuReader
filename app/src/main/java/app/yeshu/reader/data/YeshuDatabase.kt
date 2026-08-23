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

    @Query("UPDATE books SET total_read_ms=total_read_ms+:delta WHERE id=:id")
    fun addReadTime(id: Long, delta: Long)

    @Query("SELECT total_read_ms FROM books WHERE id=:id")
    fun totalReadMs(id: Long): Long?

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveArtifact(artifact: AiArtifactEntity): Long

    @Query("SELECT * FROM ai_artifacts WHERE book_id=:bookId ORDER BY updated_at DESC")
    fun listArtifacts(bookId: Long): List<AiArtifactEntity>

    @Query("SELECT * FROM ai_artifacts ORDER BY id")
    fun allArtifacts(): List<AiArtifactEntity>
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
        AiArtifactEntity::class
    ],
    version = 7,
    exportSchema = false
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
                .addMigrations(MIGRATION_6_7)
                .allowMainThreadQueries()
                .build()
                .also { instance = it }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild all legacy SQLiteOpenHelper tables. Room validates nullability and
                // default expressions strictly, while SQLite reports legacy INTEGER PRIMARY KEY
                // columns as nullable even though they are effectively non-null.
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
                db.execSQL(
                    """INSERT INTO books_new(
                        id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id,total_read_ms,
                        author,item_type,status,favorite,tags,content_hash,deleted_at)
                        SELECT id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id,total_read_ms,
                        '',CASE WHEN lower(format) IN ('txt','md','markdown','epub') THEN 'book' ELSE 'document' END,
                        CASE WHEN progress>=0.99 THEN 'done' WHEN progress>0.005 THEN 'reading' ELSE 'unread' END,
                        0,'','',0 FROM books"""
                )
                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_file_name ON books(file_name)")

                db.execSQL(
                    """CREATE TABLE notes_new(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        book_id INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL)"""
                )
                db.execSQL("INSERT INTO notes_new(id,book_id,kind,content,created_at) SELECT id,book_id,kind,content,created_at FROM notes")
                db.execSQL("DROP TABLE notes")
                db.execSQL("ALTER TABLE notes_new RENAME TO notes")

                db.execSQL(
                    """CREATE TABLE folders_new(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        parent_id INTEGER NOT NULL)"""
                )
                db.execSQL("INSERT INTO folders_new(id,name,parent_id) SELECT id,name,parent_id FROM folders")
                db.execSQL("DROP TABLE folders")
                db.execSQL("ALTER TABLE folders_new RENAME TO folders")

                db.execSQL("CREATE TABLE settings_new(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO settings_new(key,value) SELECT key,value FROM settings")
                db.execSQL("DROP TABLE settings")
                db.execSQL("ALTER TABLE settings_new RENAME TO settings")

                db.execSQL("CREATE TABLE read_log_new(day TEXT NOT NULL PRIMARY KEY, ms INTEGER NOT NULL)")
                db.execSQL("INSERT INTO read_log_new(day,ms) SELECT day,ms FROM read_log")
                db.execSQL("DROP TABLE read_log")
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
        }
    }
}
