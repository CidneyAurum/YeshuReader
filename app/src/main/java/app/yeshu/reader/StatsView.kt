package app.yeshu.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 阅读统计页：总览 + 近7日柱状图 + 时长 Top 榜 + AI 阅读报告 */
class StatsView(private val act: Activity) : FrameLayout(act) {

    private val db = Db(act)
    private lateinit var listBox: LinearLayout
    private var aiProgress: TextView? = null
    private var reportToken: AiClient.CancelToken? = null
    // 旧版 View 页面也需要跟随主题偏好，避免与 Compose 页面明暗跳变
    private val pal by lazy { LegacyPalette.of(act) }

    init {
        setBackgroundColor(pal.bg)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(-1, -1))
        applySystemBarInsets(col)

        val d = density(act)
        // 顶栏
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(T.pagePad, d), Glass.dp(18, d), Glass.dp(T.pagePad, d), Glass.dp(10, d))
        }
        top.addView(FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            layoutParams = LinearLayout.LayoutParams(Glass.dp(48, d), Glass.dp(48, d))
            contentDescription = "返回书架"
            addView(IconView(act, "back", 22, pal.icon).apply {
                // 自绘图标无自身语义，标签已在容器上，避免 TalkBack 重复/空播报
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).backToShelf() }
        })
        top.addView(TextView(act).apply {
            text = "阅读统计"
            textSize = 20f
            setTextColor(pal.textP)
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginStart = Glass.dp(14, d)
            layoutParams = lp
        })
        col.addView(top)

        val sc = ScrollView(act)
        listBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(T.pagePad, d), Glass.dp(6, d), Glass.dp(T.pagePad, d), Glass.dp(60, d))
        }
        sc.addView(listBox, LayoutParams(-1, -2))
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        refresh()
    }

    fun refresh() {
        val d = density(act)
        listBox.removeAllViews()

        // ---- 总览卡：总时长 / 坚持天数 / 笔记数 ----
        val totalMs = db.totalAllReadMs()
        val days = db.activeDays()
        val longest = db.longestDay()
        val notesN = db.noteCount()
        val booksAll = db.listBooks()
        val reading = booksAll.count { it.progress > 0.005f && it.progress < 0.99f }
        val finished = booksAll.count { it.progress >= 0.99f }

        // 一条阅读记录都没有时，画出来的是全空图表和「0 分钟」，看起来像统计坏了。
        // 直接说明还没开始读，并给一个回书架的入口。
        if (totalMs <= 0L && days == 0 && notesN == 0) {
            listBox.addView(
                Glass.emptyState(
                    act,
                    emoji = "📖",
                    title = "还没有阅读记录",
                    sub = "读上几分钟，这里会出现累计时长、近 7 天趋势和阅读时长榜。",
                    palette = pal,
                    icon = "book",
                ),
                LinearLayout.LayoutParams(-1, -2),
            )
            listBox.addView(TextView(act).apply {
                text = "去书架"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(14, d).toFloat()
                    setColor(T.accent)
                }
                foreground = Glass.pressFx()
                setOnClickListener { (act as MainActivity).backToShelf() }
            }, LinearLayout.LayoutParams(-1, Glass.dp(48, d)).also { it.topMargin = Glass.dp(14, d) })
            return
        }

        fun statCard(): LinearLayout {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(T.rCard, d).toFloat(); setColor(pal.surface)
                }
                setPadding(Glass.dp(8, d), Glass.dp(16, d), Glass.dp(8, d), Glass.dp(16, d))
            }
            val cells = listOf(
                formatMs(totalMs) to "累计阅读",
                "$days" to "坚持天数",
                // R43：read_log 只按天存总量，无法还原时段分布；「最长的一天」是同样有意义
                // 且不需要改表结构就能算出的指标。
                (longest?.let { (day, ms) -> "$day\n${ms / 60000} 分钟" } ?: "—") to "最长的一天",
                "${booksAll.size}" to "藏书",
                "$reading" to "在读"
            )
            cells.forEachIndexed { i, (v, label) ->
                row.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(TextView(act).apply {
                        text = v; textSize = if (i == 0) 15f else 19f
                        setTextColor(if (i == 0) pal.textP else T.accent)
                        setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                    })
                    addView(TextView(act).apply {
                        text = label; textSize = 11f; setTextColor(pal.textT)
                        setPadding(0, Glass.dp(4, d), 0, 0); gravity = Gravity.CENTER
                    })
                })
            }
            return row
        }
        listBox.addView(statCard(), LinearLayout.LayoutParams(-1, -2).also {
            it.topMargin = Glass.dp(4, d)
        })

        // ---- 近 7 日柱状图 ----
        val daily = db.dailyReadMs(7)
        val maxMs = daily.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        listBox.addView(sectionTitle("近 7 天"))
        val chart = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(T.rCard, d).toFloat(); setColor(pal.surface)
            }
            setPadding(Glass.dp(16, d), Glass.dp(16, d), Glass.dp(16, d), Glass.dp(12, d))
        }
        val barRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        // 同月只显示日、跨月带上月份：固定 "MM-dd" 在窄柱上读不清，只显示日又会出现 31→1 的错觉
        val dayLabels = DayLabels.labels(daily.map { it.first })
        daily.forEachIndexed { index, (day, ms) ->
            val frac = ms.toFloat() / maxMs
            barRow.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, Glass.dp(110, d), 1f)
                addView(TextView(act).apply {
                    text = if (ms > 0) "${ms / 60000}′" else ""
                    textSize = 9f; setTextColor(pal.textS); gravity = Gravity.CENTER
                })
                addView(View(act).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = Glass.dp(3, d).toFloat()
                        setColor(if (ms > 0) T.accent else pal.surface3)
                    }
                    layoutParams = LinearLayout.LayoutParams(Glass.dp(14, d),
                        ((Glass.dp(84, d)) * frac).toInt().coerceAtLeast(Glass.dp(3, d))).also {
                        it.topMargin = Glass.dp(4, d); it.gravity = Gravity.CENTER_HORIZONTAL
                    }
                })
                addView(TextView(act).apply {
                    text = dayLabels[index]; textSize = 9f; setTextColor(pal.textT)
                    setPadding(0, Glass.dp(6, d), 0, 0); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                })
            })
        }
        chart.addView(barRow, LinearLayout.LayoutParams(-1, -2))
        listBox.addView(chart, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(12, d) })

        // ---- 投入最多 ----
        val tops = db.topBooks(5)
        if (tops.isNotEmpty()) {
            listBox.addView(sectionTitle("投入最多"))
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(T.rCard, d).toFloat(); setColor(pal.surface)
                }
                setPadding(Glass.dp(16, d), Glass.dp(6, d), Glass.dp(16, d), Glass.dp(10, d))
            }
            tops.forEachIndexed { i, tb ->
                card.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Glass.dp(10, d), 0, Glass.dp(10, d))
                    addView(TextView(act).apply {
                        text = "${i + 1}"; textSize = 13f; setTextColor(pal.textT)
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(Glass.dp(22, d), -2)
                    })
                    addView(TextView(act).apply {
                        text = tb.title; textSize = 14f; setTextColor(pal.textP)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(act).apply {
                        text = formatMs(tb.ms); textSize = 12f; setTextColor(pal.textS)
                        val lp = LinearLayout.LayoutParams(-2, -2); lp.marginStart = Glass.dp(10, d)
                        layoutParams = lp
                    })
                })
            }
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(12, d) })
        }

        // ---- AI 阅读报告入口卡 ----
        val aiCard = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(T.rCard, d).toFloat()
                setColor(Color.parseColor("#12233D"))
            }
            setPadding(Glass.dp(18, d), Glass.dp(18, d), Glass.dp(18, d), Glass.dp(18, d))
            addView(TextView(act).apply {
                text = "AI 阅读报告"; textSize = 16f; setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(act).apply {
                text = "让 AI 根据你的阅读数据生成个性化报告，存进笔记可随时回看"
                textSize = 12f; setTextColor(Color.argb(190, 255, 255, 255))
                setPadding(0, Glass.dp(6, d), 0, 0)
            })
            addView(TextView(act).apply {
                text = if (db.listNotes(0, "report").isEmpty()) "生成报告" else "重新生成"
                textSize = 14f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(12, d).toFloat(); setColor(T.accent)
                }
                setPadding(0, Glass.dp(11, d), 0, Glass.dp(11, d))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(14, d) }
                setOnClickListener { aiReport() }
            })
        }
        listBox.addView(aiCard, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(16, d) })

        // ---- 历史报告列表（listNotes 已是 id DESC，最新的排最前，与全站一致）----
        val reports = db.listNotes(0, "report")
        reports.forEach { r ->
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(12, d).toFloat(); setColor(pal.surface)
                }
                setPadding(Glass.dp(14, d), Glass.dp(12, d), Glass.dp(14, d), Glass.dp(12, d))
                addView(TextView(act).apply {
                    text = r.content.replace("\n", " ").take(64) + "…"
                    textSize = 12f; setTextColor(pal.textS); maxLines = 2
                })
                setOnClickListener { showReport(r.content) }
                setOnLongClickListener {
                    AlertDialog.Builder(act).setTitle("删除该报告？")
                        .setPositiveButton("删除") { _, _ -> db.deleteNote(r.id); refresh() }
                        .setNegativeButton("取消", null).show().also { Glass.styleDialog(it, d) }
                    true
                }
            }
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(10, d) })
        }
    }

    private fun sectionTitle(t: String): TextView {
        val d = density(act)
        return TextView(act).apply {
            text = t; textSize = 13f; setTextColor(pal.textT)
            setTypeface(null, Typeface.BOLD); letterSpacing = 0.05f
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = Glass.dp(18, d)
            layoutParams = lp
        }
    }

    private fun formatMs(ms: Long): String = when {
        ms <= 0 -> "0 分钟"
        ms < 3600_000 -> "${ms / 60000} 分钟"
        else -> {
            val h = ms / 3600000; val m = (ms % 3600000) / 60000
            if (m == 0L) "$h 小时" else "$h 小时 $m 分"
        }
    }

    /**
     * AI 阅读报告：统计数据 → 个性化周报文案，存 notes(bookId=0, kind=report)。
     * bookId=0 是「全局笔记」哨兵值；笔记中枢 NotesHubScreen 目前按 books[note.bookId] 过滤，
     * 会把这些报告丢弃（详见交付说明中需要的 YeshuApp 改动）。
     */
    private fun aiReport() {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            AlertDialog.Builder(act).setTitle("未配置 AI")
                .setMessage("请先在书架「AI 设置」填写接口地址、Key 和模型名。")
                .setPositiveButton("去设置") { _, _ -> (act as MainActivity).showSettings() }
                .setNegativeButton("取消", null).show().also { Glass.styleDialog(it, density(act)) }
            return
        }
        val totalMs = db.totalAllReadMs()
        val days = db.activeDays()
        val tops = db.topBooks(5)
        val booksAll = db.listBooks()
        val finished = booksAll.count { it.progress >= 0.99f }
        val recent7 = db.dailyReadMs(7).joinToString { (day, ms) -> "$day=${ms / 60000}分钟" }
        val data = buildString {
            append("累计阅读 ${formatMs(totalMs)}；有记录天数 $days 天；藏书 ${booksAll.size} 本，读完 $finished 本。\n")
            append("投入最多：" + tops.joinToString { "${it.title}(${formatMs(it.ms)})" } + "\n")
            append("近7天每日：$recent7")
        }
        // 可取消请求：浮层点按或视图分离时中断网络 I/O；已生成好的报告仍会落库，不静默丢弃
        val token = AiClient.CancelToken().also { reportToken = it }
        val pill = showAiProgress("生成中… 点按停止", token)
        pill.contentDescription = "正在分析你的阅读数据，点按停止"
        Thread({
            var err: String? = null
            var reply = ""
            var stored = ""
            val streamed = StringBuilder()
            try {
                reply = AiClient.withCancellation(token) {
                    AiClient.chat(cfg,
                        "你是一位温暖幽默的私人阅读顾问。用简体中文写一份简短的个性化阅读报告。",
                        "根据以下阅读数据写一份「阅读报告」：① 一句总体评价；② 阅读习惯观察 2 条；" +
                            "③ 一个具体可行的建议（比如下次读什么、什么时段读）；④ 一句鼓励。总共 200 字以内。" +
                            "\n\n【数据】\n$data",
                        onDelta = { delta ->
                            streamed.append(delta)
                            act.runOnUiThread {
                                if (reportToken !== token || !isAttachedToWindow) return@runOnUiThread
                                pill.text = streamed.toString().trim().takeLast(PROGRESS_TAIL_CHARS)
                            }
                        },
                        onRestart = {
                            // 断流重发：作废已上屏增量，避免「半截 + 全文」重复
                            streamed.setLength(0)
                            act.runOnUiThread {
                                if (reportToken === token && isAttachedToWindow) pill.text = "生成中… 点按停止"
                            }
                        },
                        timeoutMs = 120_000)
                }
                // 一行溯源：模型名 + 生成时间（token 用量待 AiClient.probe 落地后再补）
                val model = cfg.model.ifBlank { "未知模型" }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                stored = reply.trim().trimEnd() + "\n\n—— $model · $stamp"
                // 全局报告用 bookId=0 作哨兵值；笔记中枢会放行 bookId==0，所以这里能显示出来。
                // 先落库再回主线程：视图若已分离，结果也不该丢。
                if (!token.isCancelled()) db.addNote(0, "report", stored)
            } catch (t: Throwable) {
                if (!token.isCancelled()) err = AiClient.userFacingError(t)
            }
            val e = err
            val cancelled = token.isCancelled()
            act.runOnUiThread {
                if (reportToken === token) reportToken = null
                // 视图已分离：结果已落库，不再触碰 UI
                if (!isAttachedToWindow) return@runOnUiThread
                dismissAiProgress()
                when {
                    cancelled -> Unit
                    e != null -> AlertDialog.Builder(act).setTitle("AI 调用失败").setMessage(e)
                        .setPositiveButton("关闭", null).show().also { Glass.styleDialog(it, density(act)) }
                    else -> {
                        refresh()
                        showReport(stored.ifBlank { reply })
                    }
                }
            }
        }, "yeshu-report").apply { isDaemon = true }.start()
    }

    /**
     * 不使用 ProgressDialog：它持有 Activity 窗口且无法取消；浮层随视图分离自动消失。
     * 传入 token 时浮层可点按取消，并返回 TextView 供流式增量更新。
     */
    private fun showAiProgress(message: String, token: AiClient.CancelToken? = null): TextView {
        dismissAiProgress()
        val d = density(act)
        val tv = TextView(act).apply {
            text = message
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

    override fun onDetachedFromWindow() {
        // 离开页面即取消进行中的 AI 报告，避免继续占用连接、也不写半截结果
        reportToken?.cancel()
        reportToken = null
        dismissAiProgress()
        super.onDetachedFromWindow()
    }

    private fun showReport(body: String) {
        val d = density(act)
        val sc = ScrollView(act)
        sc.addView(TextView(act).apply {
            text = body
            textSize = 15f
            // 对话框底色跟随主题，正文颜色也必须跟着走
            setTextColor(if (pal.dark) pal.textP else Color.parseColor("#222222"))
            setLineSpacing(Glass.dp(4, d).toFloat(), 1.1f)
            setPadding(Glass.dp(20, d), Glass.dp(16, d), Glass.dp(20, d), Glass.dp(20, d))
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(act).setTitle("AI 阅读报告").setView(sc)
            .setPositiveButton("关闭", null).show().also { Glass.styleDialog(it, density(act)) }
    }

    private companion object {
        /** 流式进度浮层里回显的尾部字符数 */
        const val PROGRESS_TAIL_CHARS = 80
    }
}
