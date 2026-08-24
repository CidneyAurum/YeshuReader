package app.yeshu.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.yeshu.reader.parse.DocParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Book-cover storage.
 *
 * The rendered cover always lives in the app-private files directory. A small sidecar marks
 * user-selected covers, so the precedence is deterministic without a database migration:
 * custom cover > cover detected in the source document > generated placeholder in the UI.
 */
object CoverStore {

    private const val COVER_MAX_DIMENSION = 480
    private const val JPEG_QUALITY = 86
    private const val MAX_CUSTOM_SOURCE_BYTES = 64L * 1024L * 1024L

    private val mem = object : android.util.LruCache<Long, Bitmap>(24) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }
    private val generationLock = Any()
    private val inFlight = mutableSetOf<Long>()
    private val completionListeners = mutableMapOf<Long, MutableList<(Boolean) -> Unit>>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun file(ctx: Context, bookId: Long) = File(ctx.filesDir, "cover_$bookId.img")

    private fun customMarker(ctx: Context, bookId: Long) =
        File(ctx.filesDir, "cover_$bookId.custom")

    private fun autoMissMarker(ctx: Context, bookId: Long) =
        File(ctx.filesDir, "cover_$bookId.auto_miss")

    fun hasCover(ctx: Context, bookId: Long): Boolean = file(ctx, bookId).isFile

    fun isCustom(ctx: Context, bookId: Long): Boolean =
        file(ctx, bookId).isFile && customMarker(ctx, bookId).isFile

    /**
     * Restores only the source flag after another component has restored cover_<id>.img.
     * Backup code can export [isCustom] and call this method after copying the cover file.
     */
    fun restoreCustomFlag(ctx: Context, bookId: Long, custom: Boolean): Boolean =
        synchronized(generationLock) {
            val marker = customMarker(ctx, bookId)
            if (!custom) {
                marker.delete()
                true
            } else if (hasCover(ctx, bookId)) {
                runCatching {
                    marker.writeText("custom\n")
                    autoMissMarker(ctx, bookId).delete()
                }.isSuccess
            } else {
                marker.delete()
                false
            }
        }

    /** Human-readable source used by the shelf management UI. */
    fun sourceLabel(ctx: Context, bookId: Long): String = when {
        isCustom(ctx, bookId) -> "自定义封面"
        hasCover(ctx, bookId) -> "自动封面"
        else -> "自动占位"
    }

    /** Cached cover decode; a missing or invalid private cover returns null. */
    fun load(ctx: Context, bookId: Long): Bitmap? {
        if (!hasCover(ctx, bookId)) return null
        mem.get(bookId)?.let { return it }
        val bmp = decodeSampledBitmap(file(ctx, bookId), COVER_MAX_DIMENSION) ?: return null
        mem.put(bookId, bmp)
        return bmp
    }

    /**
     * Detects a cover on a background thread. Calls made while the same book is being processed
     * join that work, allowing an import-time extraction to notify a newly opened shelf.
     */
    fun generateAsync(
        ctx: Context,
        bookId: Long,
        source: File,
        format: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        launchAutoGeneration(ctx.applicationContext, bookId, source, format, false, onComplete)
    }

    /** Starts automatic detection only when no cover exists and the same source did not fail. */
    fun ensureAutoAsync(
        ctx: Context,
        bookId: Long,
        source: File,
        format: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (hasCover(ctx, bookId)) {
            onComplete?.let { mainHandler.post { it(true) } }
            return
        }
        val marker = customMarker(ctx, bookId)
        if (marker.exists()) marker.delete() // Recover from an interrupted custom-cover write.
        if (!supportsAutomaticCover(format) || isKnownAutoMiss(ctx, bookId, source, format)) {
            onComplete?.let { mainHandler.post { it(false) } }
            return
        }
        launchAutoGeneration(ctx.applicationContext, bookId, source, format, false, onComplete)
    }

    private fun launchAutoGeneration(
        ctx: Context,
        bookId: Long,
        source: File,
        format: String,
        force: Boolean,
        onComplete: ((Boolean) -> Unit)?
    ) {
        val shouldLaunch = synchronized(generationLock) {
            if (onComplete != null) {
                completionListeners.getOrPut(bookId) { mutableListOf() }.add(onComplete)
            }
            inFlight.add(bookId)
        }
        if (!shouldLaunch) return

        Thread({
            val success = try {
                generateSync(ctx, bookId, source, format, force)
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
            val listeners = synchronized(generationLock) {
                inFlight.remove(bookId)
                completionListeners.remove(bookId).orEmpty()
            }
            if (listeners.isNotEmpty()) mainHandler.post { listeners.forEach { it(success) } }
        }, "yeshu-cover-$bookId").start()
    }

    /** Existing callers keep the non-forcing behavior: a custom or valid automatic cover wins. */
    fun generateSync(ctx: Context, bookId: Long, source: File, format: String): Boolean =
        generateSync(ctx, bookId, source, format, false)

    private fun generateSync(
        ctx: Context,
        bookId: Long,
        source: File,
        format: String,
        force: Boolean
    ): Boolean {
        if (!source.isFile || !supportsAutomaticCover(format)) return false
        if (!force && hasCover(ctx, bookId)) return true
        if (customMarker(ctx, bookId).isFile) return hasCover(ctx, bookId)

        val bitmap = extractAutomaticBitmap(source, format) ?: run {
            recordAutoMiss(ctx, bookId, source, format)
            return false
        }
        return try {
            synchronized(generationLock) {
                // A user may select a cover while PDF/EPUB extraction is still running.
                if (customMarker(ctx, bookId).isFile) return@synchronized hasCover(ctx, bookId)
                if (!force && hasCover(ctx, bookId)) return@synchronized true
                val saved = writePrivateCover(ctx, bookId, bitmap)
                if (saved) {
                    autoMissMarker(ctx, bookId).delete()
                    mem.remove(bookId)
                }
                saved
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Copies a selected image into private storage after sampled decoding and EXIF correction.
     * The app never depends on the picker URI after this method returns.
     */
    fun saveCustom(ctx: Context, bookId: Long, uri: Uri): Boolean {
        val bitmap = try {
            decodeSampledBitmap(ctx, uri, COVER_MAX_DIMENSION)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false

        return try {
            synchronized(generationLock) {
                val saved = writePrivateCover(ctx, bookId, bitmap)
                if (saved) {
                    customMarker(ctx, bookId).writeText("custom\n")
                    autoMissMarker(ctx, bookId).delete()
                    mem.remove(bookId)
                }
                saved
            }
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Removes a custom cover and immediately re-detects the source document's own cover. */
    fun regenerateAutoSync(
        ctx: Context,
        bookId: Long,
        source: File,
        format: String
    ): Boolean {
        synchronized(generationLock) {
            customMarker(ctx, bookId).delete()
            autoMissMarker(ctx, bookId).delete()
            file(ctx, bookId).delete()
            mem.remove(bookId)
        }
        return try {
            generateSync(ctx, bookId, source, format, true)
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    /** Removes the rendered cover and all sidecars when a book is permanently purged. */
    fun delete(ctx: Context, bookId: Long) {
        synchronized(generationLock) {
            file(ctx, bookId).delete()
            customMarker(ctx, bookId).delete()
            autoMissMarker(ctx, bookId).delete()
            mem.remove(bookId)
        }
    }

    private fun supportsAutomaticCover(format: String): Boolean =
        format.lowercase() in setOf("pdf", "epub", "jpg", "jpeg", "png", "webp")

    private fun extractAutomaticBitmap(source: File, format: String): Bitmap? = try {
        when (format.lowercase()) {
            "pdf" -> renderFirstPage(source)
            "epub" -> DocParser.parseText(source).coverBytes
                ?.let { decodeSampledBytes(it, COVER_MAX_DIMENSION) }
            "jpg", "jpeg", "png", "webp" -> decodeSampledBitmap(source, COVER_MAX_DIMENSION)
            else -> null
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun sourceSignature(source: File, format: String) =
        "${source.length()}:${source.lastModified()}:${format.lowercase()}"

    private fun isKnownAutoMiss(ctx: Context, bookId: Long, source: File, format: String): Boolean =
        autoMissMarker(ctx, bookId).takeIf { it.isFile }?.runCatching { readText() }?.getOrNull() ==
            sourceSignature(source, format)

    private fun recordAutoMiss(ctx: Context, bookId: Long, source: File, format: String) {
        runCatching { autoMissMarker(ctx, bookId).writeText(sourceSignature(source, format)) }
    }

    private fun writePrivateCover(ctx: Context, bookId: Long, bitmap: Bitmap): Boolean {
        val output = file(ctx, bookId)
        val temporary = File(ctx.filesDir, "${output.name}.tmp")
        temporary.delete()
        val compressed = temporary.outputStream().buffered().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        if (!compressed || temporary.length() <= 0L) {
            temporary.delete()
            return false
        }
        if (output.exists() && !output.delete()) {
            temporary.delete()
            return false
        }
        if (!temporary.renameTo(output)) {
            temporary.delete()
            return false
        }
        return true
    }

    private fun decodeSampledBitmap(ctx: Context, uri: Uri, maxDimension: Int): Bitmap? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let { decodeSampledBitmap(File(it), maxDimension) }
        }
        // Some document providers expose non-seekable streams. Materializing once avoids opening
        // the picker URI repeatedly for bounds, pixels and EXIF while still enforcing a hard cap.
        val temporary = File.createTempFile("yeshu-cover-source-", ".img", ctx.cacheDir)
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                temporary.outputStream().buffered().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CUSTOM_SOURCE_BYTES) return null
                        target.write(buffer, 0, read)
                    }
                }
            }
            decodeSampledBitmap(temporary, maxDimension)
        } finally {
            temporary.delete()
        }
    }

    /** Reads only a thumbnail-sized bitmap, then applies the source EXIF orientation. */
    private fun decodeSampledBitmap(source: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
        val orientation = readOrientation { source.inputStream() }
        return orientBitmap(decoded, orientation)
    }

    private fun decodeSampledBytes(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val orientation = readOrientation { ByteArrayInputStream(bytes) }
        return orientBitmap(decoded, orientation)
    }

    private fun readOrientation(open: () -> InputStream?): Int = try {
        open()?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > maxDimension) sample *= 2
        return sample
    }

    private fun orientBitmap(source: Bitmap, orientation: Int): Bitmap {
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

    /** Placeholder color for source formats without an embedded cover. */
    fun tint(format: String): Int = when (format) {
        "txt" -> Color.parseColor("#5B8DEF")
        "epub" -> Color.parseColor("#E8A15D")
        "docx" -> Color.parseColor("#4A90D9")
        "pptx" -> Color.parseColor("#E86A5D")
        "pdf" -> Color.parseColor("#D95B6A")
        else -> Color.parseColor("#7A8BA6")
    }

    private fun renderFirstPage(source: File): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val scale = min(
                    COVER_MAX_DIMENSION.toFloat() / page.width,
                    COVER_MAX_DIMENSION.toFloat() / page.height
                )
                val width = max(1, (page.width * scale).toInt())
                val height = max(1, (page.height * scale).toInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        } finally {
            renderer.close()
        }
    }
}
