package app.yeshu.reader

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import app.yeshu.reader.ai.AiProfileStore
import app.yeshu.reader.ai.DocumentAiService
import app.yeshu.reader.ai.SavedAiProfile
import app.yeshu.reader.parse.Block
import app.yeshu.reader.parse.DocParser
import app.yeshu.reader.parse.ParseException
import app.yeshu.reader.parse.ParsedDoc
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

class ReaderView(
    private val act: Activity,
    private val bookId: Long,
    private val showDocumentTabs: Boolean = true
) : FrameLayout(act) {

    companion object {
        private const val SYS_PROMPT = "你是专业的中文阅读助手。用简体中文回答，输出简洁、结构化。"
        private const val CHUNK = 300
        /** 按需续载上限：恢复位置/跳转时最多预渲染 PRELOAD_MAX_CHUNKS*CHUNK 块，避免超长文档一次性铺满 */
        private const val PRELOAD_MAX_CHUNKS = 120
        /** 单次阅读会话计入时长上限：异常超长会话按上限计入，不再整段丢弃 */
        private const val READ_SESSION_CAP_MS = 6 * 3600_000L
        /** PDF 位图缓存预算：按字节回收，避免几百页文档常驻上百 MB */
        private const val PDF_BITMAP_CACHE_BYTES = 48L * 1024 * 1024
        private const val PDF_DEFAULT_PAGE_RATIO = 1.4142f
        private const val PDF_SIZE_SCAN_BATCH = 12
        /** PDF/图片图像发送确认：用户确认过一次后不再重复询问 */
        private const val SETTING_VISION_SEND_CONFIRMED = "ai_vision_send_confirmed"
        /** AI 结果里的 Markdown 标题强调色（与引用链接同色系） */
        private val AI_HEAD_ACCENT = Accent.primary

        /** 重点（高亮）用的笔记 kind；颜色 id 存在 notes.status 列里。 */
        private const val HIGHLIGHT_KIND = "highlight"
        private const val HIGHLIGHT_AMBER = "amber"
        private const val HIGHLIGHT_GREEN = "green"
        private const val HIGHLIGHT_BLUE = "blue"
        private const val HIGHLIGHT_ROSE = "rose"

        /** 记住上次使用的 AI 动作，供菜单里的「重复上次」使用。 */
        private const val AI_LAST_ACTION_KEY = "ai_last_action"

        /** 每本书的最近阅读位置标签，书架条目读取它显示「第 N 章 / 第 N 页」。 */
        fun positionSettingKey(bookId: Long): String = "reader_last_position_$bookId"
        private val BOLD_RE = Regex("\\*\\*(.+?)\\*\\*")
        /** pptx 幻灯片标题形如「第 3 张幻灯片」「— 第 3 页 —」，与 DocumentAiService 的识别规则一致 */
        private val PPT_SLIDE_RE = Regex("^—?\\s*第\\s*(\\d+)\\s*页\\s*—?$")
        private val MD_HEADING_RE = Regex("^#{1,6}\\s+.+$")
        /** 结果里的可跳转引用，例如 [PARAGRAPH:12]、[PAGE:3] */
        private val CITATION_RE = Regex("\\[(PAGE|SLIDE|CHAPTER|PARAGRAPH):(\\d+)]")

        // 三套阅读配色：背景、正文和标题必须作为一个主题整体切换。
        val LIGHT_BG = Color.parseColor("#F7F8FB")
        val LIGHT_TEXT = Color.parseColor("#20232A")
        val LIGHT_HEAD = Color.parseColor("#4055B8")
        val PAPER_BG = Color.parseColor("#F4EDDE")
        val PAPER_TEXT = Color.parseColor("#312B24")
        val PAPER_HEAD = Color.parseColor("#8A4B2A")
        val NIGHT_BG = Color.parseColor("#111318")
        val NIGHT_TEXT = Color.parseColor("#D7DAE2")
        val NIGHT_HEAD = Color.parseColor("#AAB7FF")
    }

    private enum class ReaderTheme(val key: String, val title: String, val subtitle: String) {
        SYSTEM("system", "跟随系统", "随系统外观自动切换"),
        LIGHT("light", "浅色", "清爽白底，适合明亮环境"),
        PAPER("paper", "纸张", "暖米色，适合长时间阅读"),
        DARK("dark", "深色", "低眩光，适合夜间阅读")
    }

    /** 「跟随系统」必须解析成具体主题后才能取色；其余主题原样返回。 */
    private fun resolveTheme(theme: ReaderTheme = readerTheme): ReaderTheme =
        if (theme == ReaderTheme.SYSTEM) {
            val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (night) ReaderTheme.DARK else ReaderTheme.LIGHT
        } else {
            theme
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

    /**
     * 单次流式 AI 任务的私有状态：每个对话框只取消自己的 token。
     * 之前用模块级单例，第二个任务会覆盖第一个的 token，第一个对话框的「停止」随之失效。
     */
    private class AiTask {
        val cancelled = AtomicBoolean(false)
        val token = AiClient.CancelToken()
        var dialog: android.app.Dialog? = null
    }

    /** Markdown 渲染结果：可上屏的 spans + 各标题的字符偏移（供章节 chip 跳转） */
    private class AiMarkdown(
        val text: SpannableStringBuilder,
        val sections: List<Pair<String, Int>>
    )

    /** 同一时刻只允许一个 AI 流式任务，避免两个对话框并存、状态互相覆盖 */
    private var activeAiTask: AiTask? = null
    private val pal by lazy { LegacyPalette.of(act) }

    // 文本分段渲染状态
    private var boxRef: LinearLayout? = null
    private var renderedUpTo = 0

    // 沉浸模式：上下栏引用与状态
    private var topBar: android.view.View? = null
    private var documentBar: android.view.View? = null
    private var bottomBar: android.view.View? = null
    private var progBar: android.view.View? = null
    private var themeCell: FrameLayout? = null
    private var bookmarkCell: FrameLayout? = null
    private var barsHidden = false

    /** 已划重点的块下标 → 颜色 id。只存 id，色值按当前阅读主题解析（见 highlightColorOf）。 */
    private val highlightColors = mutableMapOf<Int, String>()

    /** 已存在的书签锚点。滚动事件是高频路径，不能每帧去查一次 Room。 */
    private val bookmarkAnchors = mutableSetOf<String>()
    private var lastBookmarkAnchor = ""

    /** 排版参数：从 settings 读入，改动后立即重排并还原阅读位置。 */
    private var lineSpacingFactor = 1.38f
    private var marginDp = 20

    /** 由宿主传入的一次性跳转目标（笔记/成果里的引用锚点）；消费一次即清空。 */
    var pendingAnchor: String = ""
        private set

    /**
     * 进入阅读器后按锚点定位一次（来自笔记中枢的引用 chip）。
     * 锚点可能带方括号或空格（笔记列表里统一展示成 `[CHAPTER:3]`），这里统一解析。
     */
    fun jumpToPendingAnchor() {
        val raw = pendingAnchor
        pendingAnchor = ""
        if (raw.isBlank()) return
        val match = Regex("([A-Za-z]+)\\s*:\\s*(\\d+)").find(raw) ?: return
        val type = match.groupValues[1].uppercase()
        val index = match.groupValues[2].toIntOrNull() ?: return
        jumpToCitation(type, index)
    }

    /** 设置一次待跳转锚点；宿主在视图挂载后调用 [jumpToPendingAnchor]。 */
    fun setPendingAnchor(anchor: String) {
        pendingAnchor = anchor
    }

    /** 点正文呼出/隐藏工具栏（iBooks 式沉浸阅读） */
    private fun toggleBars() {
        val t = topBar ?: return
        val tabs = documentBar
        val b = bottomBar ?: return
        val pr = progBar
        barsHidden = !barsHidden
        if (barsHidden) {
            pr?.visibility = View.GONE
            tabs?.visibility = View.GONE
            t.animate().translationY(-t.height.toFloat()).setDuration(170)
                .withEndAction { t.visibility = View.GONE }.start()
            b.animate().translationY(b.height.toFloat()).setDuration(170)
                .withEndAction { b.visibility = View.GONE }.start()
        } else {
            t.visibility = View.VISIBLE
            tabs?.visibility = View.VISIBLE
            b.visibility = View.VISIBLE
            pr?.visibility = View.VISIBLE
            t.translationY = -t.height.toFloat()
            b.translationY = b.height.toFloat()
            t.animate().translationY(0f).setDuration(190).start()
            b.animate().translationY(0f).setDuration(190).start()
        }
    }
    private var styleSp = 17f
    private var readerTheme = ReaderTheme.PAPER

    // PDF 惰性渲染状态
    private var pdfRenderer: PdfRenderer? = null
    private var pdfSession: PdfSession? = null
    private var pdfPageCount = 0
    private val pageBitmaps = SparseArray<Bitmap>()
    private var pageViews: Array<PdfPageView?> = emptyArray()
    private var pageRenderPending = BooleanArray(0)
    private var pdfBody: LinearLayout? = null
    // PDF 页面累计偏移缓存：滚动时二分定位可见页，替代每帧遍历全部页视图
    private var pdfTops = IntArray(0)
    private var pdfTopsDirty = true
    private var pdfTopsWidth = -1
    private var pdfBitmapBytes = 0L
    private var imageBitmap: Bitmap? = null

    /** 图片集（CBZ）已解出的页文件与位图，分离时统一回收。 */
    private var archivePageFiles: List<File> = emptyList()
    private val archiveBitmaps = mutableListOf<Bitmap>()
    private var imageView: ImageView? = null

    // 长按连发循环：detach 时必须移除已排队的回调，否则空转并持有整棵视图树
    private var repeatHandler: android.os.Handler? = null
    private val repeatLoops = mutableListOf<Runnable>()

    private class PdfPageView(context: Context) : ImageView(context) {
        private var heightToWidth = PDF_DEFAULT_PAGE_RATIO

        fun setPageSize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val next = height.toFloat() / width.toFloat()
            if (kotlin.math.abs(next - heightToWidth) < 0.0001f) return
            heightToWidth = next
            requestLayout()
        }

        fun estimatedHeight(containerWidth: Int): Int =
            max(1, (containerWidth.coerceAtLeast(1) * heightToWidth).toInt())

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
                else -> suggestedMinimumWidth
            }
            val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(1)
            val desiredHeight =
                (contentWidth * heightToWidth).toInt() + paddingTop + paddingBottom
            setMeasuredDimension(
                measuredWidth,
                resolveSize(max(suggestedMinimumHeight, desiredHeight), heightMeasureSpec)
            )
        }
    }

    private class PdfSession(val renderer: PdfRenderer, private val descriptor: ParcelFileDescriptor) {
        private val closed = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "reader-pdf").apply { isDaemon = true }
        }

        fun isClosed(): Boolean = closed.get()

        fun execute(block: (PdfRenderer) -> Unit): Boolean {
            if (closed.get()) return false
            return try {
                executor.execute {
                    if (!closed.get()) block(renderer)
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        fun renderPages(indices: List<Int>, pageCount: Int, maxWidth: Int): List<Bitmap> {
            if (closed.get()) return emptyList()
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return emptyList()
            val future = try {
                executor.submit<List<Bitmap>> {
                    if (closed.get()) return@submit emptyList()
                    val out = mutableListOf<Bitmap>()
                    for (index in indices.distinct().filter { it in 0 until pageCount }) {
                        if (closed.get()) break
                        var bitmap: Bitmap? = null
                        try {
                            renderer.openPage(index).use { page ->
                                val width = min(maxWidth, page.width).coerceAtLeast(1)
                                val height = max(1, page.height * width / page.width)
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap!!.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                            }
                            bitmap?.let(out::add)
                        } catch (_: Exception) {
                            bitmap?.recycle()
                        }
                    }
                    out
                }
            } catch (_: RejectedExecutionException) {
                return emptyList()
            }
            return try {
                future.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try { renderer.close() } catch (_: Exception) {}
                    closeDescriptor()
                }
            } catch (_: RejectedExecutionException) {
                try { renderer.close() } catch (_: Exception) {}
                closeDescriptor()
            } finally {
                executor.shutdown()
            }
        }

        /** PdfRenderer 不持有 fd 所有权：渲染器关闭后再关描述符，重复关闭按空操作容忍 */
        private fun closeDescriptor() {
            try { descriptor.close() } catch (_: Exception) {}
        }
    }

    init {
        val book = db.getBook(bookId)
        if (book != null) {
            setup(book)
        } else {
            post { (act as MainActivity).showShelf() }
        }
    }

    private fun loadReaderTheme() {
        val explicit = db.getSetting("reader_theme")
            ?.let { key -> ReaderTheme.entries.firstOrNull { it.key == key } }
        readerTheme = explicit ?: run {
            val followsSystem = db.getSetting("night_follow_sys") == "1"
            val systemDark =
                (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            when {
                followsSystem && systemDark -> ReaderTheme.DARK
                db.getSetting("night_mode") == "1" -> ReaderTheme.DARK
                else -> ReaderTheme.PAPER
            }
        }
    }

    private fun themeBackground(theme: ReaderTheme = readerTheme): Int = when (resolveTheme(theme)) {
        ReaderTheme.LIGHT, ReaderTheme.SYSTEM -> LIGHT_BG
        ReaderTheme.PAPER -> PAPER_BG
        ReaderTheme.DARK -> NIGHT_BG
    }

    private fun themeText(theme: ReaderTheme = readerTheme): Int = when (resolveTheme(theme)) {
        ReaderTheme.LIGHT, ReaderTheme.SYSTEM -> LIGHT_TEXT
        ReaderTheme.PAPER -> PAPER_TEXT
        ReaderTheme.DARK -> NIGHT_TEXT
    }

    private fun themeHeading(theme: ReaderTheme = readerTheme): Int = when (resolveTheme(theme)) {
        ReaderTheme.LIGHT, ReaderTheme.SYSTEM -> LIGHT_HEAD
        ReaderTheme.PAPER -> PAPER_HEAD
        ReaderTheme.DARK -> NIGHT_HEAD
    }

    /** PDF 与图片本身不改色，只让画布边缘跟随阅读主题。 */
    private fun themeStage(theme: ReaderTheme = readerTheme): Int = when (resolveTheme(theme)) {
        ReaderTheme.LIGHT, ReaderTheme.SYSTEM -> Color.parseColor("#DDE1E8")
        ReaderTheme.PAPER -> Color.parseColor("#C9BEAA")
        ReaderTheme.DARK -> Color.parseColor("#24272E")
    }

    /** 深色半透明渐变 + 细描边；模糊后的壁纸透出时形成稳定的毛玻璃层次。 */
    private fun chromeSurface(radiusDp: Int, alpha: Int = 224): android.graphics.drawable.GradientDrawable {
        val d = density(act)
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(alpha, 29, 33, 48),
                Color.argb((alpha - 12).coerceAtLeast(0), 11, 14, 24)
            )
        ).apply {
            cornerRadius = Glass.dp(radiusDp, d).toFloat()
            setStroke(Glass.dp(1, d), Color.argb(52, 255, 255, 255))
        }
    }

    private fun chromeChip(selected: Boolean): android.graphics.drawable.GradientDrawable {
        val d = density(act)
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            if (selected) {
                intArrayOf(Color.parseColor("#CC5B5FF5"), Color.parseColor("#B56D55E8"))
            } else {
                intArrayOf(Color.argb(22, 255, 255, 255), Color.argb(10, 255, 255, 255))
            }
        ).apply {
            cornerRadius = Glass.dp(14, d).toFloat()
            setStroke(
                Glass.dp(1, d),
                if (selected) Color.argb(92, 210, 218, 255) else Color.argb(20, 255, 255, 255)
            )
        }
    }

    private fun themeIcon(theme: ReaderTheme = readerTheme): String = when (theme) {
        ReaderTheme.SYSTEM -> "sliders"
        ReaderTheme.LIGHT -> "sun"
        ReaderTheme.PAPER -> "book"
        ReaderTheme.DARK -> "moon"
    }

    private fun renderToolCell(
        container: FrameLayout,
        icon: String? = null,
        glyph: String? = null,
        label: String,
        accent: Boolean = false
    ) {
        val d = density(act)
        container.removeAllViews()
        val primary = if (accent) Accent.chromeAccentText else Accent.chromeTextMuted
        val inner = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (icon != null) {
            inner.addView(
                IconView(act, icon, 19, primary),
                LinearLayout.LayoutParams(Glass.dp(21, d), Glass.dp(21, d))
            )
        } else {
            inner.addView(TextView(act).apply {
                text = glyph.orEmpty()
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(primary)
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, Glass.dp(21, d)))
        }
        inner.addView(TextView(act).apply {
            text = label
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(if (accent) Accent.chromeAccentTextSoft else Accent.chromeTextSecondary)
            setTypeface(null, if (accent) Typeface.BOLD else Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, Glass.dp(17, d)))
        container.addView(inner, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    private fun refreshThemeCell() {
        themeCell?.let { cell ->
            cell.background = chromeChip(selected = true)
            cell.contentDescription = "阅读主题：${readerTheme.title}"
            renderToolCell(cell, icon = themeIcon(), label = readerTheme.title, accent = true)
        }
    }

    private fun setup(book: Book) {
        val d = density(act)
        // 统一使用 reader_brightness，兼容读取一次旧键后立即清理。
        val savedBrightness = db.getSetting("reader_brightness")
            ?: db.getSetting("screen_bright")?.also {
                db.setSetting("reader_brightness", it)
                db.deleteSetting("screen_bright")
            }
        savedBrightness?.toIntOrNull()?.takeIf { it in 10..100 }?.let { pct ->
            // 宿主可能在 IO 线程构造本视图（YeshuApp 的 withContext(Dispatchers.IO)），
            // 改窗口亮度属于主线程契约，延后到 post 执行。
            post {
                try {
                    val lp = act.window.attributes
                    lp.screenBrightness = pct / 100f
                    act.window.attributes = lp
                } catch (_: Exception) {}
            }
        }
        val f = File(act.filesDir, book.fileName)
        bookFormat = book.format.ifBlank { DocParser.detect(book.fileName) }
        // 兜底：早期版本把 PDF 误存为 txt，按扩展名纠正
        if (bookFormat != "pdf" && book.fileName.lowercase().endsWith(".pdf")) bookFormat = "pdf"
        styleSp = db.getSetting("reader_font_sp")?.toFloatOrNull()?.coerceIn(12f, 26f) ?: 17f
        loadReaderTheme()

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
        // 系统栏 inset 与窗口属性都必须在主线程请求，这里延后到 post（见下方 applySystemBarInsets）。

        // 顶栏：紧凑悬浮玻璃条，左右等宽使书名真正居中。
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chromeSurface(radiusDp = 20)
            elevation = Glass.dp(8, d).toFloat()
            setPadding(Glass.dp(7, d), Glass.dp(6, d), Glass.dp(7, d), Glass.dp(6, d))
        }
        val back = FrameLayout(act).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(35, 255, 255, 255))
                setStroke(Glass.dp(1, d), Color.argb(42, 255, 255, 255))
            }
            foreground = Glass.pressFx()
            contentDescription = "返回书架"
            layoutParams = LinearLayout.LayoutParams(Glass.dp(40, d), Glass.dp(40, d))
            addView(IconView(act, "back", 20), FrameLayout.LayoutParams(Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER))
            setOnClickListener { saveProgress(); closePdf(); (act as MainActivity).showShelf() }
        }
        top.addView(back)
        // 居中书名 + 当前章节副标题（滚动联动）
        val titleTv = TextView(act).apply {
            text = book.title
            textSize = 16f
            setTextColor(Accent.chromeTextPrimary)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val curHeadTv = TextView(act).apply {
            text = book.author.ifBlank { "继续阅读" }
            textSize = 10f
            setTextColor(Accent.chromeTextSecondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val titleCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(titleTv)
            addView(curHeadTv, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = Glass.dp(1, d) })
        }
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(act), LinearLayout.LayoutParams(Glass.dp(40, d), Glass.dp(40, d)))
        col.addView(top, LayoutParams(-1, -2).also { lp ->
            lp.setMargins(Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d), Glass.dp(5, d))
        })

        // 文档二层工作台原先是一条与底部工具坞并列的横栏（阅读/目录/AI/笔记），
        // 两条栏权重相同且「目录」重复出现，用户不知道该点哪条。
        // 现在统一收进下方唯一的工具坞，这里只保留进度条位置。
        documentBar = null

        val sv = ScrollView(act).apply {
            isFillViewport = true
            setBackgroundColor(if (bookFormat == "pdf" || isImageFormat(bookFormat) || isImageArchive(bookFormat)) themeStage() else themeBackground())
        }
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        sc = sv

        try {
            when {
                bookFormat == "pdf" -> setupPdf(f, sv)
                isImageArchive(bookFormat) -> setupImageArchive(f, sv)
                isImageFormat(bookFormat) -> setupImage(f, sv)
                else -> setupBlocks(f, sv)
            }
        } catch (e: Exception) {
            val err = TextView(act).apply {
                text = "打开失败：${e.message ?: e.javaClass.simpleName}"
                textSize = 15f
                setTextColor(Color.parseColor("#AA3333"))
                setPadding(Glass.dp(20, d), Glass.dp(24, d), Glass.dp(20, d), Glass.dp(20, d))
            }
            sv.addView(err, LayoutParams(-1, -2))
        }

        // 阅读进度细条（底部工具坞上方）
        val prog = Glass.progressTrack(act)
        progBar = prog
        // 3dp：2dp 在低密度屏上低于可感知阈值
        col.addView(prog, LayoutParams(-1, Glass.dp(3, d)).also { lp ->
            lp.setMargins(Glass.dp(22, d), Glass.dp(5, d), Glass.dp(22, d), Glass.dp(3, d))
        })
        fun updProg() {
            // 与落库、顶栏共用同一进度口径：三者不一致时用户会以为进度条在骗人
            prog.background?.level = (currentProgress() * 10000).toInt()
        }
        sv.post { updProg() }

        sv.viewTreeObserver.addOnScrollChangedListener {
            renderPdfWindow()
            maybeAppend()
            updProg()
            syncBookmarkCell()
            hasInteracted = true
            // 顶栏当前章名联动（微信读书式方位感）+ 实时百分比
            // 顶栏副标题：始终显示「真实位置 · 统一百分比」。
            // 没有章节结构的 TXT / PDF / 图片集以前会长期停在一句无意义的格式串。
            val percent = (currentProgress() * 100).toInt()
            val (posIndex, posTotal) = progressPosition()
            val show = if (pdfRenderer != null || bookFormat == "pdf") {
                "PDF · 第 ${posIndex + 1}${if (posTotal > 0) " / $posTotal" else ""} 页 · $percent%"
            } else if (posIndex >= 0) {
                var name: String? = null
                for (h in tocHeads) { if (h.first <= posIndex) name = h.second else break }
                if (name.isNullOrBlank()) "第 ${posIndex + 1} / $posTotal 段 · $percent%"
                else "${name.take(20)} · $percent%"
            } else {
                null
            }
            if (show != null && curHeadTv.text != show) {
                curHeadTv.visibility = View.VISIBLE
                curHeadTv.text = show
            }
            val now = System.currentTimeMillis()
            if (now - lastSavedAt > 1500) {
                lastSavedAt = now
                saveProgress()
            }
        }

        // 底部悬浮工具坞：图标 + 短标签减少猜测，主题作为高层级入口常驻。
        val bottom = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = chromeSurface(radiusDp = 23)
            elevation = Glass.dp(10, d).toFloat()
            setPadding(Glass.dp(5, d), Glass.dp(5, d), Glass.dp(5, d), Glass.dp(5, d))
        }
        topBar = top
        bottomBar = bottom
        fun toolCell(
            icon: String? = null,
            glyph: String? = null,
            label: String,
            description: String = label,
            onClick: () -> Unit
        ): FrameLayout {
            val cell = FrameLayout(act).apply {
                layoutParams = LinearLayout.LayoutParams(0, Glass.dp(52, d), 1f).also {
                    it.marginStart = Glass.dp(1, d)
                    it.marginEnd = Glass.dp(1, d)
                }
                foreground = Glass.pressFx()
                contentDescription = description
                setOnClickListener { onClick() }
            }
            renderToolCell(cell, icon = icon, glyph = glyph, label = label)
            return cell
        }
        // 唯一的一条底部工具坞：本地动作（搜索/目录/书签/主题设置）与联网动作（AI/笔记）分区排布。
        // 先加第一格：搜索。
        bottom.addView(toolCell(icon = "search", label = "搜索", description = "书内搜索") { searchInBook() })
        // 书签：点击在当前位置添加/取消，长按打开书签列表。这是本地动作，不联网。
        bookmarkCell = toolCell(icon = "book", label = "书签", description = "在当前位置添加或取消书签；长按查看全部书签") { toggleBookmark() }
        bookmarkCell?.setOnLongClickListener { showBookmarkList(); true }
        bottom.addView(bookmarkCell)
        // 窄屏（<400dp）平分到 5 格以上时每格不足 48dp 无障碍最小热区，
        // 因此只保留 4 个高频格 + 一个「更多」，其余动作进溢出弹层。
        val narrow = act.resources.configuration.screenWidthDp < 400
        if (showDocumentTabs) {
            // 宽屏下目录/AI/笔记已在侧栏常驻，这里不再重复；窄屏必须有入口。
            bottom.addView(toolCell(icon = "list", label = "目录", description = "文档目录") { listToc() })
            bottom.addView(toolCell(icon = "note", label = "AI", description = "AI 助手：理解包、问答与自测") { showAiMenu() })
            if (narrow) {
                bottom.addView(toolCell(icon = "more", label = "更多", description = "更多动作：笔记 / 阅读设置 / 亮度") { showMoreActions() })
            } else {
                bottom.addView(toolCell(icon = "folder", label = "笔记", description = "本书笔记") { (act as MainActivity).showNotes(bookId) })
            }
        }
        if (!narrow) {
            val tc = toolCell(icon = themeIcon(), label = readerTheme.title, description = "阅读设置：主题 / 亮度 / 行距 / 字号") {
                showReaderSettings()
            }
            themeCell = tc
            refreshThemeCell()
            bottom.addView(tc)
        }
        // 字号独立成格保留连发手感，同时把行距/边距收进「阅读设置」面板。
        if (!narrow) {
            val inc = toolCell(glyph = "A+", label = "字号", description = "增大字号") { applyFontSp(styleSp + 1f) }
            setupRepeatable(inc) { applyFontSp(styleSp + 1f) }
            bottom.addView(inc)
        }
        col.addView(bottom, LayoutParams(-1, -2).also { lp ->
            lp.setMargins(Glass.dp(10, d), Glass.dp(2, d), Glass.dp(10, d), Glass.dp(8, d))
        })
        // 底部导航栏 inset 交给工具坞承担（进度条在它上方，一并抬高），
        // 否则三键导航机型上工具坞与进度条会被系统栏盖住；同时回到主线程请求 inset。
        post { col.applySystemBarInsets(bottom) }
    }

    /** 长按连发：按下 380ms 后每 130ms 重复执行；长按后的 click 被吞掉防双重 */
    private fun setupRepeatable(v: android.view.View, action: () -> Unit) {
        val h = repeatHandler
            ?: android.os.Handler(android.os.Looper.getMainLooper()).also { repeatHandler = it }
        var longFired = false
        val loop = object : Runnable {
            override fun run() {
                if (!v.isAttachedToWindow) return  // view 已 detach，停止连发
                action()
                h.postDelayed(this, 130)
            }
        }
        repeatLoops += loop
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
        val doc = DocParser.parseText(f)
        // 图片型文档（漫画包、纯图片的固定版式 EPUB）解析出的是一份页清单，
        // 正文只有一行摘要；必须改走页渲染，否则用户只看到「共 N 页」这句提示。
        if (doc.pageEntries.isNotEmpty()) {
            renderImageArchive(f, sv, doc)
            return
        }
        docBlocks = doc.blocks
        tocHeads = doc.blocks.mapIndexedNotNull { i, b ->
            if (b.type == Block.HEADING) i to b.text else null
        }
        loadBookmarks()
        docFullText = doc.fullText
        // 排版参数与已划重点必须在渲染块之前就位，否则首屏块会用默认行距/无色渲染
        lineSpacingFactor = db.getSetting("reader_line_spacing")?.toFloatOrNull()?.coerceIn(1.0f, 2.0f) ?: 1.38f
        marginDp = db.getSetting("reader_margin_dp")?.toIntOrNull()?.coerceIn(8, 40) ?: 20
        loadHighlights()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeBackground())
            setPadding(0, Glass.dp(14, d), 0, 0)
        }
        boxRef = box
        renderedUpTo = 0
        // 底部留白（常驻末位，分块插入到它之前）：高度本身就是留白，
        // 原先 60dp 的 bottom padding 会被 40dp 的固定高度裁掉，这里直接给足高度。
        val pad = View(act)
        box.addView(pad, LayoutParams(-1, Glass.dp(60, d)))
        sv.addView(box, LayoutParams(-1, -2))
        appendChunk(CHUNK)

        // 恢复进度时按需预载足够多的块，保证目标位置已渲染。
        // 不能只看「已渲染块占比 >= p」：CHUNK 粒度下会停在前缀里，比例套到截断高度上会跳错位置。
        val total = doc.blocks.size
        val p = db.getBook(bookId)?.progress ?: 0f
        val restoring = p > 0.001f && p < 0.999f
        // 恢复用 ProgressModel 的逆映射，与 saveProgress 严格互逆，往返不再漂移
        val restoreIndex = ProgressModel.indexForProgress(p, total)
        if (restoring && total > 0) {
            ensureRenderedUpTo(restoreIndex, PRELOAD_MAX_CHUNKS)
        }

        sv.post {
            if (!restoring) return@post
            val applyRestore = Runnable {
                try {
                    // 直接按目标块的真实 top 定位，不再把比例套到截断高度上
                    val target = box.getChildAt(restoreIndex)
                    if (target != null) sv.scrollTo(0, target.top.coerceAtLeast(0))
                } catch (e: Exception) {}
            }
            if (box.height > 0) {
                applyRestore.run()
            } else {
                // 首次布局尚未完成时 box.height 仍是 0，直接滚会退回文首，等一次布局再恢复
                box.viewTreeObserver.addOnGlobalLayoutListener(
                    object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            val observer = box.viewTreeObserver
                            if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                            applyRestore.run()
                        }
                    }
                )
            }
        }
    }

    /**
     * 按需续载直到 [index] 已渲染；返回是否真正渲染到位。
     * 上限 [maxChunks] 用于兜住超长文档，避免一次性铺满整本书。
     */
    private fun ensureRenderedUpTo(index: Int, maxChunks: Int): Boolean {
        val total = docBlocks?.size ?: return false
        if (pdfRenderer != null || total <= 0 || index < 0) return false
        var guard = 0
        while (renderedUpTo <= index && renderedUpTo < total && guard++ < maxChunks) {
            appendChunk(CHUNK)
        }
        return renderedUpTo > index
    }

    /**
     * 当前可见块索引：块高差异很大，用滚动比例反推会指错章节，统一按真实布局位置查找。
     * 子 View 的 bottom 随索引单调不减，二分查找即可，避免每帧遍历上万个子 View。
     */
    private fun visibleBlockIndex(): Int {
        val box = boxRef ?: return -1
        val sv = sc ?: return -1
        if (box.height <= 0 || renderedUpTo <= 0) return -1
        val last = min(renderedUpTo, box.childCount) - 1
        if (last < 0) return -1
        val y = sv.scrollY
        var lo = 0
        var hi = last
        var ans = last
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if ((box.getChildAt(mid)?.bottom ?: 0) > y) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    /** 布局尚未完成时的兜底：按滚动比例估算块索引 */
    private fun estimatedBlockIndex(): Int {
        val box = boxRef ?: return -1
        val sv = sc ?: return -1
        val total = docBlocks?.size ?: return -1
        val range = box.height - sv.height
        if (range <= 0 || total <= 0) return -1
        return (sv.scrollY.toFloat() / range * (total - 1)).toInt().coerceIn(0, total - 1)
    }

    /** 当前阅读位置所在块：优先真实可见块，布局未就绪时退回比例估算 */
    private fun currentBlockIndex(): Int {
        val real = visibleBlockIndex()
        return if (real >= 0) real else estimatedBlockIndex()
    }

    private fun makeBlockView(b: Block, index: Int): TextView {
        val d = density(act)
        val tv = TextView(act)
        if (b.type == Block.HEADING) {
            tv.tag = "head"
            tv.text = b.text
            tv.textSize = styleSp + 4f
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextColor(themeHeading())
            tv.setLineSpacing(Glass.dp(3, d).toFloat(), 1.15f)
            tv.setPadding(Glass.dp(20, d), Glass.dp(26, d), Glass.dp(20, d), Glass.dp(10, d))
            // 标题同样参与沉浸切换
            tv.setOnClickListener { toggleBars() }
        } else {
            tv.text = applyHighlightSpan(b.text, index)
            tv.textSize = styleSp
            tv.setTextColor(themeText())
            // 成熟阅读器共识：1.38 倍行距最舒适
            tv.setLineSpacing(0f, lineSpacingFactor)
            tv.setPadding(Glass.dp(marginDp, d), Glass.dp(6, d), Glass.dp(marginDp, d), Glass.dp(6, d))
            if (b.text.length > 4) {
                // 长按菜单要写回块锚点，必须把块下标一并带进去（收藏金句需要可定位的来源）
                tv.setOnLongClickListener { explainBlock(b.text, index); true }
                // 单击段落 = 切换沉浸模式（iBooks 式）
                tv.setOnClickListener { toggleBars() }
            }
        }
        return tv
    }

    /**
     * 给已划重点的块加背景色。
     *
     * 颜色只存 id（amber/green/blue/rose），实际色值按当前阅读主题解析：
     * 同一个 id 在浅色底和深色底上必须都保持正文可读，不能直接存死色值。
     */
    private fun applyHighlightSpan(text: String, index: Int): CharSequence {
        val colorId = highlightColors[index] ?: return text
        val color = highlightColorOf(colorId) ?: return text
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.BackgroundColorSpan(color),
                0, length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 高亮底色的解析：深色主题用低透明高饱和，浅色主题用低饱和浅底。 */
    private fun highlightColorOf(colorId: String): Int? {
        val dark = resolveTheme() == ReaderTheme.DARK
        return when (colorId) {
            HIGHLIGHT_AMBER -> if (dark) Color.argb(90, 255, 196, 84) else Color.parseColor("#FBEFC9")
            HIGHLIGHT_GREEN -> if (dark) Color.argb(90, 126, 224, 168) else Color.parseColor("#D8F1DE")
            HIGHLIGHT_BLUE -> if (dark) Color.argb(90, 140, 178, 255) else Color.parseColor("#DBE6FF")
            HIGHLIGHT_ROSE -> if (dark) Color.argb(90, 255, 154, 180) else Color.parseColor("#FBDDE6")
            else -> null
        }
    }

    /** 重新给所有已渲染的块上色（划线/取消后即时生效，不重建界面）。 */
    private fun refreshHighlights() {
        val box = boxRef ?: return
        val blocks = docBlocks ?: return
        for (i in 0 until min(renderedUpTo, box.childCount)) {
            val view = box.getChildAt(i) as? TextView ?: continue
            if (view.tag == "head") continue
            val block = blocks.getOrNull(i) ?: continue
            view.text = applyHighlightSpan(block.text, i)
        }
    }

    /** 就地调整字号：不重建界面、不丢滚动位置 */
    private fun applyFontSp(newSp: Float) {
        val clamped = newSp.coerceIn(12f, 26f)
        if (clamped == styleSp) return
        styleSp = clamped
        db.setSetting("reader_font_sp", styleSp.toString())
        applyTypography()
    }

    /**
     * 就地应用排版参数（字号/行距/页边距），不重建界面、不丢滚动位置。
     *
     * 三者会同时改变块高，所以必须共用同一套「记住可见块 + 像素偏移」的还原逻辑，
     * 否则改行距时用字号那套锚点会甩走几百行。
     */
    private fun applyTypography() {
        if (bookFormat == "pdf" || pdfRenderer != null) return
        val box = boxRef ?: return
        val d = density(act)
        val anchorIdx = visibleBlockIndex()
        val anchorOffset = if (anchorIdx >= 0) {
            (box.getChildAt(anchorIdx)?.top ?: 0) - (sc?.scrollY ?: 0)
        } else 0
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i) as? TextView ?: continue
            if (v.tag == "head") {
                v.textSize = styleSp + 4f
                v.setPadding(Glass.dp(marginDp, d), Glass.dp(26, d), Glass.dp(marginDp, d), Glass.dp(10, d))
            } else {
                v.textSize = styleSp
                v.setLineSpacing(0f, lineSpacingFactor)
                v.setPadding(Glass.dp(marginDp, d), Glass.dp(6, d), Glass.dp(marginDp, d), Glass.dp(6, d))
            }
        }
        if (anchorIdx >= 0) {
            // 重排是一次 requestLayout，必须等布局完成后再按锚点还原滚动位置
            box.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val observer = box.viewTreeObserver
                        if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                        val sv = sc ?: return
                        val v = box.getChildAt(anchorIdx) ?: return
                        sv.scrollTo(0, (v.top - anchorOffset).coerceAtLeast(0))
                    }
                }
            )
        }
    }

    /** 三套主题即时切换：正文背景、正文文字和标题同时刷新。 */
    private fun applyReaderTheme(theme: ReaderTheme) {
        readerTheme = theme
        // 只记住阅读器自己的主题键：night_mode / night_follow_sys 属于全局外观设置，
        // 在这里回写会静默覆盖设置页里的全局主题（Compose 侧走 DataStore）。
        db.setSetting("reader_theme", theme.key)
        refreshThemeCell()

        val textBox = boxRef
        if (textBox != null) {
            textBox.setBackgroundColor(themeBackground())
            sc?.setBackgroundColor(themeBackground())
            for (i in 0 until textBox.childCount) {
                val view = textBox.getChildAt(i)
                if (view is TextView) {
                    view.setTextColor(if (view.tag == "head") themeHeading() else themeText())
                }
            }
        } else {
            sc?.setBackgroundColor(themeStage())
            pdfBody?.setBackgroundColor(themeStage())
            (sc?.getChildAt(0) as? ImageView)?.setBackgroundColor(themeStage())
        }
    }

    /**
     * 阅读设置面板：主题（含跟随系统）、行距、页边距、字号、亮度。
     *
     * 全部即时生效并写 settings 表。之前只有「主题」一个面板，行距写死 1.38、
     * 边距写死 20dp、亮度独占一格——长时间阅读最常调的几项反而无处可调。
     */
    private fun showReaderSettings() {
        val d = density(act)
        val dialog = android.app.Dialog(act)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = chromeSurface(radiusDp = 24, alpha = 246)
            setPadding(Glass.dp(18, d), Glass.dp(18, d), Glass.dp(18, d), Glass.dp(12, d))
        }
        fun label(text: String, top: Int = 14) {
            panel.addView(TextView(act).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#AEB4C2"))
                setPadding(0, Glass.dp(top, d), 0, Glass.dp(6, d))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        /** 一行可选项：标题 + 副标题 + 选中勾。 */
        fun optionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = chromeChip(selected)
                foreground = Glass.pressFx()
                contentDescription = "$title：$subtitle"
                setPadding(Glass.dp(12, d), Glass.dp(8, d), Glass.dp(12, d), Glass.dp(8, d))
                setOnClickListener { onClick() }
            }
            row.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(act).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Color.parseColor("#F7F8FC"))
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                })
                addView(TextView(act).apply {
                    text = subtitle
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#AEB4C2"))
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(act).apply {
                text = if (selected) "✓" else ""
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Accent.chromeAccentText)
            }, LinearLayout.LayoutParams(Glass.dp(30, d), Glass.dp(30, d)))
            panel.addView(row, LinearLayout.LayoutParams(-1, Glass.dp(56, d)).also {
                it.bottomMargin = Glass.dp(6, d)
            })
        }

        panel.addView(TextView(act).apply {
            text = "阅读设置"
            textSize = 19f
            setTextColor(Color.parseColor("#F7F8FC"))
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(TextView(act).apply {
            text = "改动立即生效，重开这本书仍会保持"
            textSize = 12f
            setTextColor(Color.parseColor("#AEB4C2"))
            setPadding(0, Glass.dp(3, d), 0, Glass.dp(4, d))
        }, LinearLayout.LayoutParams(-1, -2))

        label("主题", top = 10)
        ReaderTheme.entries.forEach { option ->
            optionRow(option.title, option.subtitle, option == readerTheme) {
                applyReaderTheme(option)
                dialog.dismiss()
            }
        }

        label("行距")
        val spacingOptions = listOf(1.2f to "紧凑", 1.38f to "舒适", 1.6f to "宽松")
        optionRow("行距", spacingOptions.joinToString(" / ") { it.second } + "（当前 ${"%.2f".format(lineSpacingFactor)}）", false) {
            val next = spacingOptions[(spacingOptions.indexOfFirst { it.first == lineSpacingFactor }.let { if (it < 0) 1 else it } + 1) % spacingOptions.size]
            lineSpacingFactor = next.first
            db.setSetting("reader_line_spacing", lineSpacingFactor.toString())
            applyTypography()
            dialog.dismiss()
            toast("行距：${next.second}")
        }

        label("页边距")
        val marginOptions = listOf(12 to "窄", 20 to "中", 28 to "宽")
        optionRow("页边距", marginOptions.joinToString(" / ") { it.second } + "（当前 ${marginDp}dp）", false) {
            val current = marginOptions.indexOfFirst { it.first == marginDp }.let { if (it < 0) 1 else it }
            val next = marginOptions[(current + 1) % marginOptions.size]
            marginDp = next.first
            db.setSetting("reader_margin_dp", marginDp.toString())
            applyTypography()
            dialog.dismiss()
            toast("页边距：${next.second}")
        }

        label("字号")
        val fontRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun fontButton(text: String, onClick: () -> Unit): TextView = TextView(act).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F7F8FC"))
            background = chromeChip(false)
            foreground = Glass.pressFx()
            contentDescription = text
            layoutParams = LinearLayout.LayoutParams(0, Glass.dp(46, d), 1f).also {
                it.marginStart = Glass.dp(3, d)
                it.marginEnd = Glass.dp(3, d)
            }
            setOnClickListener {
                onClick()
                this@apply.text = text
            }
        }
        val minus = fontButton("A−") { applyFontSp(styleSp - 1f) }
        val current = TextView(act).apply {
            text = "字号 ${styleSp.toInt()}"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F7F8FC"))
        }
        val plus = fontButton("A+") {
            applyFontSp(styleSp + 1f)
            current.text = "字号 ${styleSp.toInt()}"
        }
        fontRow.addView(minus)
        fontRow.addView(current, LinearLayout.LayoutParams(0, -2, 1.4f))
        fontRow.addView(plus)
        panel.addView(fontRow, LinearLayout.LayoutParams(-1, -2))

        label("亮度")
        optionRow("阅读亮度", "单独调节阅读器亮度，不改系统亮度", false) {
            dialog.dismiss()
            brightnessDialog()
        }

        panel.addView(TextView(act).apply {
            text = "关闭"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Accent.chromeTextTertiary)
            background = chromeChip(selected = false)
            foreground = Glass.pressFx()
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, Glass.dp(42, d)).also {
            it.topMargin = Glass.dp(12, d)
        })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.56f }
        }
        dialog.show()
        val available = act.resources.displayMetrics.widthPixels - Glass.dp(32, d)
        dialog.window?.setLayout(min(available, Glass.dp(420, d)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 追加渲染下一块文本（插入到底部留白之前） */
    private fun appendChunk(n: Int) {
        val box = boxRef ?: return
        val blocks = docBlocks ?: return
        val end = min(blocks.size, renderedUpTo + n)
        for (i in renderedUpTo until end) {
            box.addView(makeBlockView(blocks[i], i), box.childCount - 1, LayoutParams(-1, -2))
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

    /** 单图格式（按图片通道渲染）。与 DocParser 的图片格式集合保持一致。 */
    private fun isImageFormat(format: String): Boolean =
        format.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** 图片集（漫画包）：按页纵向排列，与单图的渲染方式不同。 */
    private fun isImageArchive(format: String): Boolean = format.lowercase() == "cbz"

    /**
     * 图片集（CBZ）渲染：把压缩包里的图片页解到应用私有目录，然后按阅读顺序纵向排列。
     *
     * 用纵向长条而不是逐页翻页，是为了复用现有的滚动容器与进度计算——
     * 翻页模式需要另写一套手势与页码状态，收益不成比例。
     * 每页前面放一个页码标题，这样目录跳转与章节名都能工作。
     */
    private fun setupImageArchive(f: File, sv: ScrollView) {
        val doc = DocParser.parseText(f)
        if (doc.pageEntries.isEmpty()) throw ParseException("图片集内没有图片")
        renderImageArchive(f, sv, doc)
    }

    /** 图片型文档的页渲染：解包页面并按顺序纵向排列。 */
    private fun renderImageArchive(f: File, sv: ScrollView, doc: ParsedDoc) {
        val d = density(act)
        val dir = File(act.filesDir, "cbz_$bookId").apply { mkdirs() }
        val pages = mutableListOf<File>()
        ZipFile(f).use { zip ->
            doc.pageEntries.forEachIndexed { index, entryName ->
                val target = File(dir, "page_%05d%s".format(index, entryName.substringAfterLast('.').let { ".$it" }))
                if (!target.isFile || target.length() == 0L) {
                    val e = zip.getEntry(entryName) ?: return@forEachIndexed
                    // 单页上限：漫画单页通常几百 KB，超过 24MB 的一定不是正常页面
                    zip.getInputStream(e).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 24L * 1024 * 1024) throw ParseException("图片集内单页过大")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                pages += target
            }
        }
        if (pages.isEmpty()) throw ParseException("图片集内没有可读取的图片")

        docBlocks = pages.mapIndexed { i, _ -> Block(Block.HEADING, "第 ${i + 1} 页") }
        tocHeads = docBlocks!!.mapIndexed { i, b -> i to b.text }
        docFullText = "图片集：共 ${pages.size} 页"
        renderedUpTo = pages.size

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeStage())
        }
        boxRef = box
        sv.addView(box, LayoutParams(-1, -2))

        archivePageFiles = pages
        pages.forEachIndexed { index, page ->
            val label = TextView(act).apply {
                text = "第 ${index + 1} 页"
                textSize = 12f
                setTextColor(Color.argb(150, 255, 255, 255))
                setBackgroundColor(themeStage())
                setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(6, d))
            }
            box.addView(label, LayoutParams(-1, -2))
            val image = ImageView(act).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(themeStage())
                setOnClickListener { toggleBars() }
            }
            // 按屏宽下采样解码，避免一次性把整本漫画的原图读进内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1440) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                page.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
            if (bitmap != null) {
                archiveBitmaps += bitmap
                image.setImageBitmap(bitmap)
            } else {
                label.text = "第 ${index + 1} 页（无法解码）"
            }
            box.addView(image, LayoutParams(-1, -2))
        }
        val pad = View(act)
        box.addView(pad, LayoutParams(-1, Glass.dp(60, d)))

        // 恢复进度
        val p = db.getBook(bookId)?.progress ?: 0f
        if (p > 0.001f && p < 0.999f) {
            sv.post {
                val range = (box.height - sv.height).coerceAtLeast(0)
                sv.scrollTo(0, (range * p).toInt())
            }
        }
    }

    /** Large photos are sampled before decode so importing a camera image cannot exhaust RAM. */
    private fun setupImage(f: File, sv: ScrollView) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("无法读取图片")
        }
        var sample = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 4096) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw IllegalArgumentException("图片解码失败")
        imageBitmap = bitmap
        val image = ImageView(act).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(themeStage())
            setImageBitmap(bitmap)
            setOnClickListener { toggleBars() }
        }
        imageView = image
        sv.setBackgroundColor(themeStage())
        sv.addView(image, LayoutParams(-1, -2))
        restoreScroll(sv)
    }

    private fun setupPdf(f: File, sv: ScrollView) {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (t: Throwable) {
            try { fd.close() } catch (_: Exception) {}
            throw t
        }
        val session = PdfSession(renderer, fd)
        pdfRenderer = renderer
        pdfSession = session
        val n = renderer.pageCount
        pdfPageCount = n

        val d = density(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeStage())
        }
        pdfBody = box
        pageViews = arrayOfNulls(n)
        pageRenderPending = BooleanArray(n)

        // 先用稳定纸张比例占位；真实页面比例由 PDF 单线程后台读取后批量更新。
        for (i in 0 until n) {
            val iv = PdfPageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#3B3F43"))
                setOnClickListener { toggleBars() }
            }
            val lp = LayoutParams(-1, -2).apply {
                topMargin = Glass.dp(if (i == 0) 8 else 4, d)
                bottomMargin = Glass.dp(4, d)
            }
            box.addView(iv, lp)
            pageViews[i] = iv
        }
        val tail = View(act)
        box.addView(tail, LayoutParams(-1, Glass.dp(50, d)))
        sv.addView(box, LayoutParams(-1, -2))

        sv.post {
            restoreScrollNow(sv, db.getBook(bookId)?.progress ?: 0f)
            renderPdfWindow(force = true)
            scanPdfPageSizes(session, arrayOfNulls(n), 0)
        }
    }

    /** 渲染当前可见页 ±1，回收远离窗口的位图 */
    private fun renderPdfWindow(force: Boolean = false) {
        val session = pdfSession ?: return
        if (session.isClosed()) return
        val sv = sc ?: return
        pdfBody ?: return
        if (pageViews.isEmpty()) return

        val scrollY = sv.scrollY
        val viewH = sv.height
        if (viewH <= 0) return

        // 页面偏移缓存：宽度变化或页高变化后重建一次，之后滚动只做二分查找
        val tops = if (pdfTopsDirty || pdfTopsWidth != sv.width || pdfTops.size != pageViews.size + 1) {
            if (!buildPdfTops(sv)) return
            pdfTops
        } else {
            pdfTops
        }
        val firstVis = pageIndexAt(tops, scrollY)
        val lastVis = pageIndexAt(tops, scrollY + viewH)

        for (i in max(0, firstVis - 1)..min(pageViews.size - 1, lastVis + 1)) {
            if (pageBitmaps.get(i) == null && !pageRenderPending[i]) {
                renderPage(session, i, sv.width)
            }
        }
        // 回收远离当前窗口的页
        for (i in pageBitmaps.size() - 1 downTo 0) {
            val key = pageBitmaps.keyAt(i)
            if (key < firstVis - 8 || key > lastVis + 8) {
                evictPdfBitmap(key)
            }
        }
        // 再按字节预算回收：靠近窗口但总量超预算时，从最远的页开始淘汰（可见窗口 ±1 永不回收）
        if (pdfBitmapBytes > PDF_BITMAP_CACHE_BYTES) {
            val keepFrom = max(0, firstVis - 1)
            val keepTo = min(pageViews.size - 1, lastVis + 1)
            val candidates = ArrayList<Int>(pageBitmaps.size())
            for (i in 0 until pageBitmaps.size()) {
                val key = pageBitmaps.keyAt(i)
                if (key in keepFrom..keepTo) continue
                candidates.add(key)
            }
            candidates.sortByDescending { key -> max(keepFrom - key, key - keepTo) }
            for (key in candidates) {
                if (pdfBitmapBytes <= PDF_BITMAP_CACHE_BYTES) break
                evictPdfBitmap(key)
            }
        }
    }

    /** 重建 PDF 页面累计偏移表（pdfTops[i] = 第 i 页 top，末尾一项为内容总高） */
    private fun buildPdfTops(sv: ScrollView): Boolean {
        val n = pageViews.size
        if (n == 0) return false
        val out = IntArray(n + 1)
        var acc = 0
        for (i in 0 until n) {
            val v = pageViews[i]
            val lp = v?.layoutParams as? MarginLayoutParams
            acc += lp?.topMargin ?: 0
            out[i] = acc
            acc += if (v != null && v.height > 0) v.height else (v?.estimatedHeight(sv.width) ?: 0)
            acc += lp?.bottomMargin ?: 0
        }
        out[n] = acc
        pdfTops = out
        pdfTopsWidth = sv.width
        pdfTopsDirty = false
        return true
    }

    /** 二分查找：返回最后一个 tops[i] <= y 的页下标（i 最大为页数-1） */
    private fun pageIndexAt(tops: IntArray, y: Int): Int {
        val n = tops.size - 1
        if (n <= 0) return 0
        var lo = 0
        var hi = n - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] <= y) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 回收单页位图并同步字节预算 */
    private fun evictPdfBitmap(key: Int) {
        val bmp = pageBitmaps.get(key) ?: return
        pageViews.getOrNull(key)?.setImageDrawable(null)
        pdfBitmapBytes -= bmp.byteCount.toLong()
        pageBitmaps.remove(key)
        bmp.recycle()
    }

    private fun renderPage(session: PdfSession, index: Int, containerW: Int) {
        // API 35 的 PdfRenderer 仅支持 ARGB_8888；宽度封顶控制内存
        val targetW = min(containerW, 900)
        if (targetW <= 0) return
        val targetView = pageViews.getOrNull(index) ?: return
        pageRenderPending[index] = true
        val accepted = session.execute { renderer ->
            var bitmap: Bitmap? = null
            var pageWidth = 0
            var pageHeight = 0
            try {
                renderer.openPage(index).use { p ->
                    pageWidth = p.width
                    pageHeight = p.height
                    val scale = targetW.toFloat() / p.width
                    val w = targetW
                    val h = max(1, (p.height * scale).toInt())
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap!!.eraseColor(Color.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } catch (_: Exception) {
                bitmap?.recycle()
                bitmap = null
            }
            val result = bitmap
            act.runOnUiThread {
                val stillCurrent =
                    pdfSession === session && !session.isClosed() &&
                        pageViews.getOrNull(index) === targetView && isAttachedToWindow
                if (pdfSession === session && index in pageRenderPending.indices) {
                    pageRenderPending[index] = false
                }
                if (!stillCurrent || result == null) {
                    result?.recycle()
                    return@runOnUiThread
                }
                targetView.setPageSize(pageWidth, pageHeight)
                // 真实页高到位后偏移表失效，需重建
                pdfTopsDirty = true
                val old = pageBitmaps.get(index)
                if (old != null && old !== result) {
                    targetView.setImageDrawable(null)
                    pdfBitmapBytes -= old.byteCount.toLong()
                    old.recycle()
                }
                pageBitmaps.put(index, result)
                pdfBitmapBytes += result.byteCount.toLong()
                targetView.setImageBitmap(result)
            }
        }
        if (!accepted) {
            pageRenderPending[index] = false
        }
    }

    private fun scanPdfPageSizes(
        session: PdfSession,
        sizes: Array<Pair<Int, Int>?>,
        start: Int
    ) {
        if (start >= sizes.size || session.isClosed()) return
        session.execute { renderer ->
            val end = min(sizes.size, start + PDF_SIZE_SCAN_BATCH)
            for (index in start until end) {
                if (session.isClosed()) return@execute
                try {
                    renderer.openPage(index).use { page ->
                        sizes[index] = page.width to page.height
                    }
                } catch (_: Exception) {}
            }
            if (end < sizes.size) {
                scanPdfPageSizes(session, sizes, end)
            } else {
                act.runOnUiThread { applyPdfPageSizes(session, sizes) }
            }
        }
    }

    private fun applyPdfPageSizes(session: PdfSession, sizes: Array<Pair<Int, Int>?>) {
        if (pdfSession !== session || session.isClosed() || !isAttachedToWindow) return
        val sv = sc ?: return
        val body = pdfBody ?: return
        val oldRange = (body.height - sv.height).coerceAtLeast(0)
        val progress = if (oldRange > 0) {
            (sv.scrollY.toFloat() / oldRange.toFloat()).coerceIn(0f, 1f)
        } else {
            db.getBook(bookId)?.progress ?: 0f
        }
        for (index in sizes.indices) {
            val size = sizes[index] ?: continue
            pageViews.getOrNull(index)?.setPageSize(size.first, size.second)
        }
        pdfTopsDirty = true
        body.post {
            if (pdfSession !== session || session.isClosed() || !isAttachedToWindow) return@post
            restoreScrollNow(sv, progress)
            renderPdfWindow(force = true)
        }
    }

    private fun closePdf() {
        val session = pdfSession
        pdfSession = null
        pdfRenderer = null
        pdfPageCount = 0
        for (i in pageBitmaps.size() - 1 downTo 0) {
            val key = pageBitmaps.keyAt(i)
            pageViews.getOrNull(key)?.setImageDrawable(null)
            pageBitmaps.valueAt(i)?.recycle()
            pageBitmaps.removeAt(i)
        }
        pageRenderPending = BooleanArray(0)
        pageViews = emptyArray()
        pdfBody = null
        pdfBitmapBytes = 0L
        pdfTops = IntArray(0)
        pdfTopsDirty = true
        pdfTopsWidth = -1
        session?.close()
    }

    // ---------- 书签与重点（本地，不联网） ----------

    /**
     * 当前位置的锚点。
     * PDF 用页号（PAGE:n，1 基），其余（含图片集）用精确块下标（BLOCK:n，0 基）——
     * 图片集的每个块都是标题，走「非标题块计数」的 PARAGRAPH 锚点会永远算不出编号。
     */
    private fun currentAnchor(): String {
        if (bookFormat == "pdf" || pdfRenderer != null) return "PAGE:${currentPdfPageIndex() + 1}"
        val index = currentBlockIndex()
        if (index < 0) return ""
        return "BLOCK:$index"
    }

    /** 人类可读的位置标签，用于书签列表。 */
    private fun currentPositionLabel(): String {
        if (bookFormat == "pdf" || pdfRenderer != null) {
            val total = pdfRenderer?.pageCount ?: 0
            return "第 ${currentPdfPageIndex() + 1}${if (total > 0) " / $total" else ""} 页"
        }
        val index = currentBlockIndex().coerceAtLeast(0)
        val total = docBlocks?.size ?: 0
        val chapter = tocHeads.lastOrNull { it.first <= index }?.second
        val where = if (chapter.isNullOrBlank()) "第 ${index + 1} 段" else "$chapter · 第 ${index + 1} 段"
        return if (total > 0) "$where（共 $total 段）" else where
    }

    /** 该位置的首行摘录，便于在列表里确认是不是想找的那一处。 */
    private fun currentExcerpt(): String {
        val blocks = docBlocks ?: return ""
        val index = currentBlockIndex()
        return blocks.getOrNull(index)?.text?.trim()?.take(60).orEmpty()
    }

    private fun refreshBookmarkCell() {
        val cell = bookmarkCell ?: return
        val anchor = currentAnchor()
        lastBookmarkAnchor = anchor
        val marked = anchor.isNotBlank() && anchor in bookmarkAnchors
        cell.contentDescription = if (marked) "取消当前位置的书签；长按查看全部书签" else "在当前位置添加书签；长按查看全部书签"
        renderToolCell(cell, icon = "book", label = if (marked) "已加签" else "书签", accent = marked)
    }

    /** 只在锚点真的变了才重绘书签格，避免滚动时反复重建子视图。 */
    private fun syncBookmarkCell() {
        if (currentAnchor() != lastBookmarkAnchor) refreshBookmarkCell()
    }

    /** 启动时把已有书签读进内存（渲染/滚动期间不再访问数据库）。 */
    private fun loadBookmarks() {
        bookmarkAnchors.clear()
        runCatching { db.listBookmarks(bookId).forEach { bookmarkAnchors += it.anchor } }
    }

    /** 点击：在当前位置添加书签；已有则取消。 */
    private fun toggleBookmark() {
        val anchor = currentAnchor()
        if (anchor.isBlank()) {
            toast("当前位置暂时无法记录，请先滚动一下")
            return
        }
        val existing = db.listBookmarks(bookId).firstOrNull { it.anchor == anchor }
        if (existing != null) {
            db.deleteBookmark(existing.id)
            bookmarkAnchors -= anchor
            toast("已取消书签")
        } else {
            val ok = db.addBookmark(bookId, anchor, currentPositionLabel(), currentExcerpt())
            if (ok) bookmarkAnchors += anchor
            toast(if (ok) "已添加书签 · ${currentPositionLabel()}" else "添加书签失败")
        }
        refreshBookmarkCell()
    }

    /** 长按：书签列表，支持跳转与删除。 */
    private fun showBookmarkList() {
        val d = density(act)
        val marks = db.listBookmarks(bookId)
        if (marks.isEmpty()) {
            showResult("书签", "这本书还没有书签。\n点底部「书签」即可把当前位置记下来，长按这里可以回看全部书签。")
            return
        }
        val column = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(6, d), Glass.dp(4, d), Glass.dp(6, d), Glass.dp(6, d))
        }
        val dialog = android.app.AlertDialog.Builder(act)
            .setTitle("书签（${marks.size}）")
            .setView(ScrollView(act).apply { addView(column, LayoutParams(-1, -2)) })
            .setNegativeButton("关闭", null)
            .create()
        // 按位置 / 按时间：长书里按位置排序才能顺着读下去
        var byPosition = false
        val sortRow = TextView(act).apply {
            text = "排序：按时间"
            textSize = 13f
            setTextColor(Accent.chromeAccentText)
            setPadding(Glass.dp(12, d), Glass.dp(8, d), Glass.dp(12, d), Glass.dp(8, d))
            foreground = Glass.pressFx()
            contentDescription = "切换书签排序方式"
        }
        fun rebuild() {
            column.removeAllViews()
            column.addView(sortRow, LinearLayout.LayoutParams(-1, -2))
            val ordered = db.listBookmarks(bookId).let { list ->
                if (byPosition) list.sortedBy { it.anchor.substringAfter(':').toIntOrNull() ?: Int.MAX_VALUE }
                else list
            }
            ordered.forEach { mark ->
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    foreground = Glass.pressFx()
                    contentDescription = "跳到 ${mark.label}"
                    setPadding(Glass.dp(12, d), Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d))
                    setOnClickListener {
                        dialog.dismiss()
                        jumpToCitation(mark.anchor.substringBefore(':').uppercase(), mark.anchor.substringAfter(':').toIntOrNull() ?: 0)
                    }
                }
                row.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(act).apply {
                        text = mark.label.ifBlank { mark.anchor }
                        textSize = 14f
                        setTextColor(pal.textP)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (mark.excerpt.isNotBlank()) {
                        addView(TextView(act).apply {
                            text = mark.excerpt
                            textSize = 11f
                            setTextColor(pal.textT)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(act).apply {
                    text = "删除"
                    textSize = 13f
                    setTextColor(Accent.dangerText)
                    setPadding(Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d), Glass.dp(6, d))
                    setOnClickListener {
                        db.deleteBookmark(mark.id)
                        bookmarkAnchors -= mark.anchor
                        refreshBookmarkCell()
                        rebuild()
                    }
                })
                column.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        sortRow.setOnClickListener {
            byPosition = !byPosition
            sortRow.text = if (byPosition) "排序：按位置" else "排序：按时间"
            rebuild()
        }
        rebuild()
        dialog.show()
        Glass.styleDialog(dialog, d)
    }

    /** 给某一块划重点（颜色 id 存进笔记的 status 列，位置存 anchor 列）。 */
    private fun addHighlight(blockIndex: Int, blockText: String, colorId: String) {
        val anchor = "BLOCK:$blockIndex"
        // 同一段只保留一种颜色：先删掉旧的重点记录，避免叠加出脏色
        Thread {
            db.listNoteDetails(bookId, HIGHLIGHT_KIND)
                .filter { it.anchor == anchor }
                .forEach { db.deleteNote(it.id) }
            db.addNote(bookId, HIGHLIGHT_KIND, blockText.trim().take(2000), anchor = anchor, status = colorId)
            act.runOnUiThread {
                highlightColors[blockIndex] = colorId
                refreshHighlights()
                toast("已划重点")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 取消某一段的重点。 */
    private fun removeHighlight(blockIndex: Int) {
        val anchor = "BLOCK:$blockIndex"
        Thread {
            db.listNoteDetails(bookId, HIGHLIGHT_KIND)
                .filter { it.anchor == anchor }
                .forEach { db.deleteNote(it.id) }
            act.runOnUiThread {
                highlightColors.remove(blockIndex)
                refreshHighlights()
                toast("已清除本段重点")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 启动时把已保存的重点读进内存，渲染块时直接用。 */
    private fun loadHighlights() {
        highlightColors.clear()
        runCatching {
            db.listNoteDetails(bookId, HIGHLIGHT_KIND).forEach { note ->
                val index = note.anchor.removePrefix("BLOCK:").toIntOrNull() ?: return@forEach
                val color = note.status.ifBlank { HIGHLIGHT_AMBER }
                highlightColors[index] = color
            }
        }
    }

    /** 颜色选择：4 色 + 清除。 */
    private fun showHighlightPicker(blockIndex: Int, blockText: String) {
        val d = density(act)
        val labels = listOf(
            HIGHLIGHT_AMBER to "琥珀（重点）",
            HIGHLIGHT_GREEN to "绿色（概念）",
            HIGHLIGHT_BLUE to "蓝色（疑问）",
            HIGHLIGHT_ROSE to "玫红（待办）"
        )
        val options = labels.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(act)
            .setTitle("划重点")
            .setItems(options) { _, which -> addHighlight(blockIndex, blockText, labels[which].first) }
            .setNeutralButton(if (highlightColors.containsKey(blockIndex)) "清除本段重点" else "取消") { _, _ ->
                if (highlightColors.containsKey(blockIndex)) removeHighlight(blockIndex)
            }
            .show().also { Glass.styleDialog(it, d) }
    }

    /** 「我划的重点」列表：跳转与删除。 */
    private fun showHighlightList() {
        val d = density(act)
        val notes = db.listNoteDetails(bookId, HIGHLIGHT_KIND)
        if (notes.isEmpty()) {
            showResult("我划的重点", "还没有划重点。\n长按正文段落 → 「划重点」，选一个颜色即可。")
            return
        }
        val column = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(6, d), Glass.dp(4, d), Glass.dp(6, d), Glass.dp(6, d))
        }
        val dialog = android.app.AlertDialog.Builder(act)
            .setTitle("我划的重点（${notes.size}）")
            .setView(ScrollView(act).apply { addView(column, LayoutParams(-1, -2)) })
            .setNegativeButton("关闭", null)
            .create()
        // 颜色筛选：四种颜色的语义不同（重点/概念/疑问/待办），复习时常常只看其中一种
        var colorFilter: String? = null
        val colorNames = mapOf(
            null to "全部颜色",
            HIGHLIGHT_AMBER to "琥珀（重点）",
            HIGHLIGHT_GREEN to "绿色（概念）",
            HIGHLIGHT_BLUE to "蓝色（疑问）",
            HIGHLIGHT_ROSE to "玫红（待办）"
        )
        val filterRow = TextView(act).apply {
            text = "筛选：全部颜色"
            textSize = 13f
            setTextColor(Accent.chromeAccentText)
            setPadding(Glass.dp(12, d), Glass.dp(8, d), Glass.dp(12, d), Glass.dp(8, d))
            foreground = Glass.pressFx()
            contentDescription = "切换重点颜色筛选"
        }
        fun rebuild() {
            column.removeAllViews()
            column.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
            db.listNoteDetails(bookId, HIGHLIGHT_KIND)
                .filter { colorFilter == null || it.status.ifBlank { HIGHLIGHT_AMBER } == colorFilter }
                .forEach { note ->
                val index = note.anchor.removePrefix("BLOCK:").toIntOrNull()
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    foreground = Glass.pressFx()
                    setPadding(Glass.dp(12, d), Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d))
                    setOnClickListener {
                        if (index == null) return@setOnClickListener
                        dialog.dismiss()
                        jumpToCitation("BLOCK", index)
                    }
                }
                row.addView(View(act).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(highlightColorOf(note.status.ifBlank { HIGHLIGHT_AMBER }) ?: Color.GRAY)
                    }
                }, LinearLayout.LayoutParams(Glass.dp(12, d), Glass.dp(12, d)).also {
                    it.marginEnd = Glass.dp(10, d)
                })
                row.addView(TextView(act).apply {
                    text = note.content
                    textSize = 13f
                    setTextColor(pal.textP)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(act).apply {
                    text = "删除"
                    textSize = 13f
                    setTextColor(Accent.dangerText)
                    setPadding(Glass.dp(10, d), Glass.dp(6, d), Glass.dp(10, d), Glass.dp(6, d))
                    setOnClickListener {
                        db.deleteNote(note.id)
                        if (index != null) highlightColors.remove(index)
                        refreshHighlights()
                        rebuild()
                    }
                })
                column.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        filterRow.setOnClickListener {
            val order = listOf(null, HIGHLIGHT_AMBER, HIGHLIGHT_GREEN, HIGHLIGHT_BLUE, HIGHLIGHT_ROSE)
            colorFilter = order[(order.indexOf(colorFilter) + 1) % order.size]
            filterRow.text = "筛选：" + colorNames[colorFilter]
            rebuild()
        }
        rebuild()
        dialog.show()
        Glass.styleDialog(dialog, d)
    }

    // ---------- AI 功能 ----------

    /** 一个 AI 动作：菜单渲染、「重复上次」与用量说明共用同一份定义。 */
    private class AiAction(
        val key: String,
        val icon: String,
        val label: String,
        val description: String,
        val run: () -> Unit
    )

    /**
     * 本次发送量级的估算说明。
     *
     * 用户在看到菜单时就要知道「会发多少、发去哪」，而不是点进去才从确认框里发现。
     * token 用「字符数 / 2」粗估并明确标注「估算」——本地无法知道服务商真实分词。
     */
    private fun aiSendEstimate(): String {
        val host = providerHost()
        return when {
            bookFormat == "pdf" || pdfRenderer != null -> {
                val total = pdfRenderer?.pageCount ?: 0
                "发往 $host · 最多 ${min(8, total)} 页图像 · 费用由服务商收取"
            }
            isImageFormat(bookFormat) || isImageArchive(bookFormat) ->
                "发往 $host · 发送当前图片 · 费用由服务商收取"
            else -> {
                val chars = min(docFullText.length, DocumentAiService.MAX_CONTEXT_CHARS)
                "发往 $host · 约 $chars 字符 ≈ ${chars / 2} token（估算）· 费用由服务商收取"
            }
        }
    }

    private fun aiActions(): List<AiAction> = listOf(
        AiAction("study_pack", "book", "生成理解包", "摘要 + 大纲 + 概念 + 卡片 + 自测，最全面") {
            studyPackAction()
        },
        AiAction("summary", "note", "快速摘要", "只读开头部分，比理解包快且省；不含大纲与自测") {
            aiSummary()
        },
        AiAction("recap", "chevron", "前情提要", "把当前章之前的内容浓缩成一段回顾，含人物与概念") {
            recapAction()
        },
        AiAction("chat", "search", "和书聊聊", "带着当前章上下文自由提问") { openChat() },
        AiAction("ask", "search", "节选问答", "就一个具体问题在选定范围内找答案") { askAction() },
        AiAction("quiz", "check", "出题自测", "生成 5 道题，作答后由 AI 批改评分") { quizAction() }
    )

    /**
     * AI 助手入口：分组 + 图标 + 一行说明的底部弹层。
     *
     * 原先是一屏 7 行纯文字，既看不出哪些会联网、哪些要花钱，也看不出
     * 「前段摘要」和「节选问答」的区别。现在按「本地 / 理解与梳理 / 互动」分组，
     * 每个联网分组都标明会发往哪个服务商与大致量级。
     * 「人物速查」已合并进「前情提要」的提示词（非虚构文档改列核心概念），不再单独占一行。
     */
    private fun showAiMenu() {
        val sheet = BottomSheet(act, "AI 助手")
        val actions = aiActions()
        // 记住上次用过的动作：重复使用同一个功能不必每次在 6 项里找
        db.getSetting(AI_LAST_ACTION_KEY)?.let { last ->
            actions.firstOrNull { it.key == last }?.let { action ->
                sheet.section("上次使用")
                sheet.item("chevron", "重复上次：${action.label}", action.description) {
                    db.setSetting(AI_LAST_ACTION_KEY, action.key)
                    action.run()
                }
            }
        }
        sheet.section("本地 · 不联网", "这些动作只在本机完成，不发送任何内容")
        sheet.item("search", "书内搜索", "在当前文档里查找词句，支持上一处/下一处") { searchInBook() }
        if (docBlocks?.isNotEmpty() == true) {
            sheet.item("list", "目录", "跳到章节或页码，每章标注全书进度") { listToc() }
        }
        val marked = currentAnchor().let { it.isNotBlank() && it in bookmarkAnchors }
        sheet.item(
            "book",
            if (marked) "取消当前位置书签" else "收藏当前位置",
            if (marked) "移除这一处的书签" else "把当前位置记进书签，长按底栏书签可回看"
        ) { toggleBookmark() }
        sheet.item("note", "我划的重点", "回看并跳转到划过的段落，可按颜色筛选") { showHighlightList() }
        sheet.item("sliders", "阅读设置", "主题 / 行距 / 页边距 / 字号 / 亮度") { showReaderSettings() }
        sheet.item("folder", "文件信息", "格式 / 大小 / 导入时间 / 内容指纹") { showFileInfo() }

        sheet.section("理解与梳理", aiSendEstimate())
        actions.take(3).forEach { action ->
            sheet.item(action.icon, action.label, action.description) {
                db.setSetting(AI_LAST_ACTION_KEY, action.key)
                action.run()
            }
        }

        sheet.section("互动", aiSendEstimate())
        actions.drop(3).forEach { action ->
            sheet.item(action.icon, action.label, action.description) {
                db.setSetting(AI_LAST_ACTION_KEY, action.key)
                action.run()
            }
        }
        sheet.show()
    }

    /** R39：当前文件的客观信息。内容指纹只是去重用的哈希前缀，不是秘密。 */
    private fun showFileInfo() {
        val book = db.getBook(bookId) ?: return
        val file = File(act.filesDir, book.fileName)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        val size = if (file.isFile) file.length() else book.sizeBytes
        showResult(
            "文件信息",
            buildString {
                append("标题：").append(book.title).append('\n')
                append("格式：").append(book.format.uppercase().ifBlank { "未知" }).append('\n')
                append("大小：").append(formatBytes(size)).append('\n')
                append("导入时间：")
                    .append(if (book.addedAt > 0) fmt.format(java.util.Date(book.addedAt)) else "未知")
                    .append('\n')
                append("最近打开：")
                    .append(if (book.lastReadAt > 0) fmt.format(java.util.Date(book.lastReadAt)) else "还没翻开过")
                    .append('\n')
                append("当前位置：").append(currentPositionLabel()).append('\n')
                append("内容指纹：").append(book.contentHash.take(8).ifBlank { "未记录" })
            }
        )
    }

    fun openTableOfContents() = listToc()

    fun openAiWorkbench() = showAiMenu()

    /** A source-addressable, cached package: summary, outline, concepts, findings, flashcards and quiz. */
    private fun studyPackAction(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        val item = db.getBook(bookId) ?: return
        val file = File(act.filesDir, item.fileName)
        val provider = providerHost()
        when {
            bookFormat == "pdf" -> {
                val total = pdfRenderer?.pageCount ?: 0
                if (total <= 0) return showResult("提示", "PDF 没有可分析页面")
                val front = (0 until min(8, total)).toList()
                android.app.AlertDialog.Builder(act)
                    // 标题必须写明会发出哪些页，用户才能判断这次上传的范围
                    .setTitle("生成理解包 · ${visionRangeTitle(front)}")
                    .setMessage("目标服务：$provider\n只会发送你选择的页面图像，文档原文件不会上传。主要结论必须带可验证页码引用。\n确认后不再重复询问。")
                    .setPositiveButton("前 ${min(8, total)} 页") { _, _ ->
                        db.setSetting(SETTING_VISION_SEND_CONFIRMED, "1")
                        generateVisualStudyPack(item, file, cfg, front, false)
                    }
                    .setNeutralButton("当前页") { _, _ ->
                        db.setSetting(SETTING_VISION_SEND_CONFIRMED, "1")
                        generateVisualStudyPack(item, file, cfg, listOf(currentPdfPageIndex()), false)
                    }
                    .setNegativeButton("取消", null)
                    .show().also { Glass.styleDialog(it, density(act)) }
            }
            isImageFormat(bookFormat) -> {
                android.app.AlertDialog.Builder(act)
                    .setTitle("理解这张图片 · 将发送当前图片")
                    .setMessage("目标服务：$provider\n将发送当前图片给已配置的视觉模型；不配置视觉模型时不会尝试伪识别。")
                    .setPositiveButton("继续") { _, _ ->
                        db.setSetting(SETTING_VISION_SEND_CONFIRMED, "1")
                        generateVisualStudyPack(item, file, cfg, listOf(0), true)
                    }
                    .setNegativeButton("取消", null)
                    .show().also { Glass.styleDialog(it, density(act)) }
            }
            else -> {
                if (!requireExtractableText("生成理解包")) return
                android.app.AlertDialog.Builder(act)
                    .setTitle("生成理解包")
                    .setMessage(
                        "目标服务：$provider\n范围：全文结构化抽样，最多 ${DocumentAiService.MAX_CONTEXT_CHARS / 1000}k 字符" +
                            "（本文 ${formatChars(docFullText.length)} 字符）。文档原文件不会上传。"
                    )
                    .setPositiveButton("继续") { _, _ -> generateTextStudyPack(item, file, cfg) }
                    .setNegativeButton("取消", null)
                    .show().also { Glass.styleDialog(it, density(act)) }
            }
        }
    }

    private fun generateTextStudyPack(item: Book, file: File, cfg: AiClient.Config) {
        val service = DocumentAiService(db)
        // 校验结果由 call 内部写入，validate 在 call 返回后读取，用于决定是否保留正文
        var validation: String? = null
        // 命中缓存时不重复落笔记：只有真正生成过才在 onDone 里写
        var generated = false
        val scope = "范围：全文结构化抽样，最多 ${DocumentAiService.MAX_CONTEXT_CHARS / 1000}k 字符" +
            if (docFullText.isNotBlank()) "（本文 ${formatChars(docFullText.length)} 字符）" else ""
        runAiStream(
            kind = null,
            title = "文档理解包",
            cfg = cfg,
            scopeLine = scope,
            validate = { validation },
            onKeep = { text -> persistStudyPack(text, "unvalidated") },
            onDone = { text -> if (generated) persistStudyPack(text, "") },
            onRetry = { alt -> generateTextStudyPack(item, file, alt) }
        ) { onDelta, onReason, onRestart ->
            when (val prepared = service.prepare(item, file, cfg, DocumentAiService.MAX_CONTEXT_CHARS)) {
                is DocumentAiService.Preparation.Cached -> {
                    onDelta(prepared.artifact.content)
                    prepared.artifact.content
                }
                is DocumentAiService.Preparation.Ready -> {
                    generated = true
                    val request = prepared.request
                    val output = AiClient.chat(
                        cfg,
                        request.systemPrompt,
                        request.userPrompt,
                        onDelta,
                        onReason = onReason,
                        onRestart = onRestart,
                        onUsage = ::recordUsage
                    )
                    // 校验失败不再抛到对话框顶部把正文整段冲掉：先记下原因，正文照常上屏
                    validation = try {
                        service.saveCompleted(request, output)
                        null
                    } catch (e: DocumentAiService.InvalidModelOutputException) {
                        e.message ?: "模型输出未通过校验"
                    }
                    output
                }
            }
        }
    }

    private fun generateVisualStudyPack(
        item: Book,
        file: File,
        cfg: AiClient.Config,
        pageIndices: List<Int>,
        singleImage: Boolean
    ) {
        if (cfg.visionModel.isBlank()) {
            showResult("未配置视觉模型", "请在设置中单独填写视觉模型。页枢不会把文本模型伪装成图片识别模型。")
            return
        }
        val label = if (singleImage) "图片理解包" else "PDF 理解包"
        val rangeTitle = if (singleImage) "将发送当前图片" else visionRangeTitle(pageIndices)
        // 与摘要/问答/出题共用同一个确认入口：首次确认后不再重复询问
        confirmVisionSend(
            label,
            rangeTitle,
            "目标服务：${providerHost()}\n只会发送这些页面的图像，文档原文件不会上传。"
        ) {
            val service = DocumentAiService(db)
            var validation: String? = null
            // 命中缓存时不重复落笔记
            var generated = false
            val scope = if (singleImage) "范围：当前图片" else pdfScope(pageIndices)
            runAiStream(
                kind = null,
                title = label,
                cfg = cfg,
                scopeLine = scope,
                validate = { validation },
                onKeep = { text -> persistStudyPack(text, "unvalidated") },
                onDone = { text -> if (generated) persistStudyPack(text, "") },
                onRetry = { alt -> generateVisualStudyPack(item, file, alt, pageIndices, singleImage) }
            ) { onDelta, onReason, onRestart ->
                val pages = if (singleImage) {
                    listOfNotNull(imageBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                } else {
                    collectPdfPages(pageIndices)
                }
                if (pages.isEmpty()) throw IllegalStateException("没有可发送的页面图像")
                try {
                    val anchorType = if (singleImage) AnchorType.IMAGE else AnchorType.PAGE
                    val anchorNumbers = if (singleImage) listOf(1) else pageIndices.map { it + 1 }
                    val anchors = anchorNumbers.map { number ->
                        DocumentAnchor(
                            anchorType,
                            number,
                            if (singleImage) "当前图片" else "第 $number 页",
                            if (singleImage) "用户选择的图片" else "PDF 第 $number 页图像"
                        )
                    }
                    val context = DocumentAiService.AnchoredContext(
                        text = anchors.joinToString("\n") { "[${it.type.name}:${it.index}] ${it.label}" },
                        anchors = anchors,
                        includedSegments = anchors.size,
                        totalSegments = if (singleImage) 1 else (pdfRenderer?.pageCount ?: anchors.size),
                        truncated = !singleImage && anchors.size < (pdfRenderer?.pageCount ?: anchors.size),
                        maxChars = DocumentAiService.MAX_CONTEXT_CHARS
                    )
                    val request = DocumentAiService.PreparedRequest(
                        bookId = item.id,
                        title = item.title,
                        documentHash = DocumentAiService.resolveDocumentHash(item, file),
                        model = cfg.visionModel,
                        promptVersion = DocumentAiService.PROMPT_VERSION,
                        kind = DocumentAiService.KIND_STUDY_PACK,
                        context = context,
                        systemPrompt = DocumentAiService.buildSystemPrompt(),
                        userPrompt = DocumentAiService.buildStudyPackPrompt(
                            item.title,
                            if (singleImage) "image" else "pdf",
                            context
                        ) + "\n图像按以下顺序提供：" + anchors.joinToString { "[${it.type.name}:${it.index}]" }
                    )
                    service.findCached(request)?.let { cached ->
                        onDelta(cached.content)
                        return@runAiStream cached.content
                    }
                    generated = true
                    val output = AiClient.chatVision(
                        cfg,
                        request.systemPrompt,
                        request.userPrompt,
                        pages,
                        onDelta,
                        onReason = onReason,
                        onRestart = onRestart,
                        onUsage = ::recordUsage
                    )
                    validation = try {
                        service.saveCompleted(request, output)
                        null
                    } catch (e: DocumentAiService.InvalidModelOutputException) {
                        e.message ?: "模型输出未通过校验"
                    }
                    output
                } finally {
                    pages.forEach { it.recycle() }
                }
            }
        }
    }

    /**
     * 理解包同时落一条笔记：ai_artifacts 只有 AI 内部缓存会读，对话框关闭后用户再无入口查看。
     * 只在真正生成（saveCompleted）或用户点「仍要保存」时写，命中缓存时不重复落库。
     * [status] 为 "unvalidated" 表示校验未通过但用户选择保留。
     */
    private fun persistStudyPack(content: String, status: String = "") {
        // kind 与 ai_artifacts 的 kind 保持一致，笔记列表按 kind 过滤时也认得出
        saveAiNote(DocumentAiService.KIND_STUDY_PACK, content, "", status)
    }

    /** 前情提要：当前章之前的内容浓缩，追长篇防忘剧情 */
    private fun recapAction(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        if (!requireExtractableText("前情提要")) return
        val blocks = docBlocks
        if (blocks == null || tocHeads.isEmpty()) {
            showResult("提示", "本书没有章节结构，无法定位「当前章之前」的内容。\n可以改用「节选问答」。")
            return
        }
        // 块高差异大：当前块按真实可见位置取，不能用比例反推
        val curIdx = currentBlockIndex().coerceAtLeast(0)
        var start = 0
        for (h in tocHeads) { if (h.first <= curIdx) start = h.first else break }
        if (start == 0) {
            showResult("提示", "你还在第一章开头，没有「前情」可讲～")
            return
        }
        // 只取当前章前 60 块根本讲不清「到目前为止」：改为对整段前文做带锚点的结构化抽样
        val before = blocks.subList(0, start)
        val context = runCatching {
            DocumentAiService.buildAnchoredContext(
                ParsedDoc(bookFormat.ifBlank { "txt" }, before, before.joinToString("\n") { it.text }),
                bookFormat,
                DocumentAiService.MAX_CONTEXT_CHARS
            )
        }.getOrNull()
        val material = context?.text?.takeIf { it.isNotBlank() } ?: fallbackRecapExcerpt(blocks, start)
        val scope = if (context != null) {
            "范围：前文节选（已发送 ${context.includedSegments}/${context.totalSegments} 段，约 ${formatChars(material.length)} 字符）"
        } else {
            "范围：前文节选（章节标题 + 各章末段，约 ${formatChars(material.length)} 字符）"
        }
        runAiStream(
            kind = "recap",
            title = "前情提要",
            cfg = cfg,
            scopeLine = scope,
            onRetry = { alt -> recapAction(alt) }
        ) { onDelta, onReason, onRestart ->
            AiClient.chat(cfg, SYS_PROMPT,
                "读者正在读长篇/资料，下面是当前章节之前的带锚点节选（[CHAPTER:n] 是章节，[PARAGRAPH:n] 是段落）。" +
                    "请用约 250 字梳理「到目前为止发生了什么」：关键事件、出场人物及其动机、留下的悬念；" +
                    "若为非虚构文档，则改为列出已出现的关键概念/术语及其含义。只输出提要正文。" +
                    "\n\n【前文开始】\n$material\n【前文结束】",
                onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
        }
    }

    /**
     * 前情提要兜底：锚点上下文不可用时，用「每章标题 + 该章末 6 块」拼出「到目前为止」的骨架。
     * 只保留末段是为了在预算内覆盖尽可能多的章节，而不是把开头几十块铺满。
     */
    private fun fallbackRecapExcerpt(blocks: List<Block>, start: Int): String {
        val heads = tocHeads.filter { it.first < start }
        if (heads.isEmpty()) {
            return blocks.subList(0, start).takeLast(60).joinToString("\n") { it.text }.take(24000)
        }
        val out = StringBuilder()
        heads.forEachIndexed { i, head ->
            val headIndex = head.first
            val end = if (i + 1 < heads.size) heads[i + 1].first else start
            out.append("【").append(head.second).append("】\n")
            out.append(
                blocks.subList((headIndex + 1).coerceAtMost(end), end).takeLast(6).joinToString("\n") { it.text }
            ).append("\n")
        }
        return out.toString().take(24000)
    }

    /** 人物速查：从当前章提取出场人物与身份 */
    private fun castAction(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        if (!requireExtractableText("人物速查")) return
        val chapter = currentChapterText()
        if (chapter.isBlank()) { showResult("提示", "当前章节没有可分析文本"); return }
        val sent = chapter.take(12000)
        runAiStream(
            kind = "cast",
            title = "人物速查",
            cfg = cfg,
            scopeLine = "范围：当前章节前 ${formatChars(sent.length)} 字符（共 ${formatChars(chapter.length)}）",
            onRetry = { alt -> castAction(alt) }
        ) { onDelta, onReason, onRestart ->
            AiClient.chat(cfg, SYS_PROMPT,
                "从下面的章节内容中提取出场人物（最多 6 个）。每个人物一行：「名字 —— 身份/角色 + 当前状态或动机」，" +
                    "按重要性排序。若为非小说类文档，则提取核心概念/术语代替人物。\n\n$sent",
                onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
        }
    }

    /** 打开与书聊天页（携带当前章上下文） */
    private fun openChat() {
        (act as MainActivity).showChat(bookId, currentChapterText())
    }

    /** 当前可见章节的纯文本（供聊天 system 上下文） */
    private fun currentChapterText(): String {
        val blocks = docBlocks ?: return ""
        // 找当前章起点：最后一个 tocHead <= 当前块（当前块按真实可见位置取）
        val curIdx = currentBlockIndex().coerceAtLeast(0)
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
                .setMessage("请先在书架「更多」→「AI 设置」填写接口地址、Key 和模型名。\nPDF 功能需要支持图片输入的视觉模型。")
                .setPositiveButton("知道了", null)
                .show().also { Glass.styleDialog(it, density(act)) }
            return null
        }
        return cfg
    }

    /** PDF：渲染前几页给视觉模型 */
    private fun collectPdfPages(maxPages: Int): List<Bitmap> {
        if (pdfSession == null) return emptyList()
        return collectPdfPages((0 until min(pdfPageCount, maxPages)).toList())
    }

    /** PDF：只渲染用户明确选择的页面，索引为 0-based。 */
    private fun collectPdfPages(indices: List<Int>): List<Bitmap> {
        val session = pdfSession ?: return emptyList()
        return session.renderPages(indices, pdfPageCount, 720)
    }

    private fun currentPdfPageIndex(): Int {
        val sv = sc ?: return 0
        val center = sv.scrollY + sv.height / 2
        for (i in pageViews.indices) {
            val view = pageViews[i] ?: continue
            if (center <= view.bottom) return i
        }
        return (pageViews.size - 1).coerceAtLeast(0)
    }

    /**
     * 流式 AI 任务：对话框内打字机输出 + 推理模型思考区。
     * [kind] 非空时成功结果自动落笔记；[validate] 返回非空表示校验未通过——
     * 此时正文保留上屏，只在上方压一条横幅，并提供「重试 / 仍要保存」。
     * [scopeLine] 是这次实际读取的范围说明；[onRetry] 用于「重新生成 / 换模型重试」。
     */
    private fun runAiStream(
        kind: String?,
        title: String,
        cfg: AiClient.Config? = null,
        scopeLine: String? = null,
        anchor: String = "",
        validate: ((String) -> String?)? = null,
        onKeep: ((String) -> Unit)? = null,
        onRetry: ((AiClient.Config) -> Unit)? = null,
        onDone: ((String) -> Unit)? = null,
        call: (onDelta: (String) -> Unit, onReason: (String) -> Unit, onRestart: () -> Unit) -> String
    ) {
        if (activeAiTask != null) {
            // 并发任务会留下两个对话框，且「停止」只作用于其中一个，直接拒绝比假装成功更诚实
            showResult("已有任务进行中", "请先关闭或停止当前的 AI 任务，再发起新的请求。")
            return
        }
        val d = density(act)
        val task = AiTask()
        activeAiTask = task
        val modelName = cfg?.model.orEmpty()
        val titleWithModel = if (modelName.isBlank()) title else "$title · $modelName"

        // 范围说明常驻标题下方：用户必须知道这次到底读了哪一段
        val scopeTv = TextView(act).apply {
            textSize = 11.5f
            setTextColor(pal.textS)
            setPadding(Glass.dp(22, d), Glass.dp(10, d), Glass.dp(22, d), 0)
            visibility = if (scopeLine.isNullOrBlank()) View.GONE else View.VISIBLE
            text = scopeLine.orEmpty()
        }
        // 校验/错误横幅：压在正文上方，正文本身不再被整段替换
        val bannerTv = TextView(act).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#B3261E"))
            setLineSpacing(Glass.dp(2, d).toFloat(), 1.1f)
            setPadding(Glass.dp(22, d), Glass.dp(10, d), Glass.dp(22, d), 0)
            visibility = View.GONE
        }
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
            // 流式结果以前只能干看着，连复制都做不到
            setTextIsSelectable(true)
        }
        val sectionRow = horizontalChipRow().apply { visibility = View.GONE }
        val actionRow = horizontalChipRow()
        // 次要动作单独一行：原先 6 个 chip 挤一行，窄屏上「停止」被挤出屏幕
        val actionRow2 = horizontalChipRow().apply { visibility = View.GONE }
        val footerTv = TextView(act).apply {
            textSize = 11f
            setTextColor(pal.textT)
            setPadding(Glass.dp(22, d), 0, Glass.dp(22, d), Glass.dp(8, d))
        }
        // 编辑态：结果以前只能看/复制，想删掉一段废话只能整段重生成
        val editor = EditText(act).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(Glass.dp(4, d).toFloat(), 1f)
            setPadding(Glass.dp(22, d), Glass.dp(12, d), Glass.dp(22, d), Glass.dp(20, d))
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "直接修改这段结果，保存后会同步更新笔记与 AI 成果"
            visibility = View.GONE
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(thinkTv)
            addView(tv)
            addView(editor)
        }
        val scroll = ScrollView(act)
        scroll.addView(box)
        val outer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(scopeTv)
            addView(bannerTv)
            addView(sectionRow)
            addView(scroll, LinearLayout.LayoutParams(-1, Glass.dp(320, d)))
            addView(actionRow)
            addView(actionRow2)
            addView(footerTv)
        }
        val dlgBuilder = android.app.AlertDialog.Builder(act)
        dlgBuilder.setTitle("$titleWithModel · 生成中")
        dlgBuilder.setView(outer)
        // 停止/重试等动作都放在正文下方的 chip 行里，避免按钮文字与状态不一致
        dlgBuilder.setPositiveButton("关闭", null)
        val dlg = dlgBuilder.create()
        task.dialog = dlg
        // 任何关闭方式（返回键、点外部、代码 dismiss）都要取消请求，否则「已取消」的生成仍会写进笔记
        dlg.setOnDismissListener {
            task.cancelled.set(true)
            task.token.cancel()
            if (activeAiTask === task) activeAiTask = null
        }
        dlg.show()
        Glass.styleDialog(dlg, density(act))

        val lastUi = longArrayOf(0L)
        val replyRef = arrayOf("")
        val sectionsRef = arrayOf<List<Pair<String, Int>>>(emptyList())
        val editingRef = booleanArrayOf(false)
        // Kotlin 局部函数必须先声明后使用：编辑动作在 showActions 之前定义，用这个槽位回调
        val showActionsSlot = arrayOfNulls<(Boolean, Boolean) -> Unit>(1)

        fun renderBody(text: String) {
            // 编辑中不要被迟到的流式回调覆盖，否则用户改到一半的文字会被冲掉
            if (editingRef[0]) return
            val md = renderAiMarkdown(text)
            applyCitationSpans(md.text)
            sectionsRef[0] = md.sections
            tv.text = md.text
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.highlightColor = Color.TRANSPARENT
        }

        fun saveEdit() {
            val edited = editor.text.toString().trim()
            if (edited.isBlank()) {
                toast("内容不能为空")
                return
            }
            val original = replyRef[0]
            val noteKind = kind ?: if (onKeep != null) DocumentAiService.KIND_STUDY_PACK else "note"
            replyRef[0] = edited
            editingRef[0] = false
            renderBody(edited)
            editor.visibility = View.GONE
            tv.visibility = View.VISIBLE
            // 写库放到后台线程：主线程 Room 写会卡住同一帧
            Thread {
                runCatching { db.applyEditedContent(bookId, noteKind, original, edited) }
                act.runOnUiThread {
                    toast("已保存修改")
                    showActionsSlot[0]?.invoke(false, false)
                    footerTv.text = "模型：${modelName.ifBlank { "未配置" }} · 已手动编辑"
                }
            }.apply { isDaemon = true }.start()
        }

        /** 进入编辑态：正文换成可编辑框，动作行换成保存/取消。 */
        fun startEditing() {
            editingRef[0] = true
            editor.setText(replyRef[0])
            editor.visibility = View.VISIBLE
            tv.visibility = View.GONE
            sectionRow.visibility = View.GONE
            footerTv.text = "手动编辑中 · 保存后会标记为「已手动编辑」"
            actionRow2.visibility = View.GONE
            fillChipRow(actionRow, listOf(
                aiChip("保存修改") { saveEdit() },
                aiChip("取消编辑") {
                    editingRef[0] = false
                    editor.visibility = View.GONE
                    tv.visibility = View.VISIBLE
                    showActionsSlot[0]?.invoke(false, false)
                    footerTv.text = "模型：${modelName.ifBlank { "未配置" }} · 完成"
                }
            ))
        }

        fun refreshSectionChips() {
            val sections = sectionsRef[0]
            if (sections.isEmpty()) {
                sectionRow.visibility = View.GONE
                return
            }
            fillChipRow(sectionRow, sections.map { (name, offset) ->
                aiChip(name) { scrollToSection(scroll, tv, offset) }
            })
        }

        fun retry(alt: AiClient.Config?) {
            val target = alt ?: cfg
            val action = onRetry
            if (target == null || action == null) {
                toast("这次结果不支持重试")
                return
            }
            // 先关旧对话框（会清空 activeAiTask），否则新任务会被「已有任务进行中」挡住
            dlg.dismiss()
            action(target)
        }

        fun pickProfileForRetry() {
            val current = cfg?.model.orEmpty()
            val others = AiProfileStore.list(db)
                .filter { it.textModel.trim().isNotBlank() && it.textModel.trim() != current }
            if (others.isEmpty()) {
                toast("没有其他可切换的模型配置")
                return
            }
            android.app.AlertDialog.Builder(act)
                .setTitle("换模型重试")
                .setItems(others.map { "${it.name} · ${it.textModel}" }.toTypedArray()) { _, which ->
                    retry(configForProfile(others[which]))
                }
                .setNegativeButton("取消", null)
                .show().also { Glass.styleDialog(it, density(act)) }
        }

        /**
         * 动作分两行：第一行是「改变本次生成」的主操作（停止/重新生成/重试），
         * 第二行是对已有正文的处理（复制/分享/编辑/存为笔记/仍要保存/关闭）。
         * 以前 6 个 chip 挤在一行，窄屏上「停止」会被挤出屏幕且无法察觉。
         */
        fun showActions(streaming: Boolean, keepable: Boolean) {
            val primary = mutableListOf<TextView>()
            val secondary = mutableListOf<TextView>()
            if (streaming) {
                primary += aiChip("停止") {
                    task.cancelled.set(true)
                    task.token.cancel()
                }
            } else {
                if (keepable) {
                    primary += aiChip("重试") { retry(null) }
                } else {
                    primary += aiChip("重新生成") { retry(null) }
                }
                if (onRetry != null && cfg != null) primary += aiChip("换模型重试") { pickProfileForRetry() }

                if (replyRef[0].isNotBlank()) secondary += aiChip("复制") { copyAiText(title, replyRef[0]) }
                if (replyRef[0].isNotBlank()) secondary += aiChip("分享") { shareAiText(title, replyRef[0]) }
                if (replyRef[0].isNotBlank()) secondary += aiChip("编辑") { startEditing() }
                if (kind == null && replyRef[0].isNotBlank()) {
                    secondary += aiChip("存为笔记") {
                        saveAiNote("note", replyRef[0], anchor, "")
                        toast("已存为笔记")
                    }
                }
                if (keepable) {
                    secondary += aiChip("仍要保存") {
                        val content = replyRef[0]
                        // onKeep 存在时由调用方决定 kind（理解包要写 study_pack），避免同一内容落两条笔记
                        if (onKeep != null) {
                            onKeep.invoke(content)
                        } else {
                            saveAiNote(kind ?: "note", content, anchor, "unvalidated")
                        }
                        toast("已按「未校验」保存")
                        dlg.dismiss()
                    }
                }
                secondary += aiChip("关闭") { dlg.dismiss() }
            }
            fillChipRow(actionRow, primary)
            actionRow2.visibility = if (secondary.isEmpty()) View.GONE else View.VISIBLE
            if (secondary.isNotEmpty()) fillChipRow(actionRow2, secondary)
        }

        showActionsSlot[0] = ::showActions
        showActions(streaming = true, keepable = false)
        lastUsage = null
        val startedAtMs = System.currentTimeMillis()
        footerTv.text = "模型：${modelName.ifBlank { "未配置" }} · 生成中"

        // 工作线程必须是 daemon：否则对话框已关、界面已 detach，进程仍被这条线程吊住
        val worker = Thread {
            var err: String? = null
            var problem: String? = null
            var reply = ""
            val acc = StringBuilder()
            val rAcc = StringBuilder()
            try {
                reply = AiClient.withCancellation(task.token) {
                    call({ delta ->
                        acc.append(delta)
                        if (rAcc.isNotEmpty()) {
                            act.runOnUiThread { thinkTv.text = "💭 已深度思考 ${rAcc.length} 字" }
                        }
                        val now = System.currentTimeMillis()
                        if (!task.cancelled.get() && now - lastUi[0] > 150) {
                            lastUi[0] = now
                            act.runOnUiThread {
                                renderBody(acc.toString())
                                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                            }
                        }
                    }, { reason ->
                        if (task.cancelled.get()) return@call
                        rAcc.append(reason)
                        act.runOnUiThread {
                            thinkTv.visibility = View.VISIBLE
                            thinkTv.text = "💭 思考中… ${rAcc.takeLast(80)}"
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }, {
                        // 服务端断流后重发：丢弃已上屏的半截内容，避免「半截 + 全文」重复
                        acc.setLength(0)
                        rAcc.setLength(0)
                        lastUi[0] = 0L
                        act.runOnUiThread { thinkTv.visibility = View.GONE }
                    })
                }
                if (reply.isBlank()) throw RuntimeException("模型返回为空")
                problem = validate?.invoke(reply)
            } catch (t: Throwable) {
                // 统一走 AiClient 的中文错误文案，不再把英文异常原文糊到用户脸上
                if (!task.cancelled.get()) err = AiClient.userFacingError(t)
            }
            val e = err
            val issue = problem
            // 取消状态在这里取一次快照：笔记落库在工作线程，UI 分支必须与它判断一致
            val cancelled = task.cancelled.get()
            // 笔记写库放到工作线程（Room 允许主线程查询，但写库会卡住同一帧的界面刷新）；
            // 先落库、再上屏，保证用户看到 ✓ 时笔记一定已经存好。
            // 校验未通过时不自动落库，改由用户点「仍要保存」再写（status=unvalidated）。
            if (e == null && issue == null && !cancelled && kind != null) {
                saveAiNote(kind, reply, anchor, "")
            }
            act.runOnUiThread {
                // 已被新任务接管时不能再改界面，否则会清掉新任务的状态
                if (activeAiTask !== task) return@runOnUiThread
                replyRef[0] = reply.ifBlank { acc.toString() }
                when {
                    cancelled -> {
                        dlg.setTitle("$titleWithModel · 已停止")
                        renderBody(acc.toString().ifBlank { "（已停止，没有收到内容）" })
                        bannerTv.visibility = View.VISIBLE
                        bannerTv.text = "已停止，未保存到笔记"
                        showActions(streaming = false, keepable = true)
                    }
                    e != null -> {
                        dlg.setTitle("$titleWithModel · 失败")
                        renderBody(acc.toString().ifBlank { "调用失败：$e" })
                        bannerTv.visibility = View.VISIBLE
                        bannerTv.text = "调用失败：$e"
                        showActions(streaming = false, keepable = true)
                    }
                    issue != null -> {
                        // 校验失败不再整段丢弃：正文照常显示，只在上方给出原因与补救动作
                        dlg.setTitle("$titleWithModel · 校验未通过")
                        renderBody(reply)
                        bannerTv.visibility = View.VISIBLE
                        bannerTv.text = "校验未通过：$issue"
                        showActions(streaming = false, keepable = true)
                    }
                    else -> {
                        dlg.setTitle("$titleWithModel ✓")
                        renderBody(reply)
                        showActions(streaming = false, keepable = false)
                        onDone?.invoke(reply)
                    }
                }
                refreshSectionChips()
                // 回执：模型 · 状态 · 耗时 · 本次 token。费用金额不做本地臆测，只报用量。
                val elapsed = ((System.currentTimeMillis() - startedAtMs) / 1000.0)
                val usage = lastUsage?.takeIf { !it.isEmpty }
                footerTv.text = buildString {
                    append("模型：").append(modelName.ifBlank { "未配置" })
                    append(
                        when {
                            cancelled -> " · 已停止"
                            e != null -> " · 调用失败"
                            issue != null -> " · 未通过校验"
                            validate != null -> " · 已通过校验"
                            else -> ""
                        }
                    )
                    append(" · 用时 ").append("%.1f".format(elapsed)).append("s")
                    if (usage != null) {
                        append(" · 输入 ").append(usage.promptTokens)
                            .append(" / 输出 ").append(usage.completionTokens)
                            .append(" tokens")
                    }
                }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }.apply { isDaemon = true }
        worker.start()
    }

    /** Make validated AI source tokens actionable so conclusions can jump back to their source. */
    private fun applyCitationLinks(target: TextView, content: String) {
        val linked = SpannableString(content)
        applyCitationSpans(linked)
        target.text = linked
        target.movementMethod = LinkMovementMethod.getInstance()
        target.highlightColor = Color.TRANSPARENT
    }

    /** 把 [PAGE:n] / [PARAGRAPH:n] 之类引用变成可点击跳转的 span（不动 movementMethod） */
    private fun applyCitationSpans(linked: android.text.Spannable) {
        // 先在纯文本上定位，再改 span，避免边遍历边改同一个 CharSequence
        CITATION_RE.findAll(linked.toString()).toList().forEach { match ->
            val type = match.groupValues[1]
            val index = match.groupValues[2].toIntOrNull() ?: return@forEach
            linked.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = jumpToCitation(type, index)
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Color.parseColor("#5B5FF5")
                    ds.isUnderlineText = true
                    ds.isFakeBoldText = true
                }
            }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun jumpToCitation(type: String, oneBasedIndex: Int) {
        when (type) {
            "PAGE" -> {
                val target = pageViews.getOrNull(oneBasedIndex - 1)
                val scroll = sc
                if (target != null && scroll != null) {
                    scroll.post { scroll.smoothScrollTo(0, max(0, target.top - Glass.dp(8, density(act)))) }
                } else showResult("无法定位", "当前结果引用的 PDF 页不在可用范围内。")
            }
            // 章节/幻灯片/段落都按「非标题块」重新计数：编号不等于块下标，直接相减会被标题顶偏
            "CHAPTER", "SLIDE", "PARAGRAPH" -> {
                val block = blockIndexForAnchor(type, oneBasedIndex)
                if (block != null) jumpToBlock(block, flash = true)
                else showResult("无法定位", "当前结果引用的${anchorTypeLabel(type)}超出文档范围，文档可能已更新。")
            }
            // 书签/重点用的是精确块下标（0 基），不受标题计数影响，图片集也能定位
            "BLOCK" -> jumpToBlock(oneBasedIndex, flash = true)
            else -> showResult("无法定位", "无法识别引用类型「$type」，请手动定位。")
        }
    }

    // ---------- 引用锚点 ↔ 块下标 ----------

    private fun isHeadingBlock(b: Block, markdown: Boolean): Boolean =
        b.type == Block.HEADING || (markdown && MD_HEADING_RE.matches(b.text.trim()))

    private fun slideNumberOf(text: String): Int? =
        PPT_SLIDE_RE.matchEntire(text.trim())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 锚点 → 块下标。
     * DocumentAiService 的 PARAGRAPH/SLIDE/CHAPTER 编号只统计「非标题块」
     * （见 proseSegments / slideSegments 里的 paragraph++ / chapter++），
     * 所以这里必须按同一规则重新走一遍 docBlocks，不能把编号当块下标用。
     */
    private fun blockIndexForAnchor(type: String, index: Int): Int? {
        val blocks = docBlocks ?: return null
        if (index <= 0 || blocks.isEmpty()) return null
        val markdown = bookFormat == "md"
        when (type) {
            "PARAGRAPH" -> {
                var paragraph = 0
                blocks.forEachIndexed { i, b ->
                    if (b.text.isBlank() || isHeadingBlock(b, markdown)) return@forEachIndexed
                    paragraph++
                    if (paragraph == index) return i
                }
            }
            "SLIDE" -> {
                var slide = 0
                blocks.forEachIndexed { i, b ->
                    if (b.text.isBlank()) return@forEachIndexed
                    if (b.type == Block.HEADING) {
                        val n = slideNumberOf(b.text)
                        if (n != null) {
                            slide = n.coerceAtLeast(1)
                            if (slide == index) return i
                            return@forEachIndexed
                        }
                    }
                    if (slide == 0) {
                        slide = 1
                        if (slide == index) return i
                    }
                }
            }
            "CHAPTER" -> {
                var chapter = 0
                blocks.forEachIndexed { i, b ->
                    if (b.text.isBlank() || !isHeadingBlock(b, markdown)) return@forEachIndexed
                    chapter++
                    if (chapter == index) return i
                }
            }
        }
        return null
    }

    /** 块下标 → 锚点字符串：与 [blockIndexForAnchor] 用同一套「非标题块」计数 */
    private fun anchorForBlockIndex(index: Int): String {
        val blocks = docBlocks ?: return ""
        if (index !in blocks.indices) return ""
        val markdown = bookFormat == "md"
        var paragraph = 0
        for (i in 0..index) {
            val b = blocks[i]
            if (b.text.isBlank() || isHeadingBlock(b, markdown)) continue
            paragraph++
        }
        return if (paragraph > 0) "PARAGRAPH:$paragraph" else ""
    }

    private fun anchorTypeLabel(type: String): String = when (type) {
        "CHAPTER" -> "章节"
        "SLIDE" -> "幻灯片"
        "PARAGRAPH" -> "段落"
        "PAGE" -> "页面"
        "BLOCK" -> "段落"
        else -> "位置"
    }

    // ---------- AI 结果 UI 工具 ----------

    /**
     * 轻量 Markdown → Spannable：标题加粗放大、列表项缩进、**加粗** 去掉星号。
     * 同时返回各标题在结果中的字符偏移，供章节 chip 跳转；不处理表格/代码块等重语法。
     */
    private fun renderAiMarkdown(raw: String): AiMarkdown {
        val d = density(act)
        val out = SpannableStringBuilder()
        val sections = mutableListOf<Pair<String, Int>>()
        val heading = Regex("^(#{1,6})\\s+(.*)$")
        val bullet = Regex("^([-*+])\\s+(.*)$")
        val numbered = Regex("^(\\d{1,2}[.、)])\\s+(.*)$")
        raw.trimEnd().split("\n").forEach { line ->
            val trimmed = line.trim()
            val head = heading.matchEntire(trimmed)
            val item = if (head == null) bullet.matchEntire(trimmed) else null
            val ordered = if (head == null && item == null) numbered.matchEntire(trimmed) else null
            when {
                head != null -> {
                    if (out.isNotEmpty()) out.append("\n")
                    val title = head.groupValues[2].trim()
                    sections += title to out.length
                    val start = out.length
                    appendInlineBold(out, title)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(
                        RelativeSizeSpan(if (head.groupValues[1].length <= 2) 1.22f else 1.1f),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.setSpan(
                        ForegroundColorSpan(AI_HEAD_ACCENT),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.append("\n")
                }
                item != null -> {
                    val start = out.length
                    out.append("• ")
                    appendInlineBold(out, item.groupValues[2])
                    out.setSpan(
                        LeadingMarginSpan.Standard(Glass.dp(18, d)),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.append("\n")
                }
                ordered != null -> {
                    // 编号必须保留：作答和批改都要靠题号对位
                    val start = out.length
                    out.append(ordered.groupValues[1]).append(" ")
                    appendInlineBold(out, ordered.groupValues[2])
                    out.setSpan(
                        LeadingMarginSpan.Standard(Glass.dp(18, d)),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.append("\n")
                }
                else -> {
                    appendInlineBold(out, line.trimEnd())
                    out.append("\n")
                }
            }
        }
        return AiMarkdown(out, sections)
    }

    /** 追加一段文本，并把 **加粗** 语法转成真正的粗体 span */
    private fun appendInlineBold(out: SpannableStringBuilder, text: String) {
        var last = 0
        BOLD_RE.findAll(text).forEach { match ->
            out.append(text, last, match.range.first)
            val start = out.length
            out.append(match.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
    }

    /** 章节 chip：把结果框滚到对应标题（字符偏移 → 行号 → 像素） */
    private fun scrollToSection(scroll: ScrollView, tv: TextView, offset: Int) {
        val layout = tv.layout ?: return
        val length = tv.text?.length ?: return
        if (offset < 0 || offset > length) return
        val line = layout.getLineForOffset(offset)
        scroll.smoothScrollTo(
            0,
            (tv.top + layout.getLineTop(line) - Glass.dp(8, density(act))).coerceAtLeast(0)
        )
    }

    /** 横向可滚动的 chip 行：章节导航与结果操作共用 */
    private fun horizontalChipRow(): android.widget.HorizontalScrollView {
        val d = density(act)
        return android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Glass.dp(18, d), Glass.dp(6, d), Glass.dp(18, d), Glass.dp(6, d))
            })
        }
    }

    private fun aiChip(label: String, onClick: () -> Unit): TextView {
        val d = density(act)
        return TextView(act).apply {
            text = label
            textSize = 12.5f
            setTextColor(pal.textP)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(pal.surface2)
                cornerRadius = Glass.dp(13, d).toFloat()
                setStroke(Glass.dp(1, d), pal.surface3)
            }
            setPadding(Glass.dp(11, d), Glass.dp(6, d), Glass.dp(11, d), Glass.dp(6, d))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun fillChipRow(row: android.widget.HorizontalScrollView, chips: List<TextView>) {
        val container = row.getChildAt(0) as? LinearLayout ?: return
        container.removeAllViews()
        val d = density(act)
        chips.forEachIndexed { index, chip ->
            container.addView(chip, LinearLayout.LayoutParams(-2, -2).also { lp ->
                if (index > 0) lp.setMargins(Glass.dp(8, d), 0, 0, 0)
            })
        }
        row.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(act, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 复制结果到剪贴板：流式结果以前只能干看着，连复制都做不到 */
    private fun copyAiText(label: String, body: String) {
        if (body.isBlank()) return toast("没有可复制的内容")
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (cm == null) return toast("当前设备不支持剪贴板")
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, body))
        toast("已复制")
    }

    private fun shareAiText(label: String, body: String) {
        if (body.isBlank()) return toast("没有可分享的内容")
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, label)
            putExtra(android.content.Intent.EXTRA_TEXT, body)
        }
        val ok = runCatching {
            act.startActivity(android.content.Intent.createChooser(send, "分享 AI 结果"))
        }.isSuccess
        if (!ok) showResult("无法分享", "当前设备没有可用的分享目标。")
    }

    /**
     * AI 结果落笔记的唯一入口。
     * [anchor]（块锚点）与 [status]（"unvalidated" 等）需要 NoteEntity 的新列；
     * Db.addNote 目前还没开放这两个形参，所以这里暂时只写 kind/content，
     * 等 Db.addNote 加上 anchor/status 后在本方法内一次补齐（见交付报告的跨文件请求）。
     */
    private fun saveAiNote(kind: String, content: String, anchor: String = "", status: String = "") {
        if (content.isBlank()) return
        try {
            db.addNote(bookId, kind, content)
        } catch (_: Exception) {}
    }

    /** 所有 chatVision 调用的统一确认入口：标题必须写明发送范围，首次确认后不再重复询问 */
    private fun confirmVisionSend(label: String, rangeTitle: String, message: String, onConfirm: () -> Unit) {
        if (db.getSetting(SETTING_VISION_SEND_CONFIRMED) == "1") {
            onConfirm()
            return
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("$label · $rangeTitle")
            .setMessage(message)
            .setPositiveButton("确认发送") { _, _ ->
                db.setSetting(SETTING_VISION_SEND_CONFIRMED, "1")
                onConfirm()
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun providerHost(): String {
        val base = runCatching { AiClient.config(db).baseUrl }.getOrDefault("")
        return runCatching { URI(AiClient.normalizeBase(base)).host }.getOrNull() ?: base.take(48)
    }

    /** 结果头部的范围说明：PDF 说页码，文本说抽样字符数 */
    private fun pdfScope(indices: List<Int>): String {
        if (indices.isEmpty()) return "范围：无可用页面"
        // indices 始终按升序构造，首尾即最小/最大页
        return "范围：第 ${indices.first() + 1}–${indices.last() + 1} 页（共 $pdfPageCount 页）"
    }

    private fun visionRangeTitle(indices: List<Int>): String {
        if (indices.isEmpty()) return "将发送 0 页图像"
        return "将发送第 ${indices.first() + 1}–${indices.last() + 1} 页图像（共 $pdfPageCount 页）"
    }

    /**
     * 范围提示。小文档用精确字数：整除到 k 会把几百字显示成「0k」，
     * 读起来像「什么都没发出去」，比不显示更糟。
     */
    private fun textScope(sentChars: Int): String =
        "范围：全文抽样，已发送 ${formatChars(sentChars)}/${formatChars(docFullText.length)} 字符"

    /**
     * 文本 AI 入口的统一前置检查。图片集/固定版式的 docFullText 只是一行占位摘要，
     * 把它发给模型等于「渲染正常但 AI 什么都没发」，必须先拦住并引导到视觉通道。
     */
    private fun requireExtractableText(action: String): Boolean {
        if (hasExtractableText(docFullText) && docBlocks?.isEmpty() != true) return true
        val visionAvailable = isImageFormat(bookFormat) || isImageArchive(bookFormat) ||
            bookFormat == "pdf" || pdfRenderer != null
        showResult(
            "这份文档没有可提取的文字",
            "它按页（图片）渲染，阅读正常，但文本通道没有内容可发送。\n" +
                if (visionAvailable) {
                    "请改用「理解包 / 前段摘要」的图片通道：会把你选择的页面图像发给视觉模型。" +
                        "（当前动作：$action）"
                } else {
                    "需要先在设置里配置支持图片输入的视觉模型，才能分析这类文档。（当前动作：$action）"
                }
        )
        return false
    }

    /** 用另一个 profile 构造一次性 Config（只在本次重试生效，不改变当前激活配置） */
    private fun configForProfile(profile: SavedAiProfile): AiClient.Config {
        val baseUrl = profile.baseUrl.trim()
        return AiClient.Config(
            baseUrl = baseUrl,
            key = db.getAiKey(profile.id, baseUrl),
            model = profile.textModel.trim(),
            visionModel = profile.visionModel.trim(),
            allowPrivateHttp = profile.allowPrivateHttp,
            chatPath = profile.chatPath,
            modelsPath = profile.modelsPath,
            authHeader = profile.authHeader,
            authPrefix = profile.authPrefix
        )
    }

    /** 整篇文档的带锚点上下文（预算内抽样），供批改等需要原文依据的功能使用 */
    private fun anchoredFullContext(): String {
        val blocks = docBlocks ?: return ""
        if (blocks.isEmpty()) return ""
        return runCatching {
            DocumentAiService.buildAnchoredContext(
                ParsedDoc(bookFormat.ifBlank { "txt" }, blocks, docFullText),
                bookFormat,
                DocumentAiService.MAX_CONTEXT_CHARS
            ).text
        }.getOrDefault("")
    }

    /** 从题面数出题目数量：优先行首编号，兜底按问号计数（不再假定「5 题 × 20 分」） */
    private fun countQuestions(text: String): Int {
        val numbered = Regex("(?m)^\\s*(?:#{1,6}\\s*)?(?:第\\s*)?(\\d{1,2})\\s*[.、)）:：]?")
            .findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..50 }
            .toSet()
        if (numbered.isNotEmpty()) return numbered.size
        return text.count { it == '？' || it == '?' }.coerceAtLeast(1)
    }

    /** 粗略判断源语言，只用于隐藏没有意义的翻译目标 */
    private fun detectSourceLanguage(text: String): String {
        val sample = text.take(400)
        if (sample.isBlank()) return ""
        val cjk = sample.count { it.code in 0x4E00..0x9FFF }
        val kana = sample.count { it.code in 0x3040..0x30FF }
        val latin = sample.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            kana > 2 -> "日本語"
            cjk * 100 / sample.length >= 15 -> "中文"
            latin * 100 / sample.length >= 40 -> "English"
            else -> ""
        }
    }

    private fun aiSummary(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        if (bookFormat == "pdf") {
            val total = pdfRenderer?.pageCount ?: 0
            val indices = (0 until min(6, total)).toList()
            if (indices.isEmpty()) { showResult("提示", "PDF 没有可分析页面"); return }
            val scope = pdfScope(indices)
            confirmVisionSend(
                "前段摘要",
                visionRangeTitle(indices),
                "目标服务：${providerHost()}\n只会发送这些页面的图像，文档原文件不会上传。"
            ) {
                runAiStream(
                    kind = "summary",
                    title = "前段摘要",
                    cfg = cfg,
                    scopeLine = scope,
                    onRetry = { alt -> aiSummary(alt) }
                ) { onDelta, onReason, onRestart ->
                    val pages = collectPdfPages(indices)
                    try {
                        AiClient.chatVision(cfg, SYS_PROMPT,
                            "这是一份课件/文档的第 ${indices.first() + 1}–${indices.last() + 1} 页截图（共 $total 页）。" +
                                "请生成摘要：先一句话概括主题，再用要点列出核心内容。",
                            pages, onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
                    } finally {
                        pages.forEach { it.recycle() }
                    }
                }
            }
        } else {
            if (!requireExtractableText("前段摘要")) return
            val text = docFullText
            if (text.isBlank()) { showResult("提示", "本文档没有可提取文本"); return }
            val sent = text.take(24000)
            runAiStream(
                kind = "summary",
                title = "前段摘要",
                cfg = cfg,
                scopeLine = textScope(sent.length),
                onRetry = { alt -> aiSummary(alt) }
            ) { onDelta, onReason, onRestart ->
                AiClient.chat(cfg, SYS_PROMPT,
                    "请为下面的内容生成摘要：先一句话概括，再用 3-6 个要点列出核心内容。\n\n【内容开始】\n$sent\n【内容结束】",
                    onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
            }
        }
    }

    private fun askAction(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        val input = EditText(act).apply {
            hint = "想问这份资料的任何问题…"
            setSingleLine(false)
            maxLines = 3
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("节选问答")
            .setView(input)
            .setPositiveButton("提问") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                if (bookFormat == "pdf") {
                    val total = pdfRenderer?.pageCount ?: 0
                    val indices = (0 until min(6, total)).toList()
                    if (indices.isEmpty()) {
                        showResult("提示", "PDF 没有可分析页面")
                        return@setPositiveButton
                    }
                    val scope = pdfScope(indices)
                    confirmVisionSend(
                        "节选问答",
                        visionRangeTitle(indices),
                        "目标服务：${providerHost()}\n只会发送这些页面的图像，文档原文件不会上传。"
                    ) {
                        runAiStream(
                            kind = "ask",
                            title = "问答 · $q",
                            cfg = cfg,
                            scopeLine = scope,
                            onRetry = { alt -> askAction(alt) }
                        ) { onDelta, onReason, onRestart ->
                            val pages = collectPdfPages(indices)
                            try {
                                AiClient.chatVision(cfg, SYS_PROMPT,
                                    "根据这些页面截图回答问题：$q\n若图中没有答案请直说。",
                                    pages, onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
                            } finally {
                                pages.forEach { it.recycle() }
                            }
                        }
                    }
                } else {
                    if (!requireExtractableText("节选问答")) return@setPositiveButton
                    val text = docFullText
                    if (text.isBlank()) {
                        showResult("提示", "本文档没有可提取文本")
                        return@setPositiveButton
                    }
                    val sent = text.take(24000)
                    runAiStream(
                        kind = "ask",
                        title = "问答 · $q",
                        cfg = cfg,
                        scopeLine = textScope(sent.length),
                        onRetry = { alt -> askAction(alt) }
                    ) { onDelta, onReason, onRestart ->
                        AiClient.chat(cfg, SYS_PROMPT,
                            "根据以下资料回答问题。若资料中没有答案请直说。\n\n问题：$q\n\n【资料开始】\n$sent\n【资料结束】",
                            onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun quizAction(cfgOverride: AiClient.Config? = null) {
        val cfg = cfgOverride ?: (aiReady() ?: return)
        if (bookFormat != "pdf" && !requireExtractableText("出题自测")) return
        val pdfIndices = if (bookFormat == "pdf") (0 until min(8, pdfRenderer?.pageCount ?: 0)).toList() else emptyList()
        val scope = if (bookFormat == "pdf") pdfScope(pdfIndices) else textScope(min(20000, docFullText.length))
        val build = { onDelta: (String) -> Unit, onReason: (String) -> Unit, onRestart: () -> Unit ->
            if (bookFormat == "pdf") {
                if (pdfIndices.isEmpty()) throw RuntimeException("PDF 没有可分析页面")
                val pages = collectPdfPages(pdfIndices)
                try {
                    AiClient.chatVision(cfg, SYS_PROMPT,
                        "根据这些页面截图出 5 道自测题（选择/简答混合）。只输出题目本身，不要给答案——用户作答后你会批改。",
                        pages, onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
                } finally {
                    pages.forEach { it.recycle() }
                }
            } else {
                val text = docFullText
                if (text.isBlank()) throw RuntimeException("本文档没有可提取文本")
                AiClient.chat(cfg, SYS_PROMPT,
                    "根据以下内容出 5 道自测题（选择/简答混合）。只输出题目本身，不要给出答案——用户稍后作答，你会批改。\n\n${text.take(20000)}",
                    onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
            }
        }
        val start = {
            runAiStream(
                kind = "quiz",
                title = "自测题",
                cfg = cfg,
                scopeLine = scope,
                onRetry = { alt -> quizAction(alt) },
                onDone = { questions ->
                    // 出题完成 → 引导作答批改闭环
                    android.app.AlertDialog.Builder(act)
                        .setTitle("✍️ 作答批改")
                        .setMessage("题目已生成。把你的答案写在下框（可简答，如「1A 2B 3…」），AI 将对照原文批改评分。")
                        .setPositiveButton("去作答") { _, _ -> answerQuiz(cfg, questions) }
                        .setNegativeButton("稍后", null)
                        .show().also { Glass.styleDialog(it, density(act)) }
                },
                call = build
            )
        }
        if (bookFormat == "pdf") {
            confirmVisionSend(
                "出题自测",
                visionRangeTitle(pdfIndices),
                "目标服务：${providerHost()}\n只会发送这些页面的图像，文档原文件不会上传。",
                start
            )
        } else {
            start()
        }
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
                // 判分依据改为带锚点的上下文，而不是硬截前 12k 字符
                val refText = if (bookFormat == "pdf") {
                    "(PDF 文档，按题面与常识判断)"
                } else {
                    anchoredFullContext().takeIf { it.isNotBlank() } ?: docFullText.take(12000)
                }
                // 每题分值按实际题数算，避免「固定 5 题 × 20 分」在题数不符时算错
                val count = countQuestions(questions).coerceAtLeast(1)
                val per = 100.0 / count
                // 批改记录入笔记：kind 必须非空，否则用户答案与判分结果都会被丢弃
                runAiStream(
                    kind = "quiz_grade",
                    title = "批改结果",
                    cfg = cfg,
                    scopeLine = if (bookFormat == "pdf") "范围：PDF 题面（未附原文）" else textScope(refText.length),
                    onRetry = { alt -> answerQuiz(alt, questions) }
                ) { onDelta, onReason, onRestart ->
                    AiClient.chat(cfg, SYS_PROMPT,
                        "你是阅卷老师。下面是原文、题目和学生的答案。请逐题判定对错并简要讲解，" +
                            "最后给总分（共 $count 题，每题约 ${"%.1f".format(per)} 分，满分 100）和一句鼓励。\n\n" +
                            "【题目】\n$questions\n\n【学生答案】\n$ans\n\n【原文参考】\n$refText",
                        onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
                }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /**
     * 段落菜单：本地动作与付费 AI 动作分成两组。
     *
     * 两点关键行为：
     * 1) [cfg] 未配置 AI 时**仍然要显示**本地分组——收藏/复制/划重点完全不联网，
     *    以前第一行就 `aiReady() ?: return`，未配置 Key 时连收藏都点不出来。
     * 2) 本地分组补上「划重点」「复制」「分享」，不再只有一条「收藏金句」。
     */
    private fun explainBlock(blockText: String, blockIndex: Int = -1, cfgOverride: AiClient.Config? = null) {
        val anchor = if (blockIndex >= 0) anchorForBlockIndex(blockIndex) else ""
        val preview = blockText.trim().take(40) + if (blockText.trim().length > 40) "…" else ""
        val sheet = BottomSheet(act, preview)
        sheet.section("本地 · 不联网")
        val quoteLabel = if (anchor.isNotBlank()) "收藏金句（$anchor）" else "收藏金句"
        sheet.item("book", quoteLabel, "存进笔记，列表里能跳回这一段") { saveQuote(blockText, anchor) }
        if (blockIndex >= 0) {
            val marked = highlightColors.containsKey(blockIndex)
            sheet.item("note", if (marked) "改颜色 / 清除重点" else "划重点", "选一个颜色标记这一段") {
                showHighlightPicker(blockIndex, blockText)
            }
        }
        sheet.item("note", "复制这段", "只写进剪贴板，不联网") { copyBlockText(blockText) }
        sheet.item("search", "搜索这段", "用开头几个字在当前文档里找相关内容") { searchForBlockText(blockText) }
        sheet.item("share", "分享这段", "生成本段图片分享出去") { shareBlockAsImage(blockText, anchor) }

        sheet.section("AI 操作", "会把这一段发往 ${providerHost()}，费用由服务商收取")
        sheet.item("bulb", "解释含义", "这段话是什么意思、为什么重要") {
            val cfg = cfgOverride ?: (aiReady() ?: return@item)
            runBlockAi("explain", "段落解释", blockText, cfg,
                "请解释下面这段话的含义（是什么意思、为什么重要），简洁作答：", anchor)
        }
        sheet.item("search", "翻译", "翻译成中文 / English / 日本語") {
            val cfg = cfgOverride ?: (aiReady() ?: return@item)
            translateBlock(blockText, anchor, cfg)
        }
        sheet.item("bulb", "大白话讲解", "用中学生能懂的话讲一遍") {
            val cfg = cfgOverride ?: (aiReady() ?: return@item)
            runBlockAi("explain", "大白话讲解", blockText, cfg,
                "用大白话给中学生讲解下面这段话，可以打比方，通俗但不失准确：", anchor)
        }
        sheet.item("chevron", "续写一段", "顺着文风自然续写 150–250 字") {
            val cfg = cfgOverride ?: (aiReady() ?: return@item)
            runBlockAi("continue", "续写", blockText, cfg,
                "顺着下面的文字风格与情节，自然续写一段（150-250字）：", anchor)
        }
        sheet.show()
    }

    /** 复制段落：纯本地动作，长按菜单里最常用的一条。 */
    private fun copyBlockText(blockText: String) {
        val text = blockText.trim()
        if (text.isEmpty()) return
        val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("段落", text))
        toast("已复制这一段")
    }

    /** 用段落开头作为搜索词，直接出命中计数。 */
    private fun searchForBlockText(blockText: String) {
        val query = blockText.trim().replace(Regex("\\s+"), " ").take(16)
        if (query.isBlank()) return
        searchInBook(query)
    }

    /** 把段落渲染成一张浅底图片分享：正文分享不该只依赖截图。 */
    private fun shareBlockAsImage(blockText: String, anchor: String) {
        val d = density(act)
        val title = db.getBook(bookId)?.title.orEmpty()
        val where = if (anchor.isNotBlank()) anchorTypeLabel(anchor.substringBefore(':')) + anchor.substringAfter(':') else ""
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(Glass.dp(22, d), Glass.dp(20, d), Glass.dp(22, d), Glass.dp(20, d))
        }
        card.addView(TextView(act).apply {
            text = blockText.trim().take(1200)
            textSize = 16f
            setTextColor(Color.parseColor("#20232A"))
            setLineSpacing(0f, 1.35f)
        }, LinearLayout.LayoutParams(Glass.dp(320, d), -2))
        card.addView(TextView(act).apply {
            text = "—— $title $where · 来自页枢".trim()
            textSize = 11f
            setTextColor(Color.parseColor("#8A8F9C"))
            setPadding(0, Glass.dp(14, d), 0, 0)
        })
        card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(Glass.dp(364, d), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(
            card.measuredWidth.coerceAtLeast(1),
            card.measuredHeight.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bitmap).let { canvas ->
            canvas.drawColor(Color.WHITE)
            card.draw(canvas)
        }
        val dir = File(act.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "quote_${System.currentTimeMillis()}.png")
        runCatching {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(android.content.Intent.createChooser(intent, "分享这段"))
        }.onFailure { showResult("无法分享", "生成分享图片失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 段落级 AI 动作：统一带 kind（结果必须能落笔记）、范围说明与重试入口 */
    private fun runBlockAi(
        kind: String,
        title: String,
        blockText: String,
        cfg: AiClient.Config,
        instruction: String,
        anchor: String
    ) {
        val clip = blockText.trim().take(3000)
        runAiStream(
            kind = kind,
            title = title,
            cfg = cfg,
            scopeLine = "范围：选中段落，已发送 ${clip.length} 字符",
            anchor = anchor,
            onRetry = { alt -> runBlockAi(kind, title, blockText, alt, instruction, anchor) }
        ) { onDelta, onReason, onRestart ->
            AiClient.chat(cfg, SYS_PROMPT, "$instruction\n\n「$clip」", onDelta, onReason = onReason, onRestart = onRestart, onUsage = ::recordUsage)
        }
    }

    /** 翻译：目标语言可选，源语言自身从列表里剔除（中文书里不再出现「翻译成中文」） */
    private fun translateBlock(blockText: String, anchor: String, cfg: AiClient.Config) {
        val source = detectSourceLanguage(blockText)
        val targets = listOf("中文", "English", "日本語").filterNot { it == source }
        if (targets.isEmpty()) {
            showResult("无法翻译", "这段文字的语言无法判断，暂时没有合适的翻译目标。")
            return
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("翻译成…")
            .setItems(targets.toTypedArray()) { _, which ->
                runBlockAi("translate", "翻译 · ${targets[which]}", blockText, cfg,
                    "把下面的文字翻译成流畅的${targets[which]}，只输出译文：", anchor)
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 收藏金句：带上块锚点，笔记列表里能跳回原文（锚点列待 Db.addNote 开放） */
    private fun saveQuote(blockText: String, anchor: String) {
        val quote = blockText.trim()
        if (quote.isEmpty()) return
        // 写库落到后台线程：Room 允许主线程查询，但写库不应占用点击帧
        Thread {
            saveAiNote("quote", quote, anchor, "")
            act.runOnUiThread { toast("已收藏金句 ⭐") }
        }.apply { isDaemon = true }.start()
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
        // 带引用的结果同样可以点回原文
        if (CITATION_RE.containsMatchIn(body)) applyCitationLinks(tv, body)
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

    /** 用给定关键词直接搜索（段落菜单的「搜索这段」），省去再手打一遍。 */
    private fun searchInBook(query: String) {
        if (bookFormat == "pdf") {
            showResult("提示", "PDF 为整页图片渲染，暂不支持文字搜索\n可改用 AI 问答定位内容")
            return
        }
        val q = query.trim()
        if (q.isNotEmpty()) doSearch(q)
    }

    /** 一次搜索的命中项。 */
    private data class SearchHit(val blockIndex: Int, val snippet: String)

    private fun doSearch(q: String) {
        val blocks = docBlocks ?: return
        val hits = mutableListOf<SearchHit>()
        for ((i, b) in blocks.withIndex()) {
            if (hits.size >= 200) break
            // 大小写不敏感查找直接用原串：lowercase() 可能改变长度（如 'İ'），
            // 用它的下标去切原串会指向错位甚至越界。
            val idx = b.text.indexOf(q, ignoreCase = true)
            if (idx < 0) continue
            val s = max(0, idx - 15)
            val e = min(b.text.length, idx + q.length + 25)
            val snip = (if (s > 0) "…" else "") +
                b.text.substring(s, e).replace('\n', ' ') +
                (if (e < b.text.length) "…" else "")
            hits.add(SearchHit(i, snip))
        }
        if (hits.isEmpty()) {
            showResult("书内搜索", "未找到「$q」")
            return
        }
        showSearchResults(q, hits)
    }

    /**
     * 搜索结果面板：带「第 i/N 处」计数与上一处/下一处循环跳转。
     * 原先只有一个静态列表，想找第 3 个匹配只能反复输入关键词。
     */
    private fun showSearchResults(q: String, hits: List<SearchHit>) {
        val d = density(act)
        val dialog = android.app.Dialog(act)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = chromeSurface(radiusDp = 22, alpha = 248)
            setPadding(Glass.dp(16, d), Glass.dp(16, d), Glass.dp(16, d), Glass.dp(10, d))
        }
        panel.addView(TextView(act).apply {
            text = "「$q」· 共 ${hits.size} 处"
            textSize = 16f
            setTextColor(Accent.chromeTextPrimary)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        val counter = TextView(act).apply {
            text = "尚未跳转，可点「下一处」"
            textSize = 11.5f
            setTextColor(Accent.chromeTextSecondary)
            setPadding(0, Glass.dp(3, d), 0, Glass.dp(10, d))
        }
        panel.addView(counter, LinearLayout.LayoutParams(-1, -2))

        // 当前命中下标；-1 表示还没跳转过，第一次「下一处」落在第 0 项
        var cursor = -1
        lateinit var rows: List<TextView>
        fun highlightRow() {
            rows.forEachIndexed { i, row ->
                row.background = chromeChip(i == cursor)
                row.setTextColor(if (i == cursor) Color.WHITE else Accent.chromeTextPrimary)
            }
        }
        fun step(delta: Int) {
            if (hits.isEmpty()) return
            cursor = if (cursor < 0) {
                if (delta > 0) 0 else hits.size - 1
            } else {
                (cursor + delta + hits.size) % hits.size
            }
            jumpToBlock(hits[cursor].blockIndex, flash = true)
            counter.text = "第 ${cursor + 1}/${hits.size} 处 · 第 ${hits[cursor].blockIndex + 1} 段"
            highlightRow()
        }

        val navRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun navButton(label: String, delta: Int) = TextView(act).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Accent.chromeTextPrimary)
            background = chromeChip(false)
            foreground = Glass.pressFx()
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(0, Glass.dp(46, d), 1f).also {
                it.marginStart = Glass.dp(3, d)
                it.marginEnd = Glass.dp(3, d)
            }
            setOnClickListener { step(delta) }
        }
        navRow.addView(navButton("↑ 上一处", -1))
        navRow.addView(navButton("↓ 下一处", 1))
        panel.addView(navRow, LinearLayout.LayoutParams(-1, -2))

        val listColumn = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        rows = hits.take(200).map { hit ->
            TextView(act).apply {
                text = "${hit.snippet}\n（第 ${hit.blockIndex + 1} 段）"
                textSize = 13f
                setTextColor(Accent.chromeTextPrimary)
                setPadding(Glass.dp(12, d), Glass.dp(9, d), Glass.dp(12, d), Glass.dp(9, d))
                foreground = Glass.pressFx()
                contentDescription = "跳到第 ${hit.blockIndex + 1} 段"
                setOnClickListener {
                    cursor = hits.indexOf(hit)
                    jumpToBlock(hit.blockIndex, flash = true)
                    counter.text = "第 ${cursor + 1}/${hits.size} 处 · 第 ${hit.blockIndex + 1} 段"
                    highlightRow()
                }
                background = chromeChip(false)
            }
        }
        rows.forEach { listColumn.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val listScroll = ScrollView(act).apply {
            addView(listColumn, LayoutParams(-1, -2))
        }
        panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).also {
            it.topMargin = Glass.dp(10, d)
        })
        panel.addView(TextView(act).apply {
            text = "关闭"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Accent.chromeTextTertiary)
            background = chromeChip(false)
            foreground = Glass.pressFx()
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, Glass.dp(42, d)).also { it.topMargin = Glass.dp(10, d) })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.56f }
        }
        dialog.show()
        val availW = act.resources.displayMetrics.widthPixels - Glass.dp(32, d)
        val availH = (act.resources.displayMetrics.heightPixels * 0.74f).toInt()
        dialog.window?.setLayout(min(availW, Glass.dp(430, d)), availH)
        // 打开时先跳到第一处，用户不用再点一次
        step(1)
    }

    /** 窄屏「更多」：把放不下的动作收进同一个分组弹层，保证底栏每格都有足够热区。 */
    private fun showMoreActions() {
        BottomSheet(act, "更多")
            .section("本书")
            .item("folder", "笔记", "查看这本书的摘记、摘要与问答") { (act as MainActivity).showNotes(bookId) }
            .item("list", "目录", "跳到章节或页码") { listToc() }
            .item("note", "AI 助手", "理解包、问答、出题与聊天") { showAiMenu() }
            .section("阅读")
            .item("sliders", "阅读设置", "主题 / 行距 / 页边距 / 字号") { showReaderSettings() }
            .item("bulb", "亮度", "单独调节阅读器亮度") { brightnessDialog() }
            .section("关于本文档")
            .item("folder", "文件信息", "格式 / 大小 / 导入时间 / 内容指纹") { showFileInfo() }
            .show()
    }

    /** 目录：列出全部章节标题（EPUB 的 h1/h2 与 TXT 识别的章回），点击直达；PDF 走页码跳转 */
    private fun listToc() {
        if (bookFormat == "pdf" || pdfRenderer != null) {
            listPdfPages()
            return
        }
        val blocks = docBlocks ?: return
        val allHeads = if (tocHeads.isNotEmpty()) {
            tocHeads.filter { it.second.isNotBlank() }
        } else {
            blocks.mapIndexedNotNull { i, b ->
                if (b.type == Block.HEADING) i to b.text else null
            }
        }
        val heads = allHeads.take(200)
        if (heads.isEmpty()) {
            showResult("目录", "本文档没有识别到章节标题")
            return
        }
        // 计算当前阅读位置所在章节，在目录中标注（按真实可见块，而非滚动比例）
        val curIdx = currentBlockIndex()
        var curHead = -1
        if (curIdx >= 0) heads.forEachIndexed { w, h -> if (h.first <= curIdx) curHead = w }
        val d = density(act)
        val total = blocks.size
        val dialog = android.app.Dialog(act)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = chromeSurface(radiusDp = 22, alpha = 248)
            setPadding(Glass.dp(16, d), Glass.dp(16, d), Glass.dp(16, d), Glass.dp(10, d))
        }
        // 截断时把总数写清楚，避免"目录 · 200 章"让人以为书只有 200 章
        val tocTitle = if (allHeads.size > heads.size) {
            "目录 · 前 ${heads.size} 章（共 ${allHeads.size} 章）"
        } else {
            "目录 · ${heads.size} 章"
        }
        panel.addView(TextView(act).apply {
            text = tocTitle
            textSize = 16f
            setTextColor(Accent.chromeTextPrimary)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, Glass.dp(10, d))
        }, LinearLayout.LayoutParams(-1, -2))

        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val rowViews = heads.mapIndexed { w, h ->
            // 每章显示起始位置占全书的百分比：只有章名时无法判断「还剩多少」
            val percent = if (total > 1) (h.first * 100 / (total - 1)) else 0
            TextView(act).apply {
                text = "${h.second.take(38)}   ·   $percent%"
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (w == curHead) Color.WHITE else Accent.chromeTextPrimary)
                background = chromeChip(w == curHead)
                foreground = Glass.pressFx()
                contentDescription = "跳到${h.second}，全书 $percent%"
                setPadding(Glass.dp(12, d), Glass.dp(10, d), Glass.dp(12, d), Glass.dp(10, d))
                setOnClickListener {
                    dialog.dismiss()
                    jumpToBlock(h.first)
                }
            }
        }
        rowViews.forEach { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val scroll = ScrollView(act).apply { addView(column, LayoutParams(-1, -2)) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(TextView(act).apply {
            text = "关闭"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Accent.chromeTextTertiary)
            background = chromeChip(false)
            foreground = Glass.pressFx()
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, Glass.dp(42, d)).also { it.topMargin = Glass.dp(10, d) })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.56f }
        }
        dialog.show()
        val availW = act.resources.displayMetrics.widthPixels - Glass.dp(32, d)
        dialog.window?.setLayout(min(availW, Glass.dp(430, d)), (act.resources.displayMetrics.heightPixels * 0.74f).toInt())
        // 自动滚到当前章：长书里每次都要自己找当前位置
        if (curHead > 0) {
            scroll.post { scroll.scrollTo(0, rowViews[curHead].top.coerceAtLeast(0)) }
        }
    }

    /** PDF 页码跳转：列出全部页，点击滚到对应页 */
    private fun listPdfPages() {
        val n = pdfPageCount
        if (n <= 0) return
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
        // 音量键翻页此前只有读取方、没有写入方，开关常驻在这里最省入口
        val flip = android.widget.CheckBox(act).apply {
            text = "音量键翻页（单手阅读）"
            textSize = 14f
            isChecked = db.getSetting("reader_volume_flip") != "0"
            setPadding(Glass.dp(20, d), Glass.dp(6, d), Glass.dp(20, d), Glass.dp(10, d))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
            addView(flip)
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
            .setTitle("亮度与翻页")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                db.setSetting("reader_brightness", (seek.progress + 10).toString())
                db.setSetting("reader_volume_flip", if (flip.isChecked) "1" else "0")
                volumeFlipCached = null
                db.deleteSetting("screen_bright")
            }
            .setNegativeButton("恢复默认") { _, _ ->
                db.deleteSetting("reader_brightness")
                db.deleteSetting("screen_bright")
                val lp = act.window.attributes
                lp.screenBrightness = -1f
                act.window.attributes = lp
            }
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 跳转到指定段落（docBlocks 顺序与正文子 View 一致）；目标未渲染时先续载 */
    private fun jumpToBlock(index: Int, flash: Boolean = false) {
        val sv = sc ?: return
        val box = sv.getChildAt(0) as? LinearLayout ?: return
        val needsAppend = pdfRenderer == null && index >= renderedUpTo
        if (pdfRenderer == null && !ensureRenderedUpTo(index, PRELOAD_MAX_CHUNKS)) {
            // 超长文档可能一次补载不完：必须给出反馈，不能点了没反应
            showResult("提示", "目标位置较远，正在加载，请稍后再试")
            return
        }
        val v = box.getChildAt(index)
        if (v == null) {
            showResult("提示", "目标位置较远，正在加载，请稍后再试")
            return
        }
        val scrollToTarget = Runnable {
            val target = box.getChildAt(index) ?: return@Runnable
            sv.smoothScrollTo(0, max(0, target.top - Glass.dp(56, density(act))))
        }
        if (needsAppend) {
            // 刚追加的块还没测量，top 仍是 0，必须等这次布局完成再滚，否则会跳到文首
            sv.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val observer = sv.viewTreeObserver
                        if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                        scrollToTarget.run()
                    }
                }
            )
        } else {
            sv.post(scrollToTarget)
        }
        if (flash) {
            // 命中段高亮：金黄停留后渐隐回底色
            v.post {
                val to = themeBackground()
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
                restoreScrollNow(sv, p)
                if (pdfRenderer != null) renderPdfWindow(force = true)
            }
        }
    }

    private fun restoreScrollNow(sv: ScrollView, progress: Float) {
        if (progress <= 0.001f || progress >= 0.999f) return
        val child = sv.getChildAt(0) ?: return
        val range = (child.height - sv.height).coerceAtLeast(0)
        sv.scrollTo(0, (range * progress.coerceIn(0f, 1f)).toInt())
    }

    /**
     * 进度：统一走 [ProgressModel]，与进度条、顶栏百分比共用同一口径。
     * 位置取「真实可见块/页的下标」，而不是已渲染前缀的滚动比例——
     * 前缀比例在分块加载时会把「刚滚到已加载末尾」算成 100%。
     */
    private fun saveProgress() {
        val sv = sc ?: return
        val child = sv.getChildAt(0) ?: return
        if (sv.height <= 0) return
        val (index, total) = progressPosition()
        if (index < 0) return
        val fullyLoaded = pdfRenderer != null || total <= 0 || renderedUpTo >= total
        val pr = ProgressModel.progressOf(
            positionIndex = index,
            totalCount = total,
            fullyLoaded = fullyLoaded,
            fitsOnScreen = child.height <= sv.height,
            interacted = hasInteracted
        )
        db.updateProgress(bookId, pr)
        rememberPosition()
    }

    /**
     * R44：把「读到哪一章/第几页」写进 settings，书架条目据此显示比百分比更有信息量的位置。
     * 只在标签变化时写库——saveProgress 在滚动中每 1.5 秒就会被调用一次。
     */
    private fun rememberPosition() {
        val label = currentPositionLabel()
        if (label.isBlank() || label == lastWrittenPosition) return
        lastWrittenPosition = label
        runCatching { db.setSetting(positionSettingKey(bookId), label) }
    }

    /** 当前位置下标与总下标数；PDF 用页，其余用块。返回 (-1, 0) 表示暂不可用。 */
    private fun progressPosition(): Pair<Int, Int> {
        if (pdfRenderer != null || bookFormat == "pdf") {
            val total = pdfRenderer?.pageCount ?: pdfPageCount
            if (total <= 0) return -1 to 0
            return currentPdfPageIndex().coerceIn(0, total - 1) to total
        }
        val total = docBlocks?.size ?: 0
        if (total <= 0) return -1 to 0
        val index = currentBlockIndex()
        if (index < 0) return -1 to 0
        return index.coerceIn(0, total - 1) to total
    }

    /** 当前进度（0..1），进度条与顶栏百分比都用它。 */
    private fun currentProgress(): Float {
        val (index, total) = progressPosition()
        if (index < 0) return 0f
        val sv = sc
        val fullyLoaded = pdfRenderer != null || total <= 0 || renderedUpTo >= total
        val fits = sv != null && (sv.getChildAt(0)?.height ?: 0) <= sv.height
        return ProgressModel.progressOf(index, total, fullyLoaded, fits, hasInteracted)
    }

    /** 音量键翻页：单手阅读（设置 reader_volume_flip=0 可关）；由 MainActivity 的按键分发调用 */
    fun handleVolumeKey(event: android.view.KeyEvent): Boolean {
        val vk = event.keyCode
        if (vk != android.view.KeyEvent.KEYCODE_VOLUME_UP && vk != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // 只消费 DOWN 是不够的：UP 会漏给系统，音量面板和提示音照样弹出来。
        // 所以 DOWN 时记下已消费，UP 时同样消费掉。
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            val consumed = volumeKeyConsumed
            volumeKeyConsumed = false
            return consumed
        }
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (!volumeFlipEnabled()) return false
        val sv = sc ?: return false
        val page = (sv.height * 0.85).toInt()
        sv.smoothScrollBy(0, if (vk == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) page else -page)
        volumeKeyConsumed = true
        return true
    }

    private var volumeKeyConsumed = false
    private var volumeFlipCached: Boolean? = null

    /** 按键是高频路径，不能每次都去查 settings 表（主线程 Room 读）。 */
    private fun volumeFlipEnabled(): Boolean {
        volumeFlipCached?.let { return it }
        val enabled = db.getSetting("reader_volume_flip") != "0"
        volumeFlipCached = enabled
        return enabled
    }

    /** 用户是否真的滚动过。短文不足一屏时用它避免「一打开就 100%」。 */
    private var hasInteracted = false

    /**
     * 最近一次请求的服务端用量。同一时刻只允许一个 AI 任务（activeAiTask 互斥），
     * 所以单个字段就够，不必把 usage 一路穿过 call lambda。
     * 服务商不返回 usage 时保持 null，界面就不显示这一段。
     */
    private var lastUsage: AiClient.TokenUsage? = null

    /** 上一次写入 settings 的位置标签，避免每次滚动都写一次库。 */
    private var lastWrittenPosition = ""

    private fun recordUsage(usage: AiClient.TokenUsage) {
        lastUsage = usage
    }

    /** 计时起点；0 表示当前不在计时。 */
    private var readSessionStart = 0L

    /** 本次 attach 的墙钟起点：用于校验「累计时长不超过视图真正在前台的时间」。 */
    private var attachedAtMs = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedAtMs = System.currentTimeMillis()
        resumeReadSession()
    }

    /** 累计阅读时长：离开/暂停阅读器时落库 */
    private fun flushReadTime() {
        val started = readSessionStart
        if (started == 0L) return
        // 先清零：即使写库抛异常，也不会因为重复调用而把同一段时长记两次
        readSessionStart = 0L
        val counted = ReadSession.countMs(
            elapsedMs = System.currentTimeMillis() - started,
            wallClockMs = System.currentTimeMillis() - attachedAtMs,
            capMs = READ_SESSION_CAP_MS
        )
        if (counted <= 0L) return
        try { db.addReadTime(bookId, counted) } catch (e: Exception) {}
    }

    /** 由宿主在 onStop 调用：退到后台时暂停计时并落库，避免用户在别处仍被计入阅读时长 */
    fun pauseReadSession() {
        saveProgress()
        flushReadTime()
    }

    /**
     * 由宿主在 onStart 调用：回到前台重新开始计时。
     * 幂等——宿主多次 onStart（或宽屏布局下 AndroidView 重新 update）不会把起点往后推、
     * 也不会重复开启第二段计时。
     */
    fun resumeReadSession() {
        if (readSessionStart == 0L) readSessionStart = System.currentTimeMillis()
    }

    override fun onDetachedFromWindow() {
        activeAiTask?.let { task ->
            task.cancelled.set(true)
            task.token.cancel()
            // 对话框不会被系统随视图一起销毁，必须主动关闭，否则工作线程仍持有已 detach 的视图树
            task.dialog?.let { d -> try { d.dismiss() } catch (_: Exception) {} }
            task.dialog = null
            activeAiTask = null
        }
        repeatHandler?.let { h -> repeatLoops.forEach { h.removeCallbacks(it) } }
        repeatLoops.clear()
        // pauseReadSession 是幂等的：saveProgress + flushReadTime 各只生效一次
        pauseReadSession()
        super.onDetachedFromWindow()
        closePdf()
        // 先解除 ImageView 对位图的引用，再回收，避免 detach 后重绘使用已回收位图
        imageView?.setImageDrawable(null)
        imageView = null
        imageBitmap?.recycle()
        imageBitmap = null
        // 图片集的每一页都持有位图，同样先解引用再回收
        boxRef?.let { box ->
            for (i in 0 until box.childCount) {
                (box.getChildAt(i) as? ImageView)?.setImageDrawable(null)
            }
        }
        archiveBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        archiveBitmaps.clear()
        archivePageFiles = emptyList()
    }
}
