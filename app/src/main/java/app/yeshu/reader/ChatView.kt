package app.yeshu.reader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.yeshu.reader.ai.AiProfileStore

/**
 * 与书聊天：带全书上下文的多轮对话伴侣。
 * 历史（最近12条）从 notes(kind=chat) 重建，新消息继续落库。
 */
class ChatView(
    private val act: Activity,
    private val bookId: Long,
    /** 当前章纯文本（供 system 上下文，截断到 ~3000 字） */
    private val chapterContext: String
) : FrameLayout(act) {

    private val db = Db(act)
    // 跟随主题偏好，避免与 Compose 页面之间明暗跳变
    private val pal by lazy { LegacyPalette.of(act) }
    private val history = mutableListOf<Pair<String, String>>()  // role to content
    private lateinit var listBox: LinearLayout
    private lateinit var sc: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private lateinit var modelChip: TextView
    private var busy = false
    private var chatToken: AiClient.CancelToken? = null
    private val scrollToBottomAction = Runnable {
        if (::sc.isInitialized) sc.fullScroll(ScrollView.FOCUS_DOWN)
    }

    companion object {
        // 送入模型的字符预算：按字符而非条数截断，长回答不会把更早的轮次挤出上下文
        private const val MAX_HISTORY_CHARS = 6000
        private val ACCENT = Color.parseColor("#0A84FF")  // iOS 系统蓝
    }

    init {
        setBackgroundColor(pal.bg)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(-1, -1))

        val d = density(act)
        // 顶栏
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(16, d), Glass.dp(14, d), Glass.dp(16, d), Glass.dp(10, d))
            setBackgroundColor(pal.bar)
        }
        top.addView(FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            // 触控目标 ≥48dp；自绘图标无自身语义，标签挂在容器上
            layoutParams = LinearLayout.LayoutParams(Glass.dp(48, d), Glass.dp(48, d))
            contentDescription = "返回阅读"
            addView(IconView(act, "back", 22, pal.icon).apply {
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).openReader(bookId) }
        })
        top.addView(TextView(act).apply {
            text = "与书聊聊"
            textSize = 17f
            setTextColor(pal.textP)
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginStart = Glass.dp(12, d)
            layoutParams = lp
        })
        modelChip = TextView(act).apply {
            textSize = 10f
            setTextColor(if (pal.dark) Color.parseColor("#B8C6FF") else Color.parseColor("#3B3F9E"))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = Glass.dp(132, d)
            setPadding(Glass.dp(10, d), Glass.dp(7, d), Glass.dp(10, d), Glass.dp(7, d))
            background = Glass.pillBg(Color.argb(48, 91, 95, 245))
            contentDescription = "选择 AI 调用配置"
            setOnClickListener { showModelProfilePicker() }
        }
        top.addView(modelChip, LinearLayout.LayoutParams(-2, -2))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))
        refreshModelChip()

        // 气泡列表
        sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        listBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(14, d), Glass.dp(12, d), Glass.dp(14, d), Glass.dp(10, d))
        }
        sc.addView(listBox, LayoutParams(-1, -2))

        // 输入栏
        val inputBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(12, d), Glass.dp(8, d), Glass.dp(12, d), Glass.dp(8, d))
            setBackgroundColor(pal.bar)
        }
        etInput = EditText(act).apply {
            hint = "问点什么…"
            textSize = 15f
            setTextColor(pal.textP)
            setHintTextColor(pal.textT)
            background = Glass.pillBg(if (pal.dark) Color.argb(50, 255, 255, 255) else Color.argb(26, 23, 26, 43))
            setPadding(Glass.dp(16, d), Glass.dp(11, d), Glass.dp(16, d), Glass.dp(11, d))
            maxLines = 4
        }
        val etLp = LinearLayout.LayoutParams(0, -2, 1f)
        inputBar.addView(etInput, etLp)
        btnSend = TextView(act).apply {
            text = "发送"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "发送消息"
            background = Glass.pillBg(ACCENT)
            val lp = LinearLayout.LayoutParams(Glass.dp(64, d), Glass.dp(48, d))
            lp.marginStart = Glass.dp(8, d)
            layoutParams = lp
            // 生成中同一个键变成「停止」：点按中断网络 I/O，不用等 120s 超时
            setOnClickListener { if (busy) cancelGeneration() else send() }
        }
        inputBar.addView(btnSend)
        col.addView(inputBar, LinearLayout.LayoutParams(-1, -2))

        etInput.setOnFocusChangeListener { _, focused ->
            if (focused) scrollToBottom()
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (etInput.hasFocus()) scrollToBottom()
        }

        loadHistory()
        if (history.isEmpty()) {
            bubble("assistant", "我是这本书的阅读伴侣 📖\n可以问我剧情、人物、难懂的段落，或者让我猜猜后续。")
        }
    }

    /** 从 notes 表重建聊天（kind=chat，content 前缀 U:/A: 区分角色） */
    private fun loadHistory() {
        // DAO 返回 newest first；反转成 oldest -> newest 后按时间重放
        val rows = db.listNotes(bookId, "chat").reversed()
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            when {
                row.content.startsWith("U:") -> {
                    // 没有紧邻回答的提问是中断残留（取消/失败）：跳过，不重放给模型也不上屏
                    if (rows.getOrNull(i + 1)?.content?.startsWith("A:") != true) { i++; continue }
                    val text = row.content.removePrefix("U:")
                    history.add("user" to text)
                    bubble("user", text)
                }
                row.content.startsWith("A:") -> {
                    val text = row.content.removePrefix("A:")
                    history.add("assistant" to text)
                    bubble("assistant", text)
                }
            }
            i++
        }
        scrollToBottom()
    }

    /**
     * 按字符预算截取最近若干轮：从最新往回取，直到超出 [MAX_HISTORY_CHARS]。
     * 只按条数截断会让一条长回答挤掉好几轮更早的对话。
     */
    private fun budgetedHistory(): List<Pair<String, String>> {
        val picked = ArrayDeque<Pair<String, String>>()
        var used = 0
        for (index in history.indices.reversed()) {
            val turn = history[index]
            val cost = turn.second.length + 8
            if (picked.isNotEmpty() && used + cost > MAX_HISTORY_CHARS) break
            picked.addFirst(turn)
            used += cost
        }
        return picked.toList()
    }

    /** 生成中把发送键切成停止键；点按立即中断网络 I/O */
    private fun cancelGeneration() {
        val token = chatToken ?: return
        token.cancel()
        // 先给即时反馈；真正的收尾由请求线程回主线程完成
        btnSend.text = "停止中…"
        btnSend.isEnabled = false
        btnSend.contentDescription = "正在停止生成"
    }

    /** 发送/停止两态：切换文案与可点状态，避免生成中只剩一个禁用按钮 */
    private fun setSendButton(generating: Boolean) {
        busy = generating
        btnSend.text = if (generating) "停止" else "发送"
        btnSend.contentDescription = if (generating) "停止生成" else "发送消息"
        btnSend.isEnabled = true
        btnSend.alpha = 1f
    }

    private fun send() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || busy) return
        etInput.setText("")
        history.add("user" to text)
        bubble("user", text)
        // 记下 pending 行 id：请求被取消时要回滚，避免留下没有回答的提问
        val pendingId = db.addNote(bookId, "chat", "U:$text")
        scrollToBottom()

        setSendButton(true)
        // 思考占位气泡：流式增量会持续替换它的内容
        val thinking = bubble("assistant", "…")
        scrollToBottom()

        // 可取消请求：视图分离（离开页面/销毁 Activity）或点按停止时中断网络 I/O 且不落库
        val token = AiClient.CancelToken().also { chatToken = it }
        val streamed = StringBuilder()
        Thread({
            val cfg = AiClient.config(db)
            val ready = AiClient.isReady(cfg)
            var err: String? = null
            var reply: String? = null
            if (ready) {
                try {
                    reply = AiClient.withCancellation(token) {
                        AiClient.chatHistory(
                            cfg,
                            system = buildSystem(),
                            history = budgetedHistory(),
                            onDelta = { delta ->
                                streamed.append(delta)
                                act.runOnUiThread {
                                    // 已取消/已换请求/已分离：增量不再上屏
                                    if (token.isCancelled() || chatToken !== token || !isAttachedToWindow) {
                                        return@runOnUiThread
                                    }
                                    thinking.text = streamed.toString()
                                    scrollToBottom()
                                }
                            },
                            onRestart = {
                                // 断流重发：作废已上屏的增量，避免「半截 + 全文」重复
                                streamed.setLength(0)
                                act.runOnUiThread {
                                    if (chatToken === token && isAttachedToWindow) thinking.text = "…"
                                }
                            },
                            timeoutMs = 120_000
                        )
                    }
                } catch (e: Exception) {
                    if (!token.isCancelled()) err = AiClient.userFacingError(e)
                }
            }
            val cancelled = token.isCancelled()
            // 取消的提问不留在库里：否则下次进入会被当成没有回答的一轮重放
            if (cancelled) runCatching { db.deleteNote(pendingId) }
            act.runOnUiThread {
                if (chatToken === token) chatToken = null
                // 视图已分离时不再触碰 UI，也不写入数据库
                if (!isAttachedToWindow) return@runOnUiThread
                setSendButton(false)
                when {
                    cancelled -> {
                        // 同时回滚内存历史里的这轮提问，避免下次又把它当上下文发出去
                        val last = history.lastOrNull()
                        if (last?.first == "user" && last.second == text) history.removeAt(history.lastIndex)
                        thinking.text = "（已取消，未保存）"
                        thinking.alpha = 0.6f
                    }
                    !ready -> {
                        // 配置提示只是临时 UI 文案，不能当作模型回复落库/回放给模型
                        thinking.text = "（未配置 AI 服务——去设置页填接口地址和 Key 后再来聊）"
                        thinking.alpha = 0.6f
                    }
                    err != null -> {
                        thinking.text = "出错了：$err"
                        thinking.alpha = 0.6f
                    }
                    else -> {
                        val value = reply.orEmpty().trim()
                        thinking.text = value
                        thinking.alpha = 1f
                        history.add("assistant" to value)
                        db.addNote(bookId, "chat", "A:$value")
                    }
                }
                scrollToBottom()
            }
        }, "yeshu-chat").start()
    }

    override fun onDetachedFromWindow() {
        // 离开页面即取消进行中的请求，避免写入半截回复或触碰已分离的视图
        chatToken?.cancel()
        chatToken = null
        super.onDetachedFromWindow()
    }

    /** system：书名 + 当前章上下文 + 行为约束（输出语言跟随书籍正文，而不是固定中文） */
    private fun buildSystem(): String {
        val book = db.getBook(bookId)
        val ctx = chapterContext.take(2800)
        val hint = languageHint(ctx.ifBlank { book?.title.orEmpty() })
        return "你是《${book?.title ?: "本书"}》的阅读伴侣。以下是读者正在阅读的章节内容：\n\n$ctx\n\n" +
            "要求：基于章节内容回答读者的提问；读者问到章节之外时坦诚说明并给出合理推测；" +
            "语气友好自然，回答简洁有信息量；输出语言：与书籍主要语言一致" +
            (hint?.let { "（本书正文为$it）" } ?: "") + "。"
    }

    /**
     * 书籍主要语言的弱判断。DocumentAiService.detectLanguage 尚未落地，
     * 这里只按正文字符脚本兜底，避免英文/日文书拿到中文回答。
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

    /** 气泡：user 右对齐蓝色，assistant 左对齐深灰卡。返回内部 TextView 供后续更新。 */
    private fun bubble(role: String, text: String): TextView {
        val d = density(act)
        val row = FrameLayout(act)
        val tv = TextView(act).apply {
            setText(text)
            textSize = 15f
            setLineSpacing(Glass.dp(3, d).toFloat(), 1f)
            setTextColor(if (role == "user") Color.WHITE else pal.bubbleText)
            background = if (role == "user") {
                GradientDrawable().apply {
                    cornerRadius = Glass.dp(18, d).toFloat()
                    setColor(ACCENT)
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = Glass.dp(18, d).toFloat()
                    setColor(pal.bubble)
                }
            }
            setPadding(Glass.dp(14, d), Glass.dp(10, d), Glass.dp(14, d), Glass.dp(10, d))
            movementMethod = android.text.method.LinkMovementMethod()
        }
        val wrap = FrameLayout(act).apply {
            val lp = FrameLayout.LayoutParams(-2, -2)
            if (role == "user") lp.gravity = Gravity.END else lp.gravity = Gravity.START
            lp.marginStart = Glass.dp(if (role == "user") 52 else 0, d)
            lp.marginEnd = Glass.dp(if (role == "user") 0 else 52, d)
            addView(tv, lp)
        }
        row.addView(wrap, FrameLayout.LayoutParams(-1, -2))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = Glass.dp(8, d)
        listBox.addView(row, lp)
        return tv
    }

    private fun scrollToBottom() {
        sc.removeCallbacks(scrollToBottomAction)
        sc.post(scrollToBottomAction)
    }

    /** Quick switch for the active profile used by the very next chat request. */
    private fun showModelProfilePicker() {
        val profiles = AiProfileStore.list(db)
        val active = AiProfileStore.active(db)
        val labels = profiles.map { profile ->
            "${profile.name}  ·  ${profile.textModel.ifBlank { "未填写模型" }}"
        }.toTypedArray()
        val selected = profiles.indexOfFirst { it.id == active.id }
        val dlg = android.app.AlertDialog.Builder(act)
            .setTitle("选择本次调用配置")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                dialog.dismiss()
                val target = profiles[which]
                if (target.id == active.id) return@setSingleChoiceItems
                // 中途换供应商会把同一段对话历史发给另一家，先确认一次
                confirmProfileSwitch(target.id, labels[which])
            }
            .setNeutralButton("管理模型") { _, _ -> (act as MainActivity).showSettings() }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        Glass.styleDialog(dlg, density(act))
    }

    /** 切换生效前的确认：说明后续对话（含历史）会发往新的服务方 */
    private fun confirmProfileSwitch(profileId: String, label: String) {
        val dlg = android.app.AlertDialog.Builder(act)
            .setTitle("切换 AI 配置？")
            .setMessage("之后的对话（包含已有历史）将发送给 $label")
            .setPositiveButton("切换") { _, _ ->
                AiProfileStore.setActive(db, profileId)
                refreshModelChip()
                Toast.makeText(act, "已切换到 $label", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        Glass.styleDialog(dlg, density(act))
    }

    private fun refreshModelChip() {
        if (!::modelChip.isInitialized) return
        val active = AiProfileStore.active(db)
        modelChip.text = "${active.textModel.ifBlank { "选择模型" }}  ▾"
    }
}
