package app.yeshu.reader.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class YeshuMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "yeshu-migration-test.db"

    @Before
    @After
    fun clean() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration6To7_preservesLegacyLibraryAndCreatesArtifacts() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL(
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
            legacy.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, parent_id INTEGER NOT NULL DEFAULT 0)")
            legacy.execSQL("CREATE TABLE read_log(day TEXT PRIMARY KEY, ms INTEGER NOT NULL DEFAULT 0)")
            legacy.execSQL("INSERT INTO books(id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id,total_read_ms) VALUES(1,'讲义','lesson.pdf','pdf',42,0.5,10,20,2,3000)")
            legacy.execSQL("INSERT INTO folders(id,name,parent_id) VALUES(2,'课程',0)")
            legacy.execSQL("INSERT INTO notes(id,book_id,kind,content,created_at) VALUES(3,1,'note','保留我',30)")
            legacy.execSQL("INSERT INTO settings(key,value) VALUES('ai_key','legacy-secret')")
            legacy.execSQL("INSERT INTO read_log(day,ms) VALUES('2026-08-23',3000)")
            legacy.version = 6
        }

        val room = Room.databaseBuilder(context, YeshuDatabase::class.java, databaseName)
            .addMigrations(
                YeshuDatabase.MIGRATION_6_7,
                YeshuDatabase.MIGRATION_7_8,
                YeshuDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.dao()
            val item = dao.getBook(1)
            assertNotNull(item)
            assertEquals("讲义", item!!.title)
            assertEquals("document", item.itemType)
            assertEquals("reading", item.status)
            assertEquals(1, dao.listNotes(1).size)
            assertEquals("课程", dao.getFolder(2)?.name)
            assertEquals(3000L, dao.recentReadLog(1).single().ms)
            assertNotNull(dao.getSetting("ai_key"))
            assertEquals(0, dao.listArtifacts(1).size)
            // 版本必须跟到 @Database 声明的最新版，否则 Room 会以「缺少迁移」拒绝打开
            assertEquals(9, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
        }
    }

    /** v9 只新增 bookmarks 表：老库的笔记、锚点与状态必须原样保留。 */
    @Test
    fun migration8To9_addsBookmarksAndKeepsNotes() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL(
                """CREATE TABLE books(
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
            legacy.execSQL("CREATE TABLE settings(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            legacy.execSQL(
                """CREATE TABLE notes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    book_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    anchor TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT '')"""
            )
            legacy.execSQL("CREATE TABLE folders(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, parent_id INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE read_log(day TEXT NOT NULL PRIMARY KEY, ms INTEGER NOT NULL)")
            legacy.execSQL(
                """CREATE TABLE ai_artifacts(
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
            legacy.execSQL("INSERT INTO books(id,title,file_name,format,size_bytes,progress,added_at,last_read_at,folder_id,total_read_ms,author,item_type,status,favorite,tags,content_hash,deleted_at) VALUES(1,'书','a.epub','epub',10,0.4,1,2,0,100,'','book','reading',0,'','',0)")
            legacy.execSQL("INSERT INTO notes(id,book_id,kind,content,created_at,anchor,status) VALUES(7,1,'highlight','划过的段落',5,'BLOCK:3','amber')")
            legacy.version = 8
        }

        val room = Room.databaseBuilder(context, YeshuDatabase::class.java, databaseName)
            .addMigrations(YeshuDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.dao()
            val notes = dao.listNotes(1, "highlight")
            assertEquals(1, notes.size)
            // anchor/status 是重点的颜色与位置，升级时绝不能丢
            assertEquals("BLOCK:3", notes.single().anchor)
            assertEquals("amber", notes.single().status)
            assertEquals(0, dao.listBookmarks(1).size)
            assertEquals(
                9L,
                dao.addBookmark(
                    BookmarkEntity(bookId = 1, anchor = "BLOCK:3", label = "第 4 段", excerpt = "", createdAt = 9)
                )
            )
            assertEquals(1, dao.listBookmarks(1).size)
        } finally {
            room.close()
        }
    }
}
