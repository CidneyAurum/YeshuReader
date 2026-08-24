package app.yeshu.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import app.yeshu.reader.parse.DocParser
import java.io.File
import kotlin.math.max

/** 书籍封面缓存：filesDir/cover_<id>.img (JPEG) + 进程内 LruBitmapCache */
object CoverStore {

    private const val COVER_MAX_DIMENSION = 480

    private val mem = object : android.util.LruCache<Long, Bitmap>(24) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }

    fun file(ctx: Context, bookId: Long) = File(ctx.filesDir, "cover_$bookId.img")

    /** 带内存缓存的封面解码；无封面返回 null */
    fun load(ctx: Context, bookId: Long): Bitmap? {
        if (!file(ctx, bookId).exists()) return null
        mem.get(bookId)?.let { return it }
        val bmp = decodeSampledBitmap(file(ctx, bookId), COVER_MAX_DIMENSION) ?: return null
        mem.put(bookId, bmp)
        return bmp
    }

    fun generateAsync(ctx: Context, bookId: Long, f: File, format: String) {
        Thread {
            try { generateSync(ctx, bookId, f, format) } catch (_: Exception) { }
        }.start()
    }

    fun generateSync(ctx: Context, bookId: Long, f: File, format: String) {
        val out = file(ctx, bookId)
        if (out.exists() || !f.exists()) return
        val bmp: Bitmap? = try {
            when (format) {
                "pdf" -> renderFirstPage(f)
                "epub" -> DocParser.parseText(f).coverBytes
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                "jpg", "jpeg", "png" -> decodeSampledBitmap(f, COVER_MAX_DIMENSION)
                else -> null
            }
        } catch (e: Exception) { null }
        if (bmp != null && bmp.width > 0) {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        bmp?.recycle()
    }

    /** Reads only a thumbnail-sized bitmap, then applies the source EXIF orientation. */
    private fun decodeSampledBitmap(f: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(f.absolutePath, options) ?: return null
        return orientBitmap(f, decoded)
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun orientBitmap(f: File, source: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(f.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }

        val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (oriented !== source) source.recycle()
        return oriented
    }

    /** 无封面格式的占位色（书架卡片背景） */
    fun tint(format: String): Int = when (format) {
        "txt" -> Color.parseColor("#5B8DEF")
        "epub" -> Color.parseColor("#E8A15D")
        "docx" -> Color.parseColor("#4A90D9")
        "pptx" -> Color.parseColor("#E86A5D")
        "pdf" -> Color.parseColor("#D95B6A")
        else -> Color.parseColor("#7A8BA6")
    }

    private fun renderFirstPage(f: File): Bitmap? {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { p ->
                val w = 240
                val h = max(1, p.height * w / p.width)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        } finally {
            renderer.close()
        }
    }
}
