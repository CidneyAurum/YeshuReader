package app.yeshu.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverStoreTest {

    @Test
    fun customCoverIsPrivateSampledOrientedAndCanRestoreAutomaticCover() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bookId = 8_600_000_001L
        val source = File(context.cacheDir, "cover-store-oriented.jpg")
        CoverStore.delete(context, bookId)

        try {
            writeOrientedLandscape(source)
            assertNotNull("fixture JPEG must remain decodable after EXIF write", BitmapFactory.decodeFile(source.absolutePath))
            assertTrue(CoverStore.saveCustom(context, bookId, Uri.fromFile(source)))
            assertTrue(CoverStore.isCustom(context, bookId))
            assertEquals("自定义封面", CoverStore.sourceLabel(context, bookId))

            // The selected picker file can disappear immediately; the private copy remains.
            assertTrue(source.delete())
            val custom = CoverStore.load(context, bookId)
            assertNotNull(custom)
            assertTrue(max(custom!!.width, custom.height) <= 480)
            assertTrue("EXIF 90° rotation should produce a portrait thumbnail", custom.height > custom.width)

            writeOrientedLandscape(source)
            assertTrue(CoverStore.regenerateAutoSync(context, bookId, source, "jpg"))
            assertFalse(CoverStore.isCustom(context, bookId))
            assertEquals("自动封面", CoverStore.sourceLabel(context, bookId))

            assertTrue(CoverStore.restoreCustomFlag(context, bookId, true))
            assertTrue(CoverStore.isCustom(context, bookId))
            assertTrue(CoverStore.restoreCustomFlag(context, bookId, false))
            assertFalse(CoverStore.isCustom(context, bookId))

            CoverStore.delete(context, bookId)
            assertFalse(CoverStore.hasCover(context, bookId))
            assertEquals("自动占位", CoverStore.sourceLabel(context, bookId))
        } finally {
            source.delete()
            CoverStore.delete(context, bookId)
        }
    }

    private fun writeOrientedLandscape(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(71, 92, 220))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }
}
