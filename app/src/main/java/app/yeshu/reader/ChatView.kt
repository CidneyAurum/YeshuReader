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
        private const val MAX_HISTORY = 12          // 送入模型的轮数上限
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
            setOnClickListener { send() }
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

    /** 从 notes 表重建最近聊天（kind=chat，content 前缀 U:/A: 区分角色） */
    private fun loadHistory() {
        // DAO returns newest first. Keep the newest window, then replay oldest -> newest.
        val rows = db.listNotes(bookId, "chat").take(MAX_HISTORY).reversed()
        for (r in rows) {
            when {
                r.content.startsWith("U:") -> { history.add("user" to r.content.removePrefix("U:")); bubble("user", r.content.removePrefix("U:")) }
                r.content.startsWith("A:") -> { history.add("assistant" to r.content.removePrefix("A:")); bubble("assistant", r.content.removePrefix("A:")) }
            }
        }
        scrollToBottom()
    }

    private fun send() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || busy) return
        etInput.setText("")
        history.add("user" to text)
        bubble("user", text)
        db.addNote(bookId, "chat", "U:$text")
        scrollToBottom()

        busy = true
        btnSend.isEnabled = false
        btnSend.alpha = 0.5f
        // 思考占位气泡
        val thinking = bubble("assistant", "…")
        scrollToBottom()

        // 可取消请求：视图分离（离开页面/销毁 Activity）时中断网络 I/O 且不落库
        val token = AiClient.CancelToken().also { chatToken = it }
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
                            history = history.takeLast(MAX_HISTORY),
                            timeoutMs = 120_000
                        )
                    }
                } catch (e: Exception) {
                    if (!token.isCancelled()) err = AiClient.userFacingError(e)
                }
            }
            val cancelled = token.isCancelled()
            act.runOnUiThread {
                if (chatToken === token) chatToken = null
                // 视图已分离时不再触碰 UI，也不写入数据库
                if (!isAttachedToWindow) return@runOnUiThread
                busy = false
                btnSend.isEnabled = true
                btnSend.alpha = 1f
                when {
                    cancelled -> {
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

    /** system：书名 + 当前章上下文 + 行为约束 */
    private fun buildSystem(): String {
        val book = db.getBook(bookId)
        val ctx = chapterContext.take(2800)
        return "你是《${book?.title ?: "本书"}》的阅读伴侣。以下是读者正在阅读的章节内容：\n\n$ctx\n\n" +
            "要求：基于章节内容回答读者的提问；读者问到章节之外时坦诚说明并给出合理推测；" +
            "语气友好自然，回答简洁有信息量；用中文回复。"
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
