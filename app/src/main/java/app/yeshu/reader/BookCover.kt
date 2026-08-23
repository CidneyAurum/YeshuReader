package app.yeshu.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache

/**
 * 程序化占位封面：按书名稳定生成低饱和渐变 + 细纹理 + 克制排版。
 * 不同书籍颜色不同但整体协调；绝不使用单一色块加巨大汉字。
 */
object BookCover {

    // 内存缓存：同一本书只画一次
    private val cache = LruCache<String, Bitmap>(24)

    /** 生成占位封面（w x h 像素）。title 用于稳定取色与排版。 */
    fun placeholder(title: String, format: String, w: Int, h: Int): Bitmap {
        val key = "$title|$format|$w"
        cache.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val density = w / 160f   // 以 160px 宽为基准缩放排版

        // 1) 稳定取色：书名 hash → 低饱和双色对，垂直渐变
        val idx = (title.hashCode().let { if (it < 0) -it else it }) % T.coverPairs.size
        val (topHex, botHex) = T.coverPairs[idx]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor(topHex), Color.parseColor(botHex),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // 2) 微纹理：两条极淡弧线增加纸感（不喧宾）
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb(18, 255, 255, 255)
        }
        c.drawArc(-w * 0.4f, h * 0.62f, w * 0.9f, h * 1.25f, -30f, 90f, false, arc)
        c.drawArc(w * 0.3f, h * 0.72f, w * 1.6f, h * 1.5f, 150f, 80f, false, arc)

        // 3) 顶部格式微标：小字距大写，40% 白
        val fmt = format.uppercase().ifEmpty { "TXT" }
        val pFmt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 7.5f * density
            color = Color.argb(102, 245, 245, 247)
            letterSpacing = 0.18f
        }
        c.drawText(fmt, 12f * density, 20f * density, pFmt)

        // 4) 书名：居中、最多两行、每行最多 6 字，15sp 级别白 92%
        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14.5f * density
            color = Color.argb(235, 245, 245, 247)
            isFakeBoldText = true
        }
        val clean = title.replace(Regex("\\s+"), " ").trim()
        val lines = when {
            clean.length <= 6 -> listOf(clean)
            clean.length <= 12 -> listOf(
                clean.substring(0, clean.length / 2),
                clean.substring(clean.length / 2)
            )
            else -> listOf(clean.take(6), clean.drop(6).take(6))
        }
        val lineH = 20 * density
        var ty = h / 2f - (lines.size - 1) * lineH / 2f + 5 * density
        for (line in lines) {
            val tw = pTitle.measureText(line)
            c.drawText(line, (w - tw) / 2f, ty, pTitle)
            ty += lineH
        }

        // 5) 底部装饰细线：32dp 宽 60% 白 hairline，克制收尾
        val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.2f * density
            color = Color.argb(70, 245, 245, 247)
        }
        c.drawLine((w - 28 * density) / 2f, h - 16 * density, (w + 28 * density) / 2f, h - 16 * density, pLine)

        cache.put(key, bmp)
        return bmp
    }
}
