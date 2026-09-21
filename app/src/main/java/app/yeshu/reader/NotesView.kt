package app.yeshu.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 笔记页：某本书的全部 AI 产物（摘要/问答/自测题），点击查看、长按删除 */
class NotesView(private val act: Activity, private val bookId: Long) : FrameLayout(act) {

    private var aiProgress: TextView? = null
    private var digestToken: AiClient.CancelToken? = null

    private val db = Db(act)
    private lateinit var listBox: LinearLayout
    // 跟随主题偏好，避免与 Compose 页面之间明暗跳变
    private val pal by lazy { LegacyPalette.of(act) }
    private var filterKind: String? = null   // null=全部
    private var chipRow: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var undoBar: View? = null
    private var pendingDismiss: Runnable? = null

    init {
        setBackgroundColor(pal.bg)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(-1, -1))
        applySystemBarInsets(col)

        val d = density(act)
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(16, d), Glass.dp(14, d), Glass.dp(16, d), Glass.dp(8, d))
        }
        top.addView(FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            // 触控目标 ≥48dp；自绘图标无自身语义，标签挂在容器上
            layoutParams = LinearLayout.LayoutParams(Glass.dp(48, d), Glass.dp(48, d))
            contentDescription = "返回阅读"
            addView(IconView(act, "back", 22, pal.icon).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).openReader(bookId) }
        })
        val book = db.getBook(bookId)
        top.addView(TextView(act).apply {
            text = "笔记 · ${book?.title ?: ""}"
            textSize = 18f
            setTextColor(pal.textP)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // ✨ AI 整理：把本书散乱笔记归纳成一页精读笔记
        top.addView(TextView(act).apply {
            text = "✨"
            textSize = 15f
            setTextColor(pal.textP)
            background = Glass.pillBg(if (pal.dark) Color.argb(60, 255, 255, 255) else Color.argb(26, 23, 26, 43))
            setPadding(Glass.dp(13, d), Glass.dp(7, d), Glass.dp(13, d), Glass.dp(7, d))
            foreground = Glass.pressFx()
            isClickable = true
            contentDescription = "AI 整理笔记"
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Glass.dp(8, d)
            layoutParams = lp
            setOnClickListener { aiDigest() }
        })
        // 导出全部笔记（Markdown → 系统分享面板）
        top.addView(TextView(act).apply {
            text = "导出"
            textSize = 14f
            setTextColor(pal.textP)
            background = Glass.pillBg(if (pal.dark) Color.argb(60, 255, 255, 255) else Color.argb(26, 23, 26, 43))
            setPadding(Glass.dp(14, d), Glass.dp(7, d), Glass.dp(14, d), Glass.dp(7, d))
            foreground = Glass.pressFx()
            isClickable = true
            setOnClickListener { exportAll(db.getBook(bookId)?.title ?: "笔记") }
        })
        col.addView(top, LayoutParams(-1, -2))

        // 分类过滤 chips：全部 + NoteKindLabels 的全部 kind（含 AI 书籍简介 intro）。
        // 条目变多后一行放不下，放进横向滚动容器，保证末尾的 chip 在小屏上也能点到。
        val chips = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Glass.dp(16, d), Glass.dp(6, d), Glass.dp(16, d), Glass.dp(2, d))
        }
        fun buildChips() {
            chips.removeAllViews()
            val kinds: List<Pair<String, String?>> =
                listOf("全部" to null) + NoteKindLabels.order.map { NoteKindLabels.label(it) to it }
            kinds.forEach { (label, kind) ->
                val active = filterKind == kind
                val c = TextView(act).apply {
                    text = label
                    textSize = 12f
                    setTextColor(if (active) Color.WHITE else pal.textS)
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = Glass.pillBg(
                        if (active) Color.argb(140, 90, 130, 220)
                        else if (pal.dark) Color.argb(45, 255, 255, 255) else Color.argb(24, 23, 26, 43)
                    )
                    setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(6, d))
                    foreground = Glass.pressFx()
                    isClickable = true
                    // 触控目标补足到 48dp，并给无障碍提供明确语义
                    minHeight = Glass.dp(48, d)
                    gravity = Gravity.CENTER
                    contentDescription = if (active) "筛选：$label（已选中）" else "筛选：$label"
                    val lp = LinearLayout.LayoutParams(-2, -2)
                    lp.marginEnd = Glass.dp(8, d)
                    layoutParams = lp
                    setOnClickListener { filterKind = kind; buildChips(); refresh() }
                }
                chips.addView(c)
            }
        }
        buildChips()
        chipRow = chips
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips, FrameLayout.LayoutParams(-2, -2))
        }, LayoutParams(-1, -2))

        val sc = ScrollView(act)
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        listBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(16, d), Glass.dp(8, d), Glass.dp(16, d), Glass.dp(40, d))
        }
        sc.addView(listBox, LayoutParams(-1, -2))
        refresh()
    }

    fun refresh() {
        val d = density(act)
        listBox.removeAllViews()
        var notes = db.listNotes(bookId)
        if (filterKind != null) notes = notes.filter { it.kind == filterKind }
        if (notes.isEmpty()) {
            val hint = Glass.emptyState(
                act, "📝",
                if (filterKind == null) "还没有笔记" else "该分类暂无笔记",
                if (filterKind == null) "在阅读器里点「AI」生成摘要、提问或出题\n结果会自动保存在这里"
                else "换个分类看看，或在阅读器里继续生成"
            )
            val hp = LayoutParams(-1, -2)
            hp.topMargin = Glass.dp(30, d)
            listBox.addView(hint, hp)
            return
        }
        for (row in notes) {
            val label = NoteKindLabels.label(row.kind)
            // 角色前缀只在列表里用 emoji 表达；全文弹层展示去掉前缀的正文
            val raw = when {
                row.kind == "chat" && row.content.startsWith("U:") -> row.content.removePrefix("U:")
                row.kind == "chat" && row.content.startsWith("A:") -> row.content.removePrefix("A:")
                else -> row.content
            }
            val body = when {
                row.kind == "chat" && row.content.startsWith("U:") -> "🙋 $raw"
                row.kind == "chat" && row.content.startsWith("A:") -> "🤖 $raw"
                else -> raw
            }
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.card()
                foreground = Glass.pressFx()
                isClickable = true
                isFocusable = true
                elevation = Glass.dp(3, d).toFloat()
                setPadding(Glass.dp(16, d), Glass.dp(13, d), Glass.dp(12, d), Glass.dp(10, d))
                setOnClickListener { showFull(raw) }
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    confirmDelete(row)
                    true
                }
                installPressFeedback(this)
            }
            card.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(act).apply {
                    text = label
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(72, 76, 196))
                    background = Glass.pillBg(Color.argb(36, 91, 95, 245))
                    setPadding(Glass.dp(10, d), Glass.dp(4, d), Glass.dp(10, d), Glass.dp(4, d))
                })
                addView(TextView(act).apply {
                    text = "点击查看完整内容"
                    textSize = 10f
                    gravity = Gravity.END
                    setTextColor(Color.argb(145, 34, 34, 34))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            card.addView(TextView(act).apply {
                text = body.take(180) + if (body.length > 180) "…" else ""
                textSize = 14f
                setTextColor(Color.parseColor("#222222"))
                setLineSpacing(0f, 1.12f)
                setPadding(0, Glass.dp(10, d), Glass.dp(4, d), Glass.dp(7, d))
            }, LinearLayout.LayoutParams(-1, -2))
            card.addView(LinearLayout(act).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(TextView(act).apply {
                    text = "删除"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(190, 48, 61))
                    background = Glass.pillBg(Color.argb(32, 220, 52, 68))
                    foreground = Glass.pressFx()
                    isClickable = true
                    setPadding(Glass.dp(13, d), Glass.dp(6, d), Glass.dp(13, d), Glass.dp(6, d))
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        confirmDelete(row)
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Glass.dp(12, d)
            listBox.addView(card, lp)
        }
    }

    private fun confirmDelete(row: NoteRow) {
        AlertDialog.Builder(act)
            .setTitle("删除这条笔记？")
            .setMessage("删除后可在底部提示中撤销。")
            .setPositiveButton("删除笔记") { _, _ ->
                db.deleteNote(row.id)
                refresh()
                showUndoSnackbar(row)
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun showUndoSnackbar(row: NoteRow) {
        pendingDismiss?.let(mainHandler::removeCallbacks)
        undoBar?.let(::removeView)
        val d = density(act)
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = Glass.dp(14, d).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.argb(244, 26, 32, 52))
                cornerRadius = Glass.dp(18, d).toFloat()
                setStroke(1, Color.argb(70, 255, 255, 255))
            }
            setPadding(Glass.dp(18, d), Glass.dp(12, d), Glass.dp(9, d), Glass.dp(12, d))
        }
        bar.addView(TextView(act).apply {
            text = "笔记已删除"
            textSize = 14f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(act).apply {
            text = "撤销"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(132, 190, 255))
            background = Glass.pillBg(Color.argb(34, 132, 190, 255))
            foreground = Glass.pressFx()
            isClickable = true
            setPadding(Glass.dp(14, d), Glass.dp(7, d), Glass.dp(14, d), Glass.dp(7, d))
            setOnClickListener {
                pendingDismiss?.let(mainHandler::removeCallbacks)
                // 带上原 id 与 createdAt，否则撤销后的笔记会拿到新 id 与时间戳，
                // 在按 id DESC 排序的列表里跳到最前面。
                db.addNote(row.bookId, row.kind, row.content, row.id, row.createdAt)
                refresh()
                dismissUndoBar(bar)
            }
        })
        val navigationBottom = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val lp = LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = Glass.dp(16, d)
            rightMargin = Glass.dp(16, d)
            bottomMargin = navigationBottom + Glass.dp(18, d)
        }
        addView(bar, lp)
        undoBar = bar
        bar.alpha = 0f
        bar.translationY = Glass.dp(20, d).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        pendingDismiss = Runnable { dismissUndoBar(bar) }.also {
            mainHandler.postDelayed(it, 6_000L)
        }
    }

    private fun dismissUndoBar(bar: View) {
        if (undoBar !== bar) return
        bar.animate().alpha(0f).translationY(Glass.dp(16, density(act)).toFloat()).setDuration(150L)
            .withEndAction {
                if (bar.parent === this) removeView(bar)
                if (undoBar === bar) undoBar = null
            }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPressFeedback(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    target.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
            }
            false
        }
    }

    override fun onDetachedFromWindow() {
        pendingDismiss?.let(mainHandler::removeCallbacks)
        pendingDismiss = null
        // 离开页面即取消进行中的 AI 整理，避免继续占用连接、也不写半截结果
        digestToken?.cancel()
        digestToken = null
        dismissAiProgress()
        super.onDetachedFromWindow()
    }

    /**
     * 全文弹层：引用锚点（[CHAPTER:2] / [PAGE:12] …）渲染成可点链接。
     * 有链接时必须用 LinkMovementMethod（与文本选择互斥，两者同时开会让链接点不动），
     * 没有链接时保持可选中复制。
     */
    private fun showFull(content: String) {
        val d = density(act)
        val linked = withCitationLinks(content)
        val hasLinks = linked is android.text.Spanned &&
            linked.getSpans(0, linked.length, android.text.style.ClickableSpan::class.java).isNotEmpty()
        val sc = ScrollView(act)
        sc.addView(TextView(act).apply {
            text = linked
            textSize = 14f
            // 对话框底色跟随主题，正文颜色也必须跟着走
            setTextColor(if (pal.dark) pal.textP else Color.parseColor("#222222"))
            if (hasLinks) {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            } else {
                setTextIsSelectable(true)
            }
            setPadding(Glass.dp(20, d), Glass.dp(14, d), Glass.dp(20, d), Glass.dp(20, d))
        })
        AlertDialog.Builder(act)
            .setView(sc)
            .setPositiveButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 把引用锚点包成可点 span；无锚点时原样返回，避免多建一个 Spannable。 */
    private fun withCitationLinks(content: String): CharSequence {
        val matches = CITATION.findAll(content).toList()
        if (matches.isEmpty()) return content
        val linkColor = if (pal.dark) Color.parseColor("#8FB6FF") else Color.parseColor("#3B5BDB")
        val out = android.text.SpannableString(content)
        for (match in matches) {
            val anchor = match.value
            out.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        android.widget.Toast.makeText(
                            act, "引用来源：${anchorLabel(anchor)}", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = true
                    }
                },
                match.range.first,
                match.range.last + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return out
    }

    /** 锚点 token → 中文位置描述；解析失败时回退原始 token。 */
    private fun anchorLabel(token: String): String {
        val parts = token.removePrefix("[").removeSuffix("]").split(":")
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return token
        return when (parts.firstOrNull()) {
            "PAGE" -> "第 $index 页"
            "CHAPTER" -> "第 $index 章"
            "PARAGRAPH" -> "第 $index 段"
            "SLIDE" -> "第 $index 张幻灯片"
            "IMAGE" -> "第 $index 张图"
            else -> token
        }
    }

    /** ✨ AI 整理：全部笔记归纳为一页结构化精读笔记，存 kind=digest */
    private fun aiDigest() {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            android.widget.Toast.makeText(act, "请先在书架「AI 设置」配置接口", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val notes = db.listNotes(bookId)
        if (notes.isEmpty()) {
            android.widget.Toast.makeText(act, "还没有任何笔记，先去阅读器里用 AI 生成一些吧", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val book = db.getBook(bookId)
        // 可取消请求：浮层点按或视图分离时中断网络 I/O；已生成好的结果仍会落库，不静默丢弃
        val token = AiClient.CancelToken().also { digestToken = it }
        val pill = showAiProgress("生成中… 点按停止", token)
        pill.contentDescription = "正在归纳 ${notes.size} 条笔记，点按停止"
        Thread({
            var err: String? = null
            var reply = ""
            val streamed = StringBuilder()
            try {
                val body = notes.joinToString("\n\n") { row ->
                    "【${NoteKindLabels.label(row.kind)}】\n${row.content.take(1500)}"
                }
                // 输出语言跟随笔记（即书籍）的主要语言，而不是固定中文
                val hint = languageHint(body.take(2000))
                reply = AiClient.withCancellation(token) {
                    AiClient.chat(cfg,
                        "你是专业的读书教练。输出语言：${hint ?: "与书籍主要语言一致"}，输出结构化 Markdown 风格的纯文本。",
                        "以下是读者读《${book?.title ?: "一本书"}》期间积累的全部 AI 笔记。请归纳整理成一页「精读笔记」：" +
                            "① 核心主题一句话；② 3-5 个关键要点（合并重复内容）；③ 值得记住的金句摘录（如有）；④ 一条行动建议。" +
                            "只输出整理结果。\n\n【笔记开始】\n${body.take(22000)}\n【笔记结束】",
                        onDelta = { delta ->
                            streamed.append(delta)
                            act.runOnUiThread {
                                if (digestToken !== token || !isAttachedToWindow) return@runOnUiThread
                                pill.text = "✨ " + streamed.toString().trim().takeLast(PROGRESS_TAIL_CHARS)
                            }
                        },
                        onRestart = {
                            // 断流重发：作废已上屏增量，避免「半截 + 全文」重复
                            streamed.setLength(0)
                            act.runOnUiThread {
                                if (digestToken === token && isAttachedToWindow) pill.text = "✨ 生成中… 点按停止"
                            }
                        },
                        timeoutMs = 120_000)
                }
                // 先落库再回主线程：视图若已分离，结果也不该丢；取消则不写半截结果
                if (!token.isCancelled()) db.addNote(bookId, "digest", reply)
            } catch (t: Throwable) {
                if (!token.isCancelled()) err = AiClient.userFacingError(t)
            }
            val e = err
            val cancelled = token.isCancelled()
            act.runOnUiThread {
                if (digestToken === token) digestToken = null
                // 视图已分离：结果已落库，不再触碰 UI
                if (!isAttachedToWindow) return@runOnUiThread
                dismissAiProgress()
                when {
                    cancelled -> Unit
                    e != null -> AlertDialog.Builder(act).setTitle("AI 调用失败").setMessage(e)
                        .setPositiveButton("关闭", null).show().also { Glass.styleDialog(it, density(act)) }
                    else -> {
                        filterKind = "digest"
                        refresh()
                        showFull(reply)
                    }
                }
            }
        }, "yeshu-digest").apply { isDaemon = true }.start()
    }

    /**
     * 笔记正文主要语言的弱判断。DocumentAiService.detectLanguage 尚未落地，
     * 这里只按字符脚本兜底，避免英文/日文书笔记被归纳成中文。
     */
    private fun languageHint(sample: String): String? {
        val cjk = sample.count { it.code in 0x4E00..0x9FFF }
        val kana = sample.count { it.code in 0x3040..0x30FF }
        val latin = sample.count { it.isLetter() && it.code < 0x250 }
        return when {
            kana > 20 -> "日语"
            cjk > 20 && cjk >= latin / 3 -> "简体中文"
            latin > 20 -> "英语"
            else -> null
        }
    }

    /**
     * 不使用 ProgressDialog：它持有 Activity 窗口且无法取消；浮层随视图分离自动消失。
     * 传入 token 时浮层可点按取消，并返回 TextView 供流式增量更新。
     */
    private fun showAiProgress(message: String, token: AiClient.CancelToken? = null): TextView {
        dismissAiProgress()
        val d = density(act)
        val tv = TextView(act).apply {
            text = "✨ $message"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxWidth = Glass.dp(300, d)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(14, d).toFloat()
                setColor(Color.argb(232, 26, 32, 52))
            }
            setPadding(Glass.dp(20, d), Glass.dp(14, d), Glass.dp(20, d), Glass.dp(14, d))
            elevation = Glass.dp(12, d).toFloat()
            if (token != null) {
                isClickable = true
                foreground = Glass.pressFx()
                contentDescription = "$message，点按停止"
                setOnClickListener {
                    token.cancel()
                    dismissAiProgress()
                }
            } else {
                contentDescription = message
            }
        }
        addView(tv, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Glass.dp(112, d)
        })
        aiProgress = tv
        return tv
    }

    private fun dismissAiProgress() {
        aiProgress?.let { if (it.parent === this) removeView(it) }
        aiProgress = null
    }

    /** 全部笔记导出为 Markdown，走系统分享面板 */
    private fun exportAll(bookTitle: String) {
        val notes = db.listNotes(bookId)
        if (notes.isEmpty()) {
            android.widget.Toast.makeText(act, "还没有笔记可导出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val md = StringBuilder("# 《$bookTitle》AI 笔记\n\n")
        notes.forEach { row ->
            md.append("## ").append(NoteKindLabels.label(row.kind)).append("\n\n")
                .append(row.content.trim()).append("\n\n---\n\n")
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "《$bookTitle》AI 笔记")
            // 分享走 Binder extra，超限会抛 TransactionTooLargeException 直接崩掉；
            // 这里截断并明确告知，而不是让用户看到一次崩溃。
            val full = md.toString()
            val text = if (full.length <= SHARE_TEXT_LIMIT) {
                full
            } else {
                android.widget.Toast.makeText(act, "笔记内容较多，分享文本已截断", android.widget.Toast.LENGTH_LONG).show()
                full.take(SHARE_TEXT_LIMIT) + "\n\n…（内容过长，已截断）"
            }
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        act.startActivity(android.content.Intent.createChooser(intent, "分享 AI 笔记"))
    }

    private companion object {
        /** Binder 事务上限约 1MB，留足余量。 */
        const val SHARE_TEXT_LIMIT = 200_000

        /** 流式进度浮层里回显的尾部字符数 */
        const val PROGRESS_TAIL_CHARS = 80

        /** 与 DocumentAiService 引用格式一致：[CHAPTER:2] / [PAGE:12] / [PARAGRAPH:40] 等。 */
        val CITATION = Regex("\\[(PAGE|SLIDE|CHAPTER|PARAGRAPH|IMAGE):(\\d+)]")
    }
}
