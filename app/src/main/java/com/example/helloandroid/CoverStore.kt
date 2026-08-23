package com.example.helloandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.helloandroid.parse.DocParser
import java.io.File
import kotlin.math.max

/** 书籍封面缓存：filesDir/cover_<id>.img (JPEG) + 进程内 LruBitmapCache */
object CoverStore {

    private val mem = object : android.util.LruCache<Long, Bitmap>(24) {
        override fun sizeOf(key: Long, value: Bitmap) = 1
    }

    fun file(ctx: Context, bookId: Long) = File(ctx.filesDir, "cover_$bookId.img")

    /** 带内存缓存的封面解码；无封面返回 null */
    fun load(ctx: Context, bookId: Long): Bitmap? {
        if (!file(ctx, bookId).exists()) return null
        mem.get(bookId)?.let { return it }
        val bmp = BitmapFactory.decodeFile(file(ctx, bookId).absolutePath) ?: return null
        mem.put(bookId, bmp)
        return bmp
    }

    fun generateAsync(ctx: Context, bookId: Long, f: File, format: String) {
        Thread {
            try { generateSync(ctx, bookId, f, format) } catch (e: Exception) { e.printStackTrace() }
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
                else -> null
            }
        } catch (e: Exception) { null }
        if (bmp != null && bmp.width > 0) {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        bmp?.recycle()
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
