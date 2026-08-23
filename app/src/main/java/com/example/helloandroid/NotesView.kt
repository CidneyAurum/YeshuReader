package com.example.helloandroid

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 笔记页：某本书的全部 AI 产物（摘要/问答/自测题），点击查看、长按删除 */
class NotesView(private val act: Activity, private val bookId: Long) : FrameLayout(act) {

    private val db = Db(act)
    private lateinit var listBox: LinearLayout
    private var filterKind: String? = null   // null=全部
    private var chipRow: LinearLayout? = null

    companion object {
        private val KIND_LABEL = mapOf(
            "summary" to "摘要", "ask" to "问答", "quiz" to "自测",
            "chat" to "聊天", "quote" to "金句", "digest" to "精读", "report" to "报告"
        )
    }

    init {
        setBackgroundColor(Color.parseColor("#22334A"))
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
            layoutParams = LinearLayout.LayoutParams(Glass.dp(42, d), Glass.dp(42, d))
            addView(IconView(act, "back", 22), LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).openReader(bookId) }
        })
        val book = db.getBook(bookId)
        top.addView(TextView(act).apply {
            text = "笔记 · ${book?.title ?: ""}"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // ✨ AI 整理：把本书散乱笔记归纳成一页精读笔记
        top.addView(TextView(act).apply {
            text = "✨"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = Glass.pillBg(Color.argb(60, 255, 255, 255))
            setPadding(Glass.dp(13, d), Glass.dp(7, d), Glass.dp(13, d), Glass.dp(7, d))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Glass.dp(8, d)
            layoutParams = lp
            setOnClickListener { aiDigest() }
        })
        // 导出全部笔记（Markdown → 系统分享面板）
        top.addView(TextView(act).apply {
            text = "导出"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = Glass.pillBg(Color.argb(60, 255, 255, 255))
            setPadding(Glass.dp(14, d), Glass.dp(7, d), Glass.dp(14, d), Glass.dp(7, d))
            setOnClickListener { exportAll(db.getBook(bookId)?.title ?: "笔记") }
        })
        col.addView(top, LayoutParams(-1, -2))

        // 分类过滤 chips：全部 / 摘要 / 问答 / 自测（d 已在外层 init 定义）
        val chips = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Glass.dp(16, d), Glass.dp(6, d), Glass.dp(16, d), Glass.dp(2, d))
        }
        fun buildChips() {
            chips.removeAllViews()
            val kinds: List<Pair<String, String?>> = listOf(
                "全部" to null, "摘要" to "summary", "问答" to "ask", "自测" to "quiz",
                "聊天" to "chat", "金句" to "quote", "精读" to "digest", "报告" to "report"
            )
            kinds.forEach { (label, kind) ->
                val active = filterKind == kind
                val c = TextView(act).apply {
                    text = label
                    textSize = 12f
                    setTextColor(if (active) Color.WHITE else Color.argb(200, 255, 255, 255))
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = Glass.pillBg(if (active) Color.argb(140, 90, 130, 220) else Color.argb(45, 255, 255, 255))
                    setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(6, d))
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
        col.addView(chips, LayoutParams(-1, -2))

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
            val label = KIND_LABEL[row.kind] ?: row.kind
            val body = when {
                row.kind == "chat" && row.content.startsWith("U:") -> "🙋 " + row.content.removePrefix("U:")
                row.kind == "chat" && row.content.startsWith("A:") -> "🤖 " + row.content.removePrefix("A:")
                else -> row.content
            }
            val card = TextView(act).apply {
                this.text = "[$label] ${body.take(120)}${if (body.length > 120) "…" else ""}"
                textSize = 13f
                setTextColor(Color.parseColor("#222222"))
                background = Glass.card()
                setPadding(Glass.dp(16, d), Glass.dp(12, d), Glass.dp(16, d), Glass.dp(12, d))
                setOnClickListener { showFull(row.content) }
                setOnLongClickListener {
                    AlertDialog.Builder(act)
                        .setTitle("删除这条笔记？")
                        .setPositiveButton("删除") { _, _ -> db.deleteNote(row.id); refresh() }
                        .setNegativeButton("取消", null)
                        .show().also { Glass.styleDialog(it, density(act)) }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Glass.dp(12, d)
            listBox.addView(card, lp)
        }
    }

    private fun showFull(content: String) {
        val d = density(act)
        val sc = ScrollView(act)
        sc.addView(TextView(act).apply {
            text = content
            textSize = 14f
            setTextColor(Color.parseColor("#222222"))
            setTextIsSelectable(true)
            setPadding(Glass.dp(20, d), Glass.dp(14, d), Glass.dp(20, d), Glass.dp(20, d))
        })
        AlertDialog.Builder(act)
            .setView(sc)
            .setPositiveButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
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
        val pd = android.app.ProgressDialog.show(act, "AI 整理", "正在归纳 ${notes.size} 条笔记…", true, false)
        Thread {
            var err: String? = null
            var reply = ""
            try {
                val body = notes.joinToString("\n\n") { row ->
                    "【${KIND_LABEL[row.kind] ?: "笔记"}】\n${row.content.take(1500)}"
                }
                reply = AiClient.chat(cfg,
                    "你是专业的读书教练。用简体中文，输出结构化 Markdown 风格的纯文本。",
                    "以下是读者读《${book?.title ?: "一本书"}》期间积累的全部 AI 笔记。请归纳整理成一页「精读笔记」：" +
                        "① 核心主题一句话；② 3-5 个关键要点（合并重复内容）；③ 值得记住的金句摘录（如有）；④ 一条行动建议。" +
                        "只输出整理结果。\n\n【笔记开始】\n${body.take(22000)}\n【笔记结束】")
                db.addNote(bookId, "digest", reply)
            } catch (t: Throwable) { err = t.message ?: t.toString() }
            val e = err
            act.runOnUiThread {
                try { pd.dismiss() } catch (ex: Exception) {}
                if (e != null) {
                    AlertDialog.Builder(act).setTitle("AI 调用失败").setMessage(e)
                        .setPositiveButton("关闭", null).show().also { Glass.styleDialog(it, density(act)) }
                } else {
                    filterKind = "digest"
                    refresh()
                    showFull(reply)
                }
            }
        }.start()
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
            md.append("## ").append(KIND_LABEL[row.kind] ?: "笔记").append("\n\n")
                .append(row.content.trim()).append("\n\n---\n\n")
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "《$bookTitle》AI 笔记")
            putExtra(android.content.Intent.EXTRA_TEXT, md.toString())
        }
        act.startActivity(android.content.Intent.createChooser(intent, "分享 AI 笔记"))
    }
}
