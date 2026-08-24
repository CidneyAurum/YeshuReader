package app.yeshu.reader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.yeshu.reader.AiArtifact
import app.yeshu.reader.Db
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YeshuBookLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun softDelete_hidesBookAndRestoreMakesItVisible() {
        val db = Db(context)
        val id = db.insertBook("生命周期", uniqueFileName("lifecycle"), "txt", 4L)
        try {
            db.deleteBook(id)
            assertNull(db.getBook(id))
            assertEquals(id, db.listDeletedBooks().single { it.id == id }.id)

            db.restoreDeletedBook(id)
            assertNotNull(db.getBook(id))
            assertEquals(false, db.listDeletedBooks().any { it.id == id })
        } finally {
            db.purgeBook(id)
        }
    }

    @Test
    fun purge_removesBookAndLeavesNoDanglingAssociatedRows() {
        val db = Db(context)
        val dao = YeshuDatabase.get(context).dao()
        val bookId = db.insertBook("彻底删除", uniqueFileName("purge"), "txt", 4L)
        db.addNote(bookId, "note", "keep with book")
        db.saveArtifact(
            AiArtifact(
                id = 0L,
                bookId = bookId,
                kind = "summary",
                status = "complete",
                content = "summary",
                citationsJson = "[]",
                documentHash = "hash",
                model = "test",
                promptVersion = 1,
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        db.deleteBook(bookId)
        db.purgeBook(bookId)

        assertNull(dao.getBook(bookId))
        assertEquals(0, dao.listNotes(bookId).size)
        assertEquals(0, dao.listArtifacts(bookId).size)
    }

    private fun uniqueFileName(prefix: String) = "$prefix-${System.nanoTime()}.txt"
}
