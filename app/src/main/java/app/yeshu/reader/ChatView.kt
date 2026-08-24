package app.yeshu.reader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

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
    private val history = mutableListOf<Pair<String, String>>()  // role to content
    private lateinit var listBox: LinearLayout
    private lateinit var sc: ScrollView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private var busy = false
    private val scrollToBottomAction = Runnable {
        if (::sc.isInitialized) sc.fullScroll(ScrollView.FOCUS_DOWN)
    }

    companion object {
        private const val MAX_HISTORY = 12          // 送入模型的轮数上限
        private val ACCENT = Color.parseColor("#0A84FF")  // iOS 系统蓝
    }

    init {
        setBackgroundColor(Color.parseColor("#10141C"))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(-1, -1))
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val d = density(act)
        // 顶栏
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(16, d), Glass.dp(14, d), Glass.dp(16, d), Glass.dp(10, d))
            setBackgroundColor(Color.parseColor("#161B26"))
        }
        top.addView(FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            layoutParams = LinearLayout.LayoutParams(Glass.dp(40, d), Glass.dp(40, d))
            addView(IconView(act, "back", 20), LayoutParams(Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).openReader(bookId) }
        })
        top.addView(TextView(act).apply {
            text = "与书聊聊"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginStart = Glass.dp(12, d)
            layoutParams = lp
        })
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

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
            setBackgroundColor(Color.parseColor("#161B26"))
        }
        etInput = EditText(act).apply {
            hint = "问点什么…"
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(120, 255, 255, 255))
            background = Glass.pillBg(Color.argb(50, 255, 255, 255))
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
            background = Glass.pillBg(ACCENT)
            val lp = LinearLayout.LayoutParams(Glass.dp(64, d), Glass.dp(42, d))
            lp.marginStart = Glass.dp(8, d)
            layoutParams = lp
            setOnClickListener { send() }
        }
        inputBar.addView(btnSend)
        col.addView(inputBar, LinearLayout.LayoutParams(-1, -2))

        installKeyboardInsets(col)
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

        Thread {
            val cfg = AiClient.config(db)
            val reply = try {
                if (AiClient.isReady(cfg)) {
                    AiClient.chatHistory(
                        cfg,
                        system = buildSystem(),
                        history = history.takeLast(MAX_HISTORY),
                        timeoutMs = 120_000
                    )
                } else "（未配置 AI 服务——去设置页填接口地址和 Key 后再来聊）"
            } catch (e: Exception) {
                "出错了：${AiClient.userFacingError(e)}"
            }
            act.runOnUiThread {
                busy = false
                btnSend.isEnabled = true
                btnSend.alpha = 1f
                thinking.findViewById<TextView>(R.id.bubble_text)?.text = reply.trim()
                history.add("assistant" to reply.trim())
                db.addNote(bookId, "chat", "A:${reply.trim()}")
                scrollToBottom()
            }
        }.start()
    }

    /** system：书名 + 当前章上下文 + 行为约束 */
    private fun buildSystem(): String {
        val book = db.getBook(bookId)
        val ctx = chapterContext.take(2800)
        return "你是《${book?.title ?: "本书"}》的阅读伴侣。以下是读者正在阅读的章节内容：\n\n$ctx\n\n" +
            "要求：基于章节内容回答读者的提问；读者问到章节之外时坦诚说明并给出合理推测；" +
            "语气友好自然，回答简洁有信息量；用中文回复。"
    }

    /** 气泡：user 右对齐蓝色，assistant 左对齐深灰卡 */
    private fun bubble(role: String, text: String): FrameLayout {
        val d = density(act)
        val row = FrameLayout(act)
        val tv = TextView(act).apply {
            id = R.id.bubble_text
            setText(text)
            textSize = 15f
            setLineSpacing(Glass.dp(3, d).toFloat(), 1f)
            setTextColor(if (role == "user") Color.WHITE else Color.parseColor("#E8EAEE"))
            background = if (role == "user") {
                GradientDrawable().apply {
                    cornerRadius = Glass.dp(18, d).toFloat()
                    setColor(ACCENT)
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = Glass.dp(18, d).toFloat()
                    setColor(Color.argb(150, 34, 38, 48))
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
        return row
    }

    private fun scrollToBottom() {
        sc.removeCallbacks(scrollToBottomAction)
        sc.post(scrollToBottomAction)
    }

    /** Edge-to-edge 下显式使用 IME inset，避免键盘覆盖输入栏和最后一条消息。 */
    private fun installKeyboardInsets(content: LinearLayout) {
        var lastImeBottom = -1
        ViewCompat.setOnApplyWindowInsetsListener(this) { root, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.navigationBars()
            )
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            val bottomInset = max(navigation.bottom, ime.bottom)
            val params = content.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != bottomInset) {
                params.bottomMargin = bottomInset
                content.layoutParams = params
            }
            if (ime.bottom != lastImeBottom) {
                lastImeBottom = ime.bottom
                if (ime.bottom > 0) scrollToBottom()
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (insets.isVisible(WindowInsetsCompat.Type.ime())) scrollToBottom()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (etInput.hasFocus()) scrollToBottom()
                }
            }
        )
        ViewCompat.requestApplyInsets(this)
    }
}
