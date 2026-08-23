package com.example.helloandroid

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import com.example.helloandroid.parse.Block
import com.example.helloandroid.parse.DocParser
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ReaderView(private val act: Activity, private val bookId: Long) : FrameLayout(act) {

    companion object {
        private const val SYS_PROMPT = "你是专业的中文阅读助手。用简体中文回答，输出简洁、结构化。"
        private const val CHUNK = 300

        // 阅读配色（微信读书/Apple Books 式暖纸与低眩光夜色）
        val PAPER_BG = Color.argb(235, 247, 241, 227)      // 日间：羊皮纸
        val PAPER_TEXT = Color.parseColor("#2B2620")       // 日间正文墨色（暖黑）
        val PAPER_HEAD = Color.parseColor("#8A4B2A")       // 日间标题（赭棕）
        val NIGHT_BG = Color.argb(255, 19, 19, 20)         // 夜间：#131314 防 OLED 拖影
        val NIGHT_TEXT = Color.parseColor("#C8CBCF")
        val NIGHT_HEAD = Color.parseColor("#E8A87C")       // 夜间标题（暖橙，低刺激）
    }

    private val db = Db(act)
    private var sc: ScrollView? = null
    private var lastSavedAt = 0L

    // 文本类文档缓存（供 AI 功能使用）
    private var bookFormat: String = ""
    private var docBlocks: List<Block>? = null

    /** 章节标题索引 [(blockIndex, title)]，顶栏联动与目录共用 */
    private var tocHeads: List<Pair<Int, String>> = emptyList()
    private var docFullText: String = ""
    private val aiCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    // 文本分段渲染状态
    private var boxRef: LinearLayout? = null
    private var renderedUpTo = 0

    // 沉浸模式：上下栏引用与状态
    private var topBar: android.view.View? = null
    private var bottomBar: android.view.View? = null
    private var progBar: android.view.View? = null
    private var nightCell: FrameLayout? = null
    private var barsHidden = false

    /** 点正文呼出/隐藏工具栏（iBooks 式沉浸阅读） */
    private fun toggleBars() {
        val t = topBar ?: return
        val b = bottomBar ?: return
        val pr = progBar
        barsHidden = !barsHidden
        if (barsHidden) {
            pr?.visibility = View.GONE
            t.animate().translationY(-t.height.toFloat()).setDuration(170)
                .withEndAction { t.visibility = View.GONE }.start()
            b.animate().translationY(b.height.toFloat()).setDuration(170)
                .withEndAction { b.visibility = View.GONE }.start()
        } else {
            t.visibility = View.VISIBLE
            b.visibility = View.VISIBLE
            pr?.visibility = View.VISIBLE
            t.translationY = -t.height.toFloat()
            b.translationY = b.height.toFloat()
            t.animate().translationY(0f).setDuration(190).start()
            b.animate().translationY(0f).setDuration(190).start()
        }
    }
    private var styleSp = 17f
    private var styleNight = false

    // PDF 惰性渲染状态
    private var pdfRenderer: PdfRenderer? = null
    private val pdfLock = Any()
    private val pageBitmaps = SparseArray<Bitmap>()
    private var pageViews: Array<ImageView?> = emptyArray()
    private var pdfBody: LinearLayout? = null

    init {
        val book = db.getBook(bookId)
        if (book != null) {
            setup(book)
        } else {
            post { (act as MainActivity).showShelf() }
        }
    }

    private fun setup(book: Book) {
        val d = density(act)
        // 恢复已保存的屏幕亮度
        db.getSetting("screen_bright")?.toIntOrNull()?.takeIf { it in 10..100 }?.let { pct ->
            val lp = act.window.attributes
            lp.screenBrightness = pct / 100f
            act.window.attributes = lp
        }
        val f = File(act.filesDir, book.fileName)
        bookFormat = book.format.ifBlank { DocParser.detect(book.fileName) }
        // 兜底：早期版本把 PDF 误存为 txt，按扩展名纠正
        if (bookFormat != "pdf" && book.fileName.lowercase().endsWith(".pdf")) bookFormat = "pdf"

        val root = FrameLayout(act)
        val iv = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2E4A66"))
        }
        val bgFile = File(act.filesDir, "bg.img")
        if (bgFile.exists()) {
            val bm = BitmapFactory.decodeFile(bgFile.absolutePath)
            if (bm != null) iv.setImageBitmap(bm)
        } else {
            iv.setImageBitmap(Glass.defaultWallpaper(act))
        }
        Glass.blur(iv)
        root.addView(iv, LayoutParams(-1, -1))
        // 顶部渐变暗化：提升状态栏/顶栏白字对比度
        val scrim = android.view.View(act).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.argb(150, 0, 0, 0), android.graphics.Color.argb(0, 0, 0, 0))
            )
        }
        root.addView(scrim, LayoutParams(-1, Glass.dp(200, d)))

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, LayoutParams(-1, -1))
        addView(root, LayoutParams(-1, -1))
        applySystemBarInsets(col)

        // 顶栏
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.darkCard()
            setPadding(Glass.dp(16, d), Glass.dp(12, d), Glass.dp(16, d), Glass.dp(12, d))
        }
        val back = FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            layoutParams = LinearLayout.LayoutParams(Glass.dp(42, d), Glass.dp(42, d))
            addView(IconView(act, "back", 22), FrameLayout.LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
            setOnClickListener { saveProgress(); closePdf(); (act as MainActivity).showShelf() }
        }
        top.addView(back)
        // 字号/主题状态（供底部工具条用）
        val fontSp = db.getSetting("reader_font_sp")?.toFloatOrNull() ?: 17f
        val night = db.getSetting("night_mode") == "1"
        // 居中书名 + 当前章节副标题（滚动联动）
        val titleTv = TextView(act).apply {
            text = book.title
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
        }
        val curHeadTv = TextView(act).apply {
            textSize = 11f
            setTextColor(Color.argb(200, 255, 255, 255))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val titleCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(titleTv)
            addView(curHeadTv, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = Glass.dp(1, d) })
        }
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        // 右侧圆形 chip：笔记 / AI
        fun topChip(label: String, bold: Boolean, onClick: () -> Unit): TextView =
            TextView(act).apply {
                text = label
                textSize = if (bold) 15f else 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
                background = Glass.iconBg()
                layoutParams = LinearLayout.LayoutParams(Glass.dp(if (bold) 46 else 42, d), Glass.dp(if (bold) 46 else 42, d))
                    .also { it.marginStart = Glass.dp(8, d) }
                setOnClickListener { onClick() }
            }
        top.addView(topChip("笔记", false) { (act as MainActivity).showNotes(bookId) })
        val btnAi = TextView(act).apply {
            text = "AI"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Glass.pillBg(Color.argb(170, 64, 118, 255))
            layoutParams = LinearLayout.LayoutParams(Glass.dp(46, d), Glass.dp(46, d))
                .also { it.marginStart = Glass.dp(8, d) }
            setOnClickListener { showAiMenu() }
        }
        top.addView(btnAi)
        col.addView(top, LayoutParams(-1, -2))

        val sv = ScrollView(act)
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        sc = sv

        try {
            if (bookFormat == "pdf") setupPdf(f, sv) else setupBlocks(f, sv)
        } catch (e: Exception) {
            val err = TextView(act).apply {
                text = "打开失败：${e.message ?: e.javaClass.simpleName}"
                textSize = 15f
                setTextColor(Color.parseColor("#AA3333"))
                setPadding(Glass.dp(20, d), Glass.dp(24, d), Glass.dp(20, d), Glass.dp(20, d))
            }
            sv.addView(err, LayoutParams(-1, -2))
        }

        // 阅读进度细条（底部工具条上方）
        val prog = Glass.progressTrack(act)
        progBar = prog
        col.addView(prog, LayoutParams(-1, Glass.dp(4, d)).also { lp ->
            lp.setMargins(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(3, d))
        })
        fun updProg() {
            val child = sv.getChildAt(0) ?: return
            val range = child.height - sv.height
            val frac = if (range > 0) (sv.scrollY.toFloat() / range).coerceIn(0f, 1f) else 0f
            prog.background?.level = (frac * 10000).toInt()
        }
        sv.post { updProg() }

        sv.viewTreeObserver.addOnScrollChangedListener {
            renderPdfWindow()
            maybeAppend()
            updProg()
            // 顶栏当前章名联动（微信读书式方位感）+ 实时百分比
            if (pdfRenderer == null && tocHeads.isNotEmpty()) {
                val bx = sv.getChildAt(0) as? LinearLayout
                val range = (bx?.height ?: 0) - sv.height
                if (bx != null && range > 0) {
                    val frac = (sv.scrollY.toFloat() / range).coerceIn(0f, 1f)
                    val curIdx = (frac * ((docBlocks?.size ?: 1) - 1)).toInt()
                    var name: String? = null
                    for (h in tocHeads) { if (h.first <= curIdx) name = h.second else break }
                    val show = name?.take(20) + " · " + (frac * 100).toInt() + "%"
                    if (show != null && curHeadTv.text != show) {
                        curHeadTv.visibility = View.VISIBLE
                        curHeadTv.text = show
                    }
                }
            } else if (pdfRenderer != null) {
                val bx = sv.getChildAt(0) as? LinearLayout
                val range = (bx?.height ?: 0) - sv.height
                if (range > 0) {
                    val show = "PDF · " + ((sv.scrollY.toFloat() / range).coerceIn(0f, 1f) * 100).toInt() + "%"
                    if (curHeadTv.text != show) {
                        curHeadTv.visibility = View.VISIBLE
                        curHeadTv.text = show
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastSavedAt > 1500) {
                lastSavedAt = now
                saveProgress()
            }
        }

        // 底部玻璃工具条：搜索 / 目录 / 亮度 / 字号 / 夜间
        val bottom = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.darkCard()
            setPadding(Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d), Glass.dp(6, d))
        }
        topBar = top
        bottomBar = bottom
        fun toolCell(icon: String?, label: String? = null, onClick: () -> Unit): FrameLayout {
            val cell = FrameLayout(act).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                foreground = Glass.pressFx()
                setOnClickListener { onClick() }
            }
            val inner: View = if (icon != null) IconView(act, icon, 22) else TextView(act).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
            cell.addView(inner, FrameLayout.LayoutParams(
                Glass.dp(if (icon != null) 24 else 44, d), Glass.dp(30, d), Gravity.CENTER))
            return cell
        }
        fun refreshNightIcon(container: FrameLayout, night: Boolean) {
            container.removeAllViews()
            container.addView(IconView(act, if (night) "sun" else "moon", 22),
                FrameLayout.LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
        }
        bottom.addView(toolCell("search") { searchInBook() })
        bottom.addView(toolCell("list") { listToc() })
        bottom.addView(toolCell("bulb") { brightnessDialog() })
        val dec = toolCell(null, "A−") { applyFontSp(styleSp - 1f) }
        setupRepeatable(dec) { applyFontSp(styleSp - 1f) }
        bottom.addView(dec)
        val nc = FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            foreground = Glass.pressFx()
            setOnClickListener { applyNight(!styleNight) }
        }
        refreshNightIcon(nc, styleNight)
        nightCell = nc
        bottom.addView(nc)
        val inc = toolCell(null, "A+") { applyFontSp(styleSp + 1f) }
        setupRepeatable(inc) { applyFontSp(styleSp + 1f) }
        bottom.addView(inc)
        col.addView(bottom, LayoutParams(-1, -2))
    }

    /** 长按连发：按下 380ms 后每 130ms 重复执行；长按后的 click 被吞掉防双重 */
    private fun setupRepeatable(v: android.view.View, action: () -> Unit) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var longFired = false
        val loop = object : Runnable {
            override fun run() {
                if (readSessionStart < 0) return  // view 已 detach
                action()
                h.postDelayed(this, 130)
            }
        }
        v.setOnLongClickListener {
            longFired = true
            action()
            h.postDelayed(loop, 380)
            true
        }
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                h.removeCallbacks(loop)
            }
            false
        }
        v.setOnClickListener {
            if (!longFired) action()
            longFired = false
        }
    }

    // ---------- 文本类（TXT/EPUB/DOCX/PPTX）：块流 ----------

    private fun setupBlocks(f: File, sv: ScrollView) {
        val d = density(act)
        styleSp = db.getSetting("reader_font_sp")?.toFloatOrNull() ?: 17f
        // 夜间：跟随系统深色模式优先于手动开关
        val followSys = db.getSetting("night_follow_sys") == "1"
        styleNight = if (followSys) {
            (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            db.getSetting("night_mode") == "1"
        }
        // 恢复上次调节的亮度偏好
        db.getSetting("reader_brightness")?.toIntOrNull()?.let { pct ->
            if (pct in 10..100) {
                val lp = act.window.attributes
                lp.screenBrightness = pct / 100f
                act.window.attributes = lp
            }
        }
        val boxBg = if (styleNight) NIGHT_BG else PAPER_BG
        val doc = DocParser.parseText(f)
        docBlocks = doc.blocks
        tocHeads = doc.blocks.mapIndexedNotNull { i, b ->
            if (b.type == Block.HEADING) i to b.text else null
        }
        docFullText = doc.fullText
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(boxBg)
            setPadding(0, Glass.dp(14, d), 0, 0)
        }
        boxRef = box
        renderedUpTo = 0
        // 底部留白（常驻末位，分块插入到它之前）
        val pad = View(act).apply { setPadding(0, 0, 0, Glass.dp(60, d)) }
        box.addView(pad, LayoutParams(-1, Glass.dp(40, d)))
        sv.addView(box, LayoutParams(-1, -2))
        appendChunk(CHUNK)

        // 恢复进度时按需预载足够多的块，保证目标位置已渲染
        val total = doc.blocks.size
        val p = db.getBook(bookId)?.progress ?: 0f
        var guard = 0
        while (total > 0 && renderedUpTo < total &&
            renderedUpTo.toFloat() / total < p && guard++ < 60) {
            appendChunk(CHUNK)
        }

        sv.post {
            if (p > 0.001f && p < 0.999f) {
                try {
                    sv.scrollTo(0, (box.height * p).toInt())
                } catch (e: Exception) {}
            }
        }
    }

    private fun makeBlockView(b: Block): TextView {
        val d = density(act)
        val tv = TextView(act)
        if (b.type == Block.HEADING) {
            tv.tag = "head"
            tv.text = b.text
            tv.textSize = styleSp + 4f
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextColor(if (styleNight) NIGHT_HEAD else PAPER_HEAD)
            tv.setLineSpacing(Glass.dp(3, d).toFloat(), 1.15f)
            tv.setPadding(Glass.dp(20, d), Glass.dp(26, d), Glass.dp(20, d), Glass.dp(10, d))
            // 标题同样参与沉浸切换
            tv.setOnClickListener { toggleBars() }
        } else {
            tv.text = b.text
            tv.textSize = styleSp
            tv.setTextColor(if (styleNight) NIGHT_TEXT else PAPER_TEXT)
            // 成熟阅读器共识：1.38 倍行距最舒适
            tv.setLineSpacing(0f, 1.38f)
            tv.setPadding(Glass.dp(20, d), Glass.dp(6, d), Glass.dp(20, d), Glass.dp(6, d))
            if (b.text.length > 4) {
                tv.setOnLongClickListener { explainBlock(b.text); true }
                // 单击段落 = 切换沉浸模式（iBooks 式）
                tv.setOnClickListener { toggleBars() }
            }
        }
        return tv
    }

    /** 就地调整字号：不重建界面、不丢滚动位置 */
    private fun applyFontSp(newSp: Float) {
        if (bookFormat == "pdf" || pdfRenderer != null) return
        styleSp = newSp.coerceIn(12f, 26f)
        db.setSetting("reader_font_sp", styleSp.toString())
        val box = boxRef ?: return
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            if (v is TextView && v.tag != "head") v.textSize = styleSp
            if (v is TextView && v.tag == "head") v.textSize = styleSp + 4f
        }
    }

    /** 就地切换夜间模式：重刷正文颜色与底色 */
    private fun applyNight(night: Boolean) {
        if (bookFormat == "pdf" || pdfRenderer != null) return
        styleNight = night
        db.setSetting("night_mode", if (night) "1" else "0")
        nightCell?.let { nc ->
            nc.removeAllViews()
            val d = density(act)
            nc.addView(IconView(act, if (night) "sun" else "moon", 22),
                FrameLayout.LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
        }
        val box = boxRef ?: return
        box.setBackgroundColor(if (night) NIGHT_BG else PAPER_BG)
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            if (v is TextView) {
                if (v.tag == "head") {
                    v.setTextColor(if (night) NIGHT_HEAD else PAPER_HEAD)
                } else {
                    v.setTextColor(if (night) NIGHT_TEXT else PAPER_TEXT)
                }
            }
        }
    }

    /** 追加渲染下一块文本（插入到底部留白之前） */
    private fun appendChunk(n: Int) {
        val box = boxRef ?: return
        val blocks = docBlocks ?: return
        val end = min(blocks.size, renderedUpTo + n)
        for (i in renderedUpTo until end) {
            box.addView(makeBlockView(blocks[i]), box.childCount - 1, LayoutParams(-1, -2))
        }
        renderedUpTo = end
    }

    /** 滚近底部时自动续载 */
    private fun maybeAppend() {
        val sv = sc ?: return
        if (bookFormat == "pdf" || pdfRenderer != null) return
        if (renderedUpTo >= (docBlocks?.size ?: 0)) return
        val child = sv.getChildAt(0) ?: return
        if (sv.scrollY + sv.height > child.height - Glass.dp(900, density(act))) {
            appendChunk(CHUNK)
        }
    }

    // ---------- PDF：惰性按需渲染 ----------

    private fun setupPdf(f: File, sv: ScrollView) {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        pdfRenderer = renderer
        val n = renderer.pageCount

        val d = density(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#525659"))
        }
        pdfBody = box
        pageViews = arrayOfNulls(n)

        // 预取每页尺寸，生成占位 ImageView
        for (i in 0 until n) {
            val size = renderer.openPage(i).use { p -> Pair(p.width, p.height) }
            val iv = ImageView(act).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#3B3F43"))
                setOnClickListener { toggleBars() }
            }
            val lp = LayoutParams(-1, -2)
            lp.topMargin = Glass.dp(if (i == 0) 8 else 4, d)
            lp.bottomMargin = Glass.dp(4, d)
            box.addView(iv, lp)
            pageViews[i] = iv
            // 记录宽高比供布局前占位
            iv.tag = Pair(size.first, size.second)
        }
        val tail = View(act)
        box.addView(tail, LayoutParams(-1, Glass.dp(50, d)))
        sv.addView(box, LayoutParams(-1, -2))

        sv.post { renderPdfWindow(force = true) }
    }

    /** 渲染当前可见页 ±1，回收远离窗口的位图 */
    private fun renderPdfWindow(force: Boolean = false) {
        val renderer = pdfRenderer ?: return
        val sv = sc ?: return
        val box = pdfBody ?: return
        if (pageViews.isEmpty()) return

        val scrollY = sv.scrollY
        val viewH = sv.height
        if (viewH <= 0) return

        var firstVis = -1
        var lastVis = -1
        var acc = 0
        for (i in pageViews.indices) {
            val v = pageViews[i] ?: continue
            val top = acc + (v.layoutParams as MarginLayoutParams).topMargin
            val h = if (v.height > 0) v.height else estimatePageHeight(i, v, sv.width)
            val bottom = top + h
            acc = bottom + (v.layoutParams as MarginLayoutParams).bottomMargin
            if (bottom >= scrollY && firstVis == -1) firstVis = i
            if (top <= scrollY + viewH) lastVis = i
        }
        if (firstVis == -1) return

        for (i in max(0, firstVis - 1)..min(pageViews.size - 1, lastVis + 1)) {
            if (pageBitmaps.get(i) == null) renderPage(renderer, i, sv.width)
        }
        // 回收远离当前窗口的页
        for (i in 0 until pageBitmaps.size()) {
            val key = pageBitmaps.keyAt(i)
            if (key < firstVis - 8 || key > lastVis + 8) {
                pageBitmaps.get(key)?.recycle()
                pageBitmaps.remove(key)
                pageViews[key]?.setImageDrawable(null)
            }
        }
    }

    private fun estimatePageHeight(i: Int, v: View, containerW: Int): Int {
        val ratio = v.tag as? Pair<*, *> ?: return 800
        val pw = (ratio.first as Int).toFloat()
        val ph = (ratio.second as Int).toFloat()
        return if (pw <= 0 || containerW <= 0) 800
               else (ph / pw * containerW).toInt()
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, containerW: Int) {
        // API 35 的 PdfRenderer 仅支持 ARGB_8888；宽度封顶控制内存
        val targetW = min(containerW, 900)
        if (targetW <= 0) return
        synchronized(pdfLock) {
            try {
                renderer.openPage(index).use { p ->
                    val scale = targetW.toFloat() / p.width
                    val w = targetW
                    val h = max(1, (p.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageBitmaps.put(index, bmp)
                    pageViews[index]?.setImageBitmap(bmp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun closePdf() {
        try {
            for (i in 0 until pageBitmaps.size()) pageBitmaps.valueAt(i)?.recycle()
            pageBitmaps.clear()
            pdfRenderer?.close()
        } catch (e: Exception) {}
        pdfRenderer = null
    }

    // ---------- AI 功能 ----------

    private fun showAiMenu() {
        val options = arrayOf("💬 和书聊聊", "全文摘要", "内容问答", "出题自测", "前情提要", "人物速查")
        android.app.AlertDialog.Builder(act)
            .setTitle("AI 助手")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openChat()
                    1 -> aiSummary()
                    2 -> askAction()
                    3 -> quizAction()
                    4 -> recapAction()
                    5 -> castAction()
                }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 前情提要：当前章之前的内容浓缩，追长篇防忘剧情 */
    private fun recapAction() {
        val cfg = aiReady() ?: return
        val blocks = docBlocks
        if (blocks == null || tocHeads.isEmpty()) {
            showResult("提示", "本书没有章节结构，无法定位「当前章之前」的内容。\n可以改用「内容问答」。")
            return
        }
        val sv = sc ?: return
        val bx = sv.getChildAt(0) as? LinearLayout ?: return
        val range = (bx.height) - sv.height
        val frac = if (range > 0) sv.scrollY.toFloat() / range else 0f
        val curIdx = (frac * (blocks.size - 1)).toInt()
        var start = 0
        for (h in tocHeads) { if (h.first <= curIdx) start = h.first else break }
        if (start == 0) {
            showResult("提示", "你还在第一章开头，没有「前情」可讲～")
            return
        }
        val before = blocks.drop((start - 60).coerceAtLeast(0)).take(60)
            .joinToString("\n") { it.text }
        runAiStream("recap", "前情提要") { onDelta, onReason ->
            AiClient.chat(cfg, SYS_PROMPT,
                "读者正在读长篇/资料，下面是当前章节之前的内容节选。请用约 200 字梳理「到目前为止发生了什么」：" +
                    "关键事件、出场人物及其动机、留下的悬念。只输出提要正文。\n\n【前文开始】\n${before.take(18000)}\n【前文结束】",
                onDelta, onReason = onReason)
        }
    }

    /** 人物速查：从当前章提取出场人物与身份 */
    private fun castAction() {
        val cfg = aiReady() ?: return
        val chapter = currentChapterText()
        if (chapter.isBlank()) { showResult("提示", "当前章节没有可分析文本"); return }
        runAiStream("cast", "人物速查") { onDelta, onReason ->
            AiClient.chat(cfg, SYS_PROMPT,
                "从下面的章节内容中提取出场人物（最多 6 个）。每个人物一行：「名字 —— 身份/角色 + 当前状态或动机」，" +
                    "按重要性排序。若为非小说类文档，则提取核心概念/术语代替人物。\n\n${chapter.take(12000)}",
                onDelta, onReason = onReason)
        }
    }

    /** 打开与书聊天页（携带当前章上下文） */
    private fun openChat() {
        (act as MainActivity).showChat(bookId, currentChapterText())
    }

    /** 当前可见章节的纯文本（供聊天 system 上下文） */
    private fun currentChapterText(): String {
        val blocks = docBlocks ?: return ""
        val sv = sc ?: return ""
        // 找当前章起点：最后一个 tocHead <= 当前块
        val bx = sv.getChildAt(0) as? LinearLayout ?: return ""
        val range = (bx.height) - sv.height
        val frac = if (range > 0) sv.scrollY.toFloat() / range else 0f
        val curIdx = (frac * (blocks.size - 1)).toInt()
        var start = 0
        for (h in tocHeads) { if (h.first <= curIdx) start = h.first else break }
        // 取起点后 ~40 块
        return blocks.drop(start).take(40).joinToString("\n") { it.text }
    }

    private fun aiReady(): AiClient.Config? {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            android.app.AlertDialog.Builder(act)
                .setTitle("未配置 AI")
                .setMessage("请先在书架右上角「AI 设置」填写接口地址、Key 和模型名。\nPDF 功能需要支持图片输入的视觉模型。")
                .setPositiveButton("知道了", null)
                .show().also { Glass.styleDialog(it, density(act)) }
            return null
        }
        return cfg
    }

    /** PDF：渲染前几页给视觉模型 */
    private fun collectPdfPages(maxPages: Int): List<Bitmap> {
        val renderer = pdfRenderer ?: return emptyList()
        val sv = sc ?: return emptyList()
        val out = mutableListOf<Bitmap>()
        synchronized(pdfLock) {
            val n = min(renderer.pageCount, maxPages)
            for (i in 0 until n) {
                try {
                    renderer.openPage(i).use { p ->
                        val w = min(720, p.width)
                        val h = max(1, p.height * w / p.width)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return out
    }

    /** 流式 AI 任务：对话框内打字机输出 + 推理模型思考区，成功自动存笔记（kind=null 不存） */
    private fun runAiStream(kind: String?, title: String, onDone: ((String) -> Unit)? = null, call: (onDelta: (String) -> Unit, onReason: (String) -> Unit) -> String) {
        val d = density(act)
        aiCancelled.set(false)
        val scroll = ScrollView(act)
        // 推理模型思考区：灰色小字流式滚动，正文开始后收起为一行摘要
        val thinkTv = TextView(act).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8E959E"))
            setLineSpacing(Glass.dp(2, d).toFloat(), 1f)
            setPadding(Glass.dp(22, d), Glass.dp(10, d), Glass.dp(22, d), Glass.dp(2, d))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val tv = TextView(act).apply {
            text = "连接模型中…"
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(Glass.dp(4, d).toFloat(), 1f)
            setPadding(Glass.dp(22, d), Glass.dp(12, d), Glass.dp(22, d), Glass.dp(20, d))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(thinkTv)
            addView(tv)
        }
        scroll.addView(box)
        val dlg = android.app.AlertDialog.Builder(act)
            .setTitle("$title · 生成中")
            .setView(scroll)
            .setNegativeButton("停止") { _, _ -> aiCancelled.set(true) }
            .setPositiveButton("关闭", null)
            .create()
        dlg.show()
        Glass.styleDialog(dlg, density(act))
        val lastUi = longArrayOf(0L)
        Thread {
            var err: String? = null
            var reply = ""
            val acc = StringBuilder()
            val rAcc = StringBuilder()
            try {
                reply = call({ delta ->
                    acc.append(delta)
                    if (rAcc.isNotEmpty()) {
                        act.runOnUiThread { thinkTv.text = "💭 已深度思考 ${rAcc.length} 字" }
                    }
                    val now = System.currentTimeMillis()
                    if (!aiCancelled.get() && now - lastUi[0] > 150) {
                        lastUi[0] = now
                        act.runOnUiThread {
                            tv.text = acc.toString()
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }, { reason ->
                    if (aiCancelled.get()) return@call
                    rAcc.append(reason)
                    act.runOnUiThread {
                        thinkTv.visibility = View.VISIBLE
                        thinkTv.text = "💭 思考中… ${rAcc.takeLast(80)}"
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                })
                if (reply.isBlank()) throw RuntimeException("模型返回为空")
            } catch (t: Throwable) {
                if (!aiCancelled.get()) err = t.message ?: t.toString()
            }
            val e = err
            act.runOnUiThread {
                when {
                    aiCancelled.get() -> {
                        dlg.setTitle("$title · 已停止")
                        tv.text = acc.toString() + "\n\n（已停止，未保存到笔记）"
                    }
                    e != null -> dlg.setTitle("$title · 失败").also { tv.text = "调用失败：\n$e" }
                    else -> {
                        if (kind != null) db.addNote(bookId, kind, reply)
                        dlg.setTitle("$title ✓")
                        tv.text = reply
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        onDone?.invoke(reply)
                    }
                }
            }
        }.start()
    }

    private fun aiSummary() {
        val cfg = aiReady() ?: return
        if (bookFormat == "pdf") {
            runAiStream("summary", "AI 摘要") { onDelta, onReason ->
                val pages = collectPdfPages(6)
                try {
                    AiClient.chatVision(cfg, SYS_PROMPT,
                        "这是一份课件/文档的前几页截图。请生成摘要：先一句话概括主题，再用要点列出核心内容。",
                        pages, onDelta, onReason = onReason)
                } finally {
                    pages.forEach { it.recycle() }
                }
            }
        } else {
            val text = docFullText
            if (text.isBlank()) { showResult("提示", "本文档没有可提取文本"); return }
            runAiStream("summary", "AI 摘要") { onDelta, onReason ->
                AiClient.chat(cfg, SYS_PROMPT,
                    "请为下面的内容生成摘要：先一句话概括，再用 3-6 个要点列出核心内容。\n\n【内容开始】\n${text.take(24000)}\n【内容结束】",
                    onDelta, onReason = onReason)
            }
        }
    }

    private fun askAction() {
        val cfg = aiReady() ?: return
        val input = EditText(act).apply {
            hint = "想问这份资料的任何问题…"
            setSingleLine(false)
            maxLines = 3
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("内容问答")
            .setView(input)
            .setPositiveButton("提问") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                if (bookFormat == "pdf") {
                    runAiStream("ask", "问答 · $q") { onDelta, onReason ->
                        val pages = collectPdfPages(6)
                        try {
                            AiClient.chatVision(cfg, SYS_PROMPT,
                                "根据这些页面截图回答问题：$q\n若图中没有答案请直说。", pages, onDelta, onReason = onReason)
                        } finally {
                            pages.forEach { it.recycle() }
                        }
                    }
                } else {
                    val text = docFullText
                    runAiStream("ask", "问答 · $q") { onDelta, onReason ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "根据以下资料回答问题。若资料中没有答案请直说。\n\n问题：$q\n\n【资料开始】\n${text.take(24000)}\n【资料结束】",
                            onDelta, onReason = onReason)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun quizAction() {
        val cfg = aiReady() ?: return
        val build = { onDelta: (String) -> Unit, onReason: (String) -> Unit ->
            if (bookFormat == "pdf") {
                val pages = collectPdfPages(8)
                try {
                    AiClient.chatVision(cfg, SYS_PROMPT,
                        "根据这些页面截图出 5 道自测题（选择/简答混合）。只输出题目本身，不要给答案——用户作答后你会批改。",
                        pages, onDelta, onReason = onReason)
                } finally {
                    pages.forEach { it.recycle() }
                }
            } else {
                val text = docFullText
                if (text.isBlank()) throw RuntimeException("本文档没有可提取文本")
                AiClient.chat(cfg, SYS_PROMPT,
                    "根据以下内容出 5 道自测题（选择/简答混合）。只输出题目本身，不要给出答案——用户稍后作答，你会批改。\n\n${text.take(20000)}",
                    onDelta, onReason = onReason)
            }
        }
        runAiStream("quiz", "自测题", onDone = { questions ->
            // 出题完成 → 引导作答批改闭环
            android.app.AlertDialog.Builder(act)
                .setTitle("✍️ 作答批改")
                .setMessage("题目已生成。把你的答案写在下框（可简答，如「1A 2B 3…」），AI 将对照原文批改评分。")
                .setPositiveButton("去作答") { _, _ -> answerQuiz(cfg, questions) }
                .setNegativeButton("稍后", null)
                .show().also { Glass.styleDialog(it, density(act)) }
        }, call = build)
    }

    /** 批改：原文 + 题目 + 用户答案 → 逐题判分与讲解 */
    private fun answerQuiz(cfg: AiClient.Config, questions: String) {
        val input = EditText(act).apply {
            hint = "在此输入你的答案…"
            setSingleLine(false)
            minLines = 3
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("✍️ 提交答案")
            .setView(input)
            .setPositiveButton("提交批改") { _, _ ->
                val ans = input.text.toString().trim()
                if (ans.isEmpty()) return@setPositiveButton
                val refText = if (bookFormat == "pdf") "(PDF 文档)" else docFullText.take(12000)
                runAiStream(null, "批改结果") { onDelta, onReason ->
                    AiClient.chat(cfg, SYS_PROMPT,
                        "你是阅卷老师。下面是原文、题目和学生的答案。请逐题判定对错并简要讲解，" +
                            "最后给总分（每题 20 分，满分 100）和一句鼓励。\n\n" +
                            "【题目】\n$questions\n\n【学生答案】\n$ans\n\n【原文参考】\n$refText",
                        onDelta, onReason = onReason)
                }
                // 批改记录入笔记
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun explainBlock(blockText: String) {
        val cfg = aiReady() ?: return
        val options = arrayOf("💡 解释含义", "🌍 翻译成中文", "🗣 大白话讲解", "✍️ 续写一段", "⭐ 收藏金句")
        android.app.AlertDialog.Builder(act)
            .setTitle("这段话…")
            .setItems(options) { _, which ->
                val clip = blockText.take(3000)
                when (which) {
                    0 -> runAiStream(null, "段落解释") { onDelta, onReason ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "请解释下面这段话的含义（是什么意思、为什么重要），简洁作答：\n\n「$clip」",
                            onDelta, onReason = onReason)
                    }
                    1 -> runAiStream(null, "翻译") { onDelta, onReason ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "把下面的文字翻译成流畅的中文，只输出译文：\n\n「$clip」",
                            onDelta, onReason = onReason)
                    }
                    2 -> runAiStream(null, "大白话讲解") { onDelta, onReason ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "用大白话给中学生讲解下面这段话，可以打比方，通俗但不失准确：\n\n「$clip」",
                            onDelta, onReason = onReason)
                    }
                    3 -> runAiStream(null, "续写") { onDelta, onReason ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "顺着下面的文字风格与情节，自然续写一段（150-250字）：\n\n「$clip」",
                            onDelta, onReason = onReason)
                    }
                    4 -> {
                        db.addNote(bookId, "quote", blockText.trim())
                        android.widget.Toast.makeText(act, "已收藏金句 ⭐", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun showResult(title: String, body: String) {
        val d = density(act)
        val scroll = ScrollView(act)
        val tv = TextView(act).apply {
            this.text = body
            textSize = 14f
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(Glass.dp(3, d).toFloat(), 1f)
            setPadding(Glass.dp(20, d), Glass.dp(14, d), Glass.dp(20, d), Glass.dp(20, d))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(act)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    // ---------- 公共 ----------

    /** 书内搜索入口（PDF 为位图渲染，无文字层） */
    private fun searchInBook() {
        if (bookFormat == "pdf") {
            showResult("提示", "PDF 为整页图片渲染，暂不支持文字搜索\n可改用 AI 问答定位内容")
            return
        }
        val d = density(act)
        val input = EditText(act).apply {
            hint = "输入关键词，回车或点搜索"
            setSingleLine(true)
            setPadding(Glass.dp(20, d), Glass.dp(12, d), Glass.dp(20, d), Glass.dp(12, d))
        }
        // 软键盘回车/搜索键直接触发搜索
        val dlg = android.app.AlertDialog.Builder(act)
            .setTitle("书内搜索")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) doSearch(q)
            }
            .setNegativeButton("取消", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) { dlg.dismiss(); doSearch(q); true } else false
        }
        dlg.show()
        Glass.styleDialog(dlg, density(act))
    }

    private fun doSearch(q: String) {
        val blocks = docBlocks ?: return
        val ql = q.lowercase()
        data class Hit(val blockIndex: Int, val snippet: String)
        val hits = mutableListOf<Hit>()
        for ((i, b) in blocks.withIndex()) {
            if (hits.size >= 30) break
            val idx = b.text.lowercase().indexOf(ql)
            if (idx < 0) continue
            val s = max(0, idx - 15)
            val e = min(b.text.length, idx + q.length + 25)
            val snip = (if (s > 0) "…" else "") +
                b.text.substring(s, e).replace('\n', ' ') +
                (if (e < b.text.length) "…" else "")
            hits.add(Hit(i, snip))
        }
        if (hits.isEmpty()) {
            showResult("书内搜索", "未找到「$q」")
            return
        }
        val labels = hits.map { h -> "${h.snippet}\n（第 ${h.blockIndex + 1} 段）" }.toTypedArray()
        android.app.AlertDialog.Builder(act)
            .setTitle("找到 ${hits.size} 处「$q」")
            .setItems(labels) { _, w -> jumpToBlock(hits[w].blockIndex, flash = true) }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 目录：列出全部章节标题（EPUB 的 h1/h2 与 TXT 识别的章回），点击直达；PDF 走页码跳转 */
    private fun listToc() {
        if (bookFormat == "pdf" || pdfRenderer != null) {
            listPdfPages()
            return
        }
        val blocks = docBlocks ?: return
        val heads = if (tocHeads.isNotEmpty()) {
            tocHeads.filter { it.second.isNotBlank() }.take(200)
        } else {
            blocks.mapIndexedNotNull { i, b ->
                if (b.type == Block.HEADING && i < 200) i to b.text else null
            }.take(200)
        }
        if (heads.isEmpty()) {
            showResult("目录", "本文档没有识别到章节标题")
            return
        }
        // 计算当前阅读位置所在章节，在目录中标注
        val sv = sc
        val curIdx = if (sv != null) {
            val box = sv.getChildAt(0) as? LinearLayout
            val range = (box?.height ?: 0) - sv.height
            if (box != null && range > 0 && blocks.isNotEmpty()) {
                val frac = (sv.scrollY.toFloat() / range).coerceIn(0f, 1f)
                (frac * (blocks.size - 1)).toInt()
            } else -1
        } else -1
        var curHead = -1
        if (curIdx >= 0) heads.forEachIndexed { w, h -> if (h.first <= curIdx) curHead = w }
        val labels = heads.mapIndexed { w, h ->
            val mark = if (w == curHead) " ◀" else ""
            h.second.take(38) + mark
        }.toTypedArray()
        android.app.AlertDialog.Builder(act)
            .setTitle("目录 · ${heads.size} 章")
            .setItems(labels) { _, w -> jumpToBlock(heads[w].first) }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** PDF 页码跳转：列出全部页，点击滚到对应页 */
    private fun listPdfPages() {
        val n = pdfRenderer?.pageCount ?: return
        val labels = Array(n) { "第 ${it + 1} 页" }
        android.app.AlertDialog.Builder(act)
            .setTitle("跳转到页 · 共 $n 页")
            .setItems(labels) { _, w ->
                val sv = sc ?: return@setItems
                val target = pageViews.getOrNull(w) ?: return@setItems
                sv.post {
                    sv.smoothScrollTo(0, max(0, (target?.top ?: 0) - Glass.dp(8, density(act))))
                }
            }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 屏幕亮度调节（仅影响本应用窗口），10%-100% */
    private fun brightnessDialog() {
        val d = density(act)
        val cur = act.window.attributes.screenBrightness
        val initPct = ((if (cur < 0f) 100f else cur * 100f)).toInt().coerceIn(10, 100)
        val seek = android.widget.SeekBar(act).apply {
            max = 90
            progress = initPct - 10
            setPadding(Glass.dp(24, d), Glass.dp(16, d), Glass.dp(24, d), Glass.dp(8, d))
        }
        val label = TextView(act).apply {
            text = "当前亮度 $initPct%（会记住你的偏好）"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, Glass.dp(8, d), 0, Glass.dp(16, d))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress + 10
                label.text = "当前亮度 $pct%"
                val lp = act.window.attributes
                lp.screenBrightness = pct / 100f
                act.window.attributes = lp
                db.setSetting("reader_brightness", pct.toString())  // 记忆，下次打开恢复
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        android.app.AlertDialog.Builder(act)
            .setTitle("亮度")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                db.setSetting("screen_bright", (seek.progress + 10).toString())
            }
            .setNegativeButton("恢复默认") { _, _ ->
                db.setSetting("screen_bright", "")
                val lp = act.window.attributes
                lp.screenBrightness = -1f
                act.window.attributes = lp
            }
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 跳转到指定段落（docBlocks 顺序与正文子 View 一致）；目标未渲染时先续载 */
    private fun jumpToBlock(index: Int, flash: Boolean = false) {
        val sv = sc ?: return
        if (pdfRenderer == null) {
            val total = docBlocks?.size ?: 0
            var guard = 0
            while (index >= renderedUpTo && renderedUpTo < total && guard++ < 60) {
                appendChunk(CHUNK)
            }
        }
        val box = sv.getChildAt(0) as? LinearLayout ?: return
        val v = box.getChildAt(index) ?: return
        sv.post { sv.smoothScrollTo(0, max(0, v.top - Glass.dp(56, density(act)))) }
        if (flash) {
            // 命中段高亮：金黄停留后渐隐回底色
            v.post {
                val to = if (styleNight) Color.rgb(19, 19, 20) else Color.rgb(247, 241, 227)
                v.setBackgroundColor(Color.argb(255, 255, 213, 79))
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100
                    startDelay = 500
                    addUpdateListener { a ->
                        val t = a.animatedValue as Float
                        v.setBackgroundColor(
                            Color.rgb(
                                (255 + (Color.red(to) - 255) * t).toInt(),
                                (213 + (Color.green(to) - 213) * t).toInt(),
                                (79 + (Color.blue(to) - 79) * t).toInt()
                            )
                        )
                    }
                }.start()
            }
        }
    }

    private fun restoreScroll(sv: ScrollView) {
        val book = db.getBook(bookId)
        val p = book?.progress ?: 0f
        if (p > 0.001f && p < 0.999f) {
            sv.post {
                sv.scrollTo(0, (sv.getChildAt(0).height * p).toInt())
                if (pdfRenderer != null) renderPdfWindow(force = true)
            }
        }
    }

    private fun saveProgress() {
        val sv = sc ?: return
        val child = sv.getChildAt(0) ?: return
        val range = child.height
        val extent = sv.height
        val offset = sv.scrollY
        if (range > extent) {
            val pr = offset.toFloat() / (range - extent).toFloat()
            db.updateProgress(bookId, pr.coerceIn(0f, 1f))
        }
    }

    /** 音量键翻页：单手阅读（设置 reader_volume_flip=0 可关）；由 MainActivity.dispatchKeyEvent 调用 */
    fun handleVolumeKey(event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        val vk = event.keyCode
        if (vk != android.view.KeyEvent.KEYCODE_VOLUME_UP && vk != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (db.getSetting("reader_volume_flip") == "0") return false
        val sv = sc ?: return false
        val page = (sv.height * 0.85).toInt()
        sv.smoothScrollBy(0, if (vk == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) page else -page)
        return true
    }

    private var readSessionStart = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readSessionStart = System.currentTimeMillis()
    }

    /** 累计阅读时长：离开阅读器时落库 */
    private fun flushReadTime() {
        if (readSessionStart > 0) {
            val delta = System.currentTimeMillis() - readSessionStart
            readSessionStart = 0L
            if (delta in 1000..(6 * 3600_000L)) {  // 忽略<1s噪声与异常超时
                try { db.addReadTime(bookId, delta) } catch (e: Exception) {}
            }
        }
    }

    override fun onDetachedFromWindow() {
        flushReadTime()
        super.onDetachedFromWindow()
        closePdf()
    }
}
