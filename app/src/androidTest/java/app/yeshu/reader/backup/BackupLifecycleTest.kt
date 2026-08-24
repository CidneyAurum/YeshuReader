package app.yeshu.reader.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.yeshu.reader.Db
import app.yeshu.reader.data.AiArtifactEntity
import app.yeshu.reader.data.LibraryItemEntity
import app.yeshu.reader.data.NoteEntity
import app.yeshu.reader.data.SettingEntity
import app.yeshu.reader.data.YeshuDatabase
import app.yeshu.reader.preferences.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val room get() = YeshuDatabase.get(context)
    private val dao get() = room.dao()
    private val testFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        room.clearAllTables()
        runBlocking { UserPreferences(context).restore(UserPreferences.Snapshot()) }
        cleanupRestoreFiles()
    }

    @After
    fun tearDown() {
        room.clearAllTables()
        runBlocking { UserPreferences(context).restore(UserPreferences.Snapshot()) }
        testFiles.forEach { it.deleteRecursively() }
        cleanupRestoreFiles()
    }

    @Test
    fun restoreTwice_reusesContentHashAndDoesNotDuplicateChildrenOrSecrets() {
        dao.setSetting(SettingEntity("ai_key", "local-placeholder"))
        val metadata = JSONObject()
            .put("app", "yeshu")
            .put("version", 3)
            .put("books", JSONArray().put(JSONObject()
                .put("id", 11).put("title", "同一本书").put("fileName", "payload.txt")
                .put("format", "txt").put("sizeBytes", 7).put("contentHash", "ABC123")
                .put("totalReadMs", 4321)))
            .put("notes", JSONArray().put(JSONObject()
                .put("id", 21).put("bookId", 11).put("kind", "note")
                .put("content", "幂等笔记").put("createdAt", 1234)))
            .put("artifacts", JSONArray().put(JSONObject()
                .put("id", 31).put("bookId", 11).put("kind", "study_pack")
                .put("status", "completed").put("content", "artifact")
                .put("citationsJson", "[]").put("documentHash", "DOC-HASH")
                .put("model", "test-model").put("promptVersion", 1)
                .put("createdAt", 2000).put("updatedAt", 3000)))
            .put("settings", JSONArray()
                .put(JSONObject().put("key", "reader_font_sp").put("value", "19"))
                .put(JSONObject().put("key", "ai_base_url").put("value", "https://api.example.com/v1"))
                .put(JSONObject().put("key", "ai_key").put("value", "backup-placeholder")))
            .put("readLogs", JSONArray().put(JSONObject().put("day", "2026-08-24").put("ms", 4321)))
            .put("appearance", JSONObject().put("themeMode", "dark").put("showIllustrations", false))
        val archive = createZip(metadata, mapOf("library/payload.txt" to "content".toByteArray()))

        assertTrue(BackupService.restore(context, Uri.fromFile(archive)).startsWith("恢复完成"))
        assertTrue(BackupService.restore(context, Uri.fromFile(archive)).startsWith("恢复完成"))

        val books = dao.listBooks()
        assertEquals(1, books.size)
        assertEquals("abc123", books.single().contentHash)
        assertEquals(1, dao.listNotes(books.single().id).size)
        assertEquals(1, dao.listArtifacts(books.single().id).size)
        assertEquals(4321L, books.single().totalReadMs)
        assertEquals(4321L, dao.recentReadLog(1).single().ms)
        assertEquals("19", dao.getSetting("reader_font_sp"))
        assertEquals("https://api.example.com/v1", dao.getSetting("ai_base_url"))
        assertEquals("local-placeholder", dao.getSetting("ai_key"))
        val preferences = UserPreferences(context)
        assertEquals("dark", runBlocking { preferences.themeMode.first() })
        assertFalse(runBlocking { preferences.showIllustrations.first() })
    }

    @Test
    fun purgeBook_removesAssociatedNotesAndArtifactsInOneOperation() {
        val id = dao.insertBook(book("purge.txt", "purge-hash"))
        dao.addNote(NoteEntity(bookId = id, kind = "note", content = "remove", createdAt = 1))
        dao.saveArtifact(artifact(id, "remove-hash"))
        dao.softDeleteBook(id, 10)

        Db(context).purgeBook(id)

        assertNull(dao.findBookByContentHash("purge-hash"))
        assertTrue(dao.listNotes(id).isEmpty())
        assertTrue(dao.listArtifacts(id).isEmpty())
    }

    @Test
    fun export_excludesOrphansAndNonWhitelistedSettings() {
        val activeId = dao.insertBook(book("active.txt", "active-hash"))
        File(context.filesDir, "active.txt").also { it.writeText("active"); testFiles += it }
        dao.addNote(NoteEntity(bookId = activeId, kind = "note", content = "keep", createdAt = 1))
        dao.addNote(NoteEntity(bookId = 99999, kind = "note", content = "orphan", createdAt = 2))
        dao.saveArtifact(artifact(activeId, "keep-hash"))
        dao.saveArtifact(artifact(99999, "orphan-hash"))
        dao.setSetting(SettingEntity("reader_font_sp", "18"))
        dao.setSetting(SettingEntity("ai_model", "safe-model"))
        dao.setSetting(SettingEntity("ai_key", "local-placeholder"))
        Db(context).addReadTime(activeId, 1234L)
        runBlocking { UserPreferences(context).restore(UserPreferences.Snapshot("light", false)) }
        val archive = tempFile("export", ".zip")

        assertTrue(BackupService.export(context, Uri.fromFile(archive)).startsWith("备份完成"))
        val root = ZipFile(archive).use { zip ->
            val entry = zip.getEntry("library.json")
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }

        assertEquals(1, root.getJSONArray("notes").length())
        assertEquals(1, root.getJSONArray("artifacts").length())
        val settings = root.getJSONArray("settings")
        val settingKeys = (0 until settings.length()).map { settings.getJSONObject(it).getString("key") }.toSet()
        assertEquals(setOf("reader_font_sp", "ai_model"), settingKeys)
        assertEquals(1234L, root.getJSONArray("books").getJSONObject(0).getLong("totalReadMs"))
        assertEquals(1234L, root.getJSONArray("readLogs").getJSONObject(0).getLong("ms"))
        assertEquals("light", root.getJSONObject("appearance").getString("themeMode"))
        assertFalse(root.getJSONObject("appearance").getBoolean("showIllustrations"))
        assertFalse(root.toString().contains("local-placeholder"))
    }

    @Test
    fun restore_preservesNewerLocalArtifactAndReadingTime() {
        val localId = dao.insertBook(book("local.txt", "shared-hash").copy(totalReadMs = 9000L))
        dao.saveArtifact(artifact(localId, "doc-hash").copy(content = "new-local", updatedAt = 9000L))
        val metadata = JSONObject()
            .put("app", "yeshu")
            .put("version", 3)
            .put("books", JSONArray().put(JSONObject()
                .put("id", 7).put("title", "local.txt").put("fileName", "missing.txt")
                .put("format", "txt").put("sizeBytes", 6).put("contentHash", "shared-hash")
                .put("totalReadMs", 1000L)))
            .put("artifacts", JSONArray().put(JSONObject()
                .put("bookId", 7).put("kind", "study_pack").put("status", "completed")
                .put("content", "stale-backup").put("citationsJson", "[]")
                .put("documentHash", "doc-hash").put("model", "test-model")
                .put("promptVersion", 1).put("createdAt", 1L).put("updatedAt", 3000L)))
        val archive = createZip(metadata, emptyMap())

        assertTrue(BackupService.restore(context, Uri.fromFile(archive)).startsWith("恢复完成"))

        assertEquals(9000L, dao.getBook(localId)?.totalReadMs)
        assertEquals("new-local", dao.listArtifacts(localId).single().content)
        assertEquals(9000L, dao.listArtifacts(localId).single().updatedAt)
    }

    @Test
    fun restore_rejectsExcessiveCompressionRatioWithoutPartialData() {
        val metadata = JSONObject()
            .put("app", "yeshu")
            .put("books", JSONArray().put(JSONObject()
                .put("id", 1).put("title", "bomb").put("fileName", "bomb.txt")
                .put("sizeBytes", 2_000_000).put("contentHash", "bomb-hash")))
        val archive = createZip(metadata, mapOf("library/bomb.txt" to ByteArray(2_000_000)))

        val result = BackupService.restore(context, Uri.fromFile(archive))

        assertTrue(result.contains("压缩比"))
        assertTrue(dao.listBooks().isEmpty())
    }

    @Test
    fun restore_rejectsTooManyEntriesWithoutPartialData() {
        val archive = tempFile("too-many", ".zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(JSONObject().put("app", "yeshu").toString().toByteArray())
            zip.closeEntry()
            repeat(4096) { index ->
                zip.putNextEntry(ZipEntry("ignored/$index"))
                zip.closeEntry()
            }
        }

        val result = BackupService.restore(context, Uri.fromFile(archive))

        assertTrue(result.contains("条目过多"))
        assertTrue(dao.listBooks().isEmpty())
    }

    private fun book(fileName: String, hash: String) = LibraryItemEntity(
        title = fileName,
        fileName = fileName,
        sizeBytes = 6,
        addedAt = 1,
        contentHash = hash
    )

    private fun artifact(bookId: Long, hash: String) = AiArtifactEntity(
        bookId = bookId,
        kind = "study_pack",
        status = "completed",
        content = "content",
        documentHash = hash,
        model = "test-model",
        createdAt = 1,
        updatedAt = 2
    )

    private fun createZip(metadata: JSONObject, entries: Map<String, ByteArray>): File {
        val archive = tempFile("backup", ".zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(metadata.toString().toByteArray())
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile("yeshu-$prefix-", suffix, context.cacheDir).also { testFiles += it }

    private fun cleanupRestoreFiles() {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("restore_") }
            ?.forEach { it.deleteRecursively() }
    }
}
