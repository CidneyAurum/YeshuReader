package app.yeshu.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 自绘矢量图标系统（零依赖）：24x24 视口，线性描边风格，
 * 统一线宽/圆角端点，替换 emoji 字符图标。
 */
class IconView(
    ctx: Context,
    private val name: String,
    sizeDp: Int = 22,
    color: Int = Color.WHITE
) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** 视口坐标 → 像素 */
    private fun s(v: Float) = v * width / 24f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (name) {
            "back" -> {
                c.drawLine(s(15f), s(5f), s(8f), s(12f), stroke)
                c.drawLine(s(8f), s(12f), s(15f), s(19f), stroke)
            }
            "search" -> {
                c.drawCircle(s(10.5f), s(10.5f), s(5.5f), stroke)
                c.drawLine(s(14.8f), s(14.8f), s(20f), s(20f), stroke)
            }
            "list" -> {
                c.drawLine(s(4f), s(6.5f), s(20f), s(6.5f), stroke)
                c.drawLine(s(4f), s(12f), s(20f), s(12f), stroke)
                c.drawLine(s(4f), s(17.5f), s(13f), s(17.5f), stroke)
            }
            "bulb" -> {
                c.drawCircle(s(12f), s(10f), s(5.2f), stroke)
                c.drawLine(s(9.5f), s(17.5f), s(14.5f), s(17.5f), stroke)
                c.drawLine(s(10.5f), s(20f), s(13.5f), s(20f), stroke)
                // 光线
                for (a in intArrayOf(-60, -30, 0, 30, 60)) {
                    val rad = Math.toRadians(a.toDouble())
                    val x1 = s(12f + 7.2f * Math.sin(rad).toFloat())
                    val y1 = s(10f - 7.2f * Math.cos(rad).toFloat())
                    val x2 = s(12f + 9.0f * Math.sin(rad).toFloat())
                    val y2 = s(10f - 9.0f * Math.cos(rad).toFloat())
                    c.drawLine(x1, y1, x2, y2, stroke)
                }
            }
            "sun" -> {
                c.drawCircle(s(12f), s(12f), s(4f), fill)
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    c.drawLine(
                        s(12f + 6f * Math.cos(rad).toFloat()), s(12f + 6f * Math.sin(rad).toFloat()),
                        s(12f + 8.5f * Math.cos(rad).toFloat()), s(12f + 8.5f * Math.sin(rad).toFloat()),
                        stroke
                    )
                }
            }
            "moon" -> {
                val path = android.graphics.Path()
                path.addCircle(s(12f), s(12f), s(7f), android.graphics.Path.Direction.CW)
                path.addCircle(s(16f), s(9f), s(6f), android.graphics.Path.Direction.CW)
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = stroke.color
                })
                // 用背景色抠掉重叠部分（even-odd 效果）
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                val hp = android.graphics.Path()
                hp.addCircle(s(16.5f), s(8.5f), s(6.5f), android.graphics.Path.Direction.CW)
                val layer = saveLayer(c)
                c.drawPath(path, fill)
                c.drawPath(hp, hole)
                restoreLayer(c, layer)
            }
            "palette" -> { // 封面墙：图片山形
                c.drawRoundRect(s(3.5f), s(4.5f), s(20.5f), s(19.5f), s(2.5f), s(2.5f), stroke)
                c.drawCircle(s(8.5f), s(9.5f), s(1.6f), fill)
                val m = android.graphics.Path()
                m.moveTo(s(6f), s(17f))
                m.lineTo(s(11f), s(11.5f))
                m.lineTo(s(14.5f), s(15f))
                m.lineTo(s(17f), s(12.8f))
                m.lineTo(s(20f), s(16f))
                c.drawPath(m, stroke)
            }
            "folder" -> {
                val p = android.graphics.Path()
                p.moveTo(s(3.5f), s(6.5f))
                p.lineTo(s(9.5f), s(6.5f))
                p.lineTo(s(11.5f), s(9f))
                p.lineTo(s(20.5f), s(9f))
                p.lineTo(s(20.5f), s(18.5f))
                p.lineTo(s(3.5f), s(18.5f))
                p.close()
                c.drawPath(p, stroke)
            }
            "check" -> { // 多选框
                c.drawRoundRect(s(4f), s(4f), s(20f), s(20f), s(4f), s(4f), stroke)
                c.drawLine(s(8f), s(12f), s(11f), s(15f), stroke)
                c.drawLine(s(11f), s(15f), s(16.5f), s(9f), stroke)
            }
            "grid" -> {
                c.drawRoundRect(s(4f), s(4f), s(11f), s(11f), s(1.5f), s(1.5f), stroke)
                c.drawRoundRect(s(13f), s(4f), s(20f), s(11f), s(1.5f), s(1.5f), stroke)
                c.drawRoundRect(s(4f), s(13f), s(11f), s(20f), s(1.5f), s(1.5f), stroke)
                c.drawRoundRect(s(13f), s(13f), s(20f), s(20f), s(1.5f), s(1.5f), stroke)
            }
            "sliders" -> { // 设置：滑杆调节
                c.drawLine(s(4f), s(7f), s(20f), s(7f), stroke)
                c.drawLine(s(4f), s(12f), s(20f), s(12f), stroke)
                c.drawLine(s(4f), s(17f), s(20f), s(17f), stroke)
                c.drawCircle(s(15f), s(7f), s(2f), fill)
                c.drawCircle(s(8f), s(12f), s(2f), fill)
                c.drawCircle(s(17f), s(17f), s(2f), fill)
            }
            "plus" -> {
                c.drawLine(s(12f), s(5f), s(12f), s(19f), stroke)
                c.drawLine(s(5f), s(12f), s(19f), s(12f), stroke)
            }
            "book" -> {
                c.drawLine(s(12f), s(5.5f), s(12f), s(18.5f), stroke)
                val l = android.graphics.Path()
                l.moveTo(s(12f), s(5.5f))
                l.cubicTo(s(9f), s(3.8f), s(5.5f), s(4f), s(3.5f), s(5.5f))
                l.lineTo(s(3.5f), s(16.5f))
                l.cubicTo(s(5.5f), s(15f), s(9f), s(14.8f), s(12f), s(16.5f))
                c.drawPath(l, stroke)
                val r = android.graphics.Path()
                r.moveTo(s(12f), s(5.5f))
                r.cubicTo(s(15f), s(3.8f), s(18.5f), s(4f), s(20.5f), s(5.5f))
                r.lineTo(s(20.5f), s(16.5f))
                r.cubicTo(s(18.5f), s(15f), s(15f), s(14.8f), s(12f), s(16.5f))
                c.drawPath(r, stroke)
            }
            "note" -> {
                c.drawRoundRect(s(5f), s(3.5f), s(19f), s(20.5f), s(2f), s(2f), stroke)
                c.drawLine(s(8.5f), s(8.5f), s(15.5f), s(8.5f), stroke)
                c.drawLine(s(8.5f), s(12f), s(15.5f), s(12f), stroke)
                c.drawLine(s(8.5f), s(15.5f), s(13f), s(15.5f), stroke)
            }
            "share" -> {
                c.drawCircle(s(6f), s(12f), s(2.6f), stroke)
                c.drawCircle(s(18f), s(5.5f), s(2.6f), stroke)
                c.drawCircle(s(18f), s(18.5f), s(2.6f), stroke)
                c.drawLine(s(8.3f), s(10.8f), s(15.7f), s(6.7f), stroke)
                c.drawLine(s(8.3f), s(13.2f), s(15.7f), s(17.3f), stroke)
            }
            "more" -> { // 横向三点
                c.drawCircle(s(5f), s(12f), s(1.7f), fill)
                c.drawCircle(s(12f), s(12f), s(1.7f), fill)
                c.drawCircle(s(19f), s(12f), s(1.7f), fill)
            }
            "chevron" -> {
                c.drawLine(s(9.5f), s(6f), s(16f), s(12f), stroke)
                c.drawLine(s(16f), s(12f), s(9.5f), s(18f), stroke)
            }
            "close" -> {
                c.drawLine(s(6f), s(6f), s(18f), s(18f), stroke)
                c.drawLine(s(18f), s(6f), s(6f), s(18f), stroke)
            }
            "sort" -> { // 上下双向箭头
                c.drawLine(s(8f), s(19f), s(8f), s(5f), stroke)
                c.drawLine(s(5f), s(8f), s(8f), s(5f), stroke)
                c.drawLine(s(8f), s(5f), s(11f), s(8f), stroke)
                c.drawLine(s(16f), s(5f), s(16f), s(19f), stroke)
                c.drawLine(s(13f), s(16f), s(16f), s(19f), stroke)
                c.drawLine(s(16f), s(19f), s(19f), s(16f), stroke)
            }
        }
    }

    private fun saveLayer(c: Canvas): Int =
        if (android.os.Build.VERSION.SDK_INT >= 21)
            c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        else c.save()

    private fun restoreLayer(c: Canvas, layer: Int) = c.restoreToCount(layer)
}
