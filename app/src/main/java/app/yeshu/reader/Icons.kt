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

    // onDraw 里绝不新建对象（lint DrawAllocation）：阅读器底栏常驻 6+ 个图标，
    // 每帧每图标分配 Paint/Path 会在长文滚动时制造可感知的 GC 卡顿。
    // 复用同一个实例，绘制前 reset/rewind。
    private val scratchPath = android.graphics.Path()
    private val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    private fun drawScratchPath(c: Canvas, fillMode: Boolean) {
        scratchPaint.reset()
        scratchPaint.flags = Paint.ANTI_ALIAS_FLAG
        scratchPaint.style = if (fillMode) Paint.Style.FILL else Paint.Style.STROKE
        scratchPaint.color = stroke.color
        c.drawPath(scratchPath, scratchPaint)
    }

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        // 纯装饰性的自绘图标本身没有语义，交给外层可点击容器描述，
        // 否则 TalkBack 会读出一堆「未标记的视图」。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
                scratchPath.rewind()
                scratchPath.addCircle(s(12f), s(12f), s(7f), android.graphics.Path.Direction.CW)
                scratchPath.addCircle(s(16f), s(9f), s(6f), android.graphics.Path.Direction.CW)
                // 用 CLEAR 抠掉重叠部分（even-odd 效果）；所有对象复用，onDraw 零分配
                val layer = saveLayer(c)
                drawScratchPath(c, fillMode = true)
                scratchPath.rewind()
                scratchPath.addCircle(s(16.5f), s(8.5f), s(6.5f), android.graphics.Path.Direction.CW)
                c.drawPath(scratchPath, holePaint)
                restoreLayer(c, layer)
            }
            "palette" -> { // 封面墙：图片山形
                c.drawRoundRect(s(3.5f), s(4.5f), s(20.5f), s(19.5f), s(2.5f), s(2.5f), stroke)
                c.drawCircle(s(8.5f), s(9.5f), s(1.6f), fill)
                scratchPath.rewind()
                scratchPath.moveTo(s(6f), s(17f))
                scratchPath.lineTo(s(11f), s(11.5f))
                scratchPath.lineTo(s(14.5f), s(15f))
                scratchPath.lineTo(s(17f), s(12.8f))
                scratchPath.lineTo(s(20f), s(16f))
                c.drawPath(scratchPath, stroke)
            }
            "folder" -> {
                scratchPath.rewind()
                scratchPath.moveTo(s(3.5f), s(6.5f))
                scratchPath.lineTo(s(9.5f), s(6.5f))
                scratchPath.lineTo(s(11.5f), s(9f))
                scratchPath.lineTo(s(20.5f), s(9f))
                scratchPath.lineTo(s(20.5f), s(18.5f))
                scratchPath.lineTo(s(3.5f), s(18.5f))
                scratchPath.close()
                c.drawPath(scratchPath, stroke)
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
                scratchPath.rewind()
                scratchPath.moveTo(s(12f), s(5.5f))
                scratchPath.cubicTo(s(9f), s(3.8f), s(5.5f), s(4f), s(3.5f), s(5.5f))
                scratchPath.lineTo(s(3.5f), s(16.5f))
                scratchPath.cubicTo(s(5.5f), s(15f), s(9f), s(14.8f), s(12f), s(16.5f))
                c.drawPath(scratchPath, stroke)
                scratchPath.rewind()
                scratchPath.moveTo(s(12f), s(5.5f))
                scratchPath.cubicTo(s(15f), s(3.8f), s(18.5f), s(4f), s(20.5f), s(5.5f))
                scratchPath.lineTo(s(20.5f), s(16.5f))
                scratchPath.cubicTo(s(18.5f), s(15f), s(15f), s(14.8f), s(12f), s(16.5f))
                c.drawPath(scratchPath, stroke)
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
            "chat" -> { // 对话气泡：圆角矩形 + 左下小尾巴
                c.drawRoundRect(s(3.5f), s(4.5f), s(20.5f), s(16f), s(3.5f), s(3.5f), stroke)
                scratchPath.rewind()
                scratchPath.moveTo(s(8f), s(16f))
                scratchPath.lineTo(s(8f), s(20f))
                scratchPath.lineTo(s(12.5f), s(16f))
                c.drawPath(scratchPath, stroke)
                c.drawLine(s(8f), s(8.5f), s(16f), s(8.5f), stroke)
                c.drawLine(s(8f), s(12f), s(13.5f), s(12f), stroke)
            }
            "history" -> { // 回溯箭头：环形 + 逆时针箭头，表示「前情」
                scratchPath.rewind()
                scratchPath.addArc(
                    android.graphics.RectF(s(4f), s(4f), s(20f), s(20f)), -60f, 300f
                )
                c.drawPath(scratchPath, stroke)
                // 箭头指向起点，明确「往回」的方向
                c.drawLine(s(4f), s(4.5f), s(4f), s(9.5f), stroke)
                c.drawLine(s(4f), s(9.5f), s(9f), s(9.5f), stroke)
                // 中心指针：强调「当前时刻」
                c.drawLine(s(12f), s(12f), s(12f), s(8f), stroke)
            }
            "question" -> { // 问号：弧线 + 竖笔 + 点，用于「问答」
                scratchPath.rewind()
                scratchPath.moveTo(s(9f), s(9.5f))
                scratchPath.cubicTo(s(9f), s(6f), s(15f), s(6f), s(15f), s(9.5f))
                scratchPath.cubicTo(s(15f), s(12f), s(12f), s(12.5f), s(12f), s(15f))
                c.drawPath(scratchPath, stroke)
                c.drawCircle(s(12f), s(18.5f), s(1.1f), fill)
            }
            "sparkle" -> { // 四角星：AI 生成类动作的通用标记
                scratchPath.rewind()
                scratchPath.moveTo(s(12f), s(3.5f))
                scratchPath.lineTo(s(14f), s(10f))
                scratchPath.lineTo(s(20.5f), s(12f))
                scratchPath.lineTo(s(14f), s(14f))
                scratchPath.lineTo(s(12f), s(20.5f))
                scratchPath.lineTo(s(10f), s(14f))
                scratchPath.lineTo(s(3.5f), s(12f))
                scratchPath.lineTo(s(10f), s(10f))
                scratchPath.close()
                c.drawPath(scratchPath, fill)
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
