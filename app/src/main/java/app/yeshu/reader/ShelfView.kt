package app.yeshu.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.yeshu.reader.parse.DocParser
import app.yeshu.reader.preferences.UserPreferences
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ShelfView(private val act: Activity) : FrameLayout(act) {

    private val db = Db(act)
    // 旧版 View 页面跟随主题偏好，避免与 Compose 页面之间明暗跳变
    private val pal by lazy { LegacyPalette.of(act) }
    private lateinit var listBox: LinearLayout
    private var bgHost: FrameLayout? = null
    private var bgImage: ImageView? = null
    private var aiProgress: TextView? = null
    private var aiBusy = false
    // 进行中的 AI 请求：浮层点按取消与视图分离取消共用同一个 token
    private var aiToken: AiClient.CancelToken? = null
    private lateinit var etSearch: android.widget.EditText
    private lateinit var statTv: TextView
    private lateinit var sortBtn: TextView
    private lateinit var segWrap: FrameLayout
    private lateinit var segList: TextView
    private lateinit var segGrid: TextView
    private var segStyleFn: (() -> Unit)? = null
    private var filterHost: android.widget.HorizontalScrollView? = null   // 控制可见性的外层
    private var filterRow: LinearLayout? = null                           // 装 chips 的内层

    // 当前所在文件夹（0 = 根目录）
    private var curFolder = 0L
    private var multiMode = false
    private val selected = mutableSetOf<Long>()
    private var multiBar: LinearLayout? = null
    private var multiCountTv: TextView? = null
    private var pendingCoverBookId = -1L
    // 搜索输入防抖：避免每个字符都触发一次整树重建
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchDebounce: Runnable? = null
    // 当前显示的底部弹层：用于返回键先关弹层，以及页面销毁时清理浮层
    private var activeSheet: BottomSheet? = null
    private var sheetBackCallback: OnBackPressedCallback? = null
    // 待写入文件的笔记导出内容（超过 Binder 事务上限时改走 ACTION_CREATE_DOCUMENT）
    private var pendingExportMarkdown: String? = null

    init {
        val d = density(act)

        // 背景：近黑纯色 + 极微弱深蓝渐变（自定义 bg.img 存在时仍尊重用户选择）
        val root = FrameLayout(act).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(pal.bgGradientTop, pal.bg, pal.bg)
            )
        }
        bgHost = root
        if (File(act.filesDir, "bg.img").exists()) loadBg()

        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LayoutParams(-1, -1))
        addView(root, LayoutParams(-1, -1))
        applySystemBarInsets(content)

        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(T.pagePad, d), Glass.dp(22, d), Glass.dp(T.pagePad, d), Glass.dp(10, d))
        }
        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(act).apply {
            text = "页枢"
            textSize = 30f
            setTextColor(pal.textP)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        titleCol.addView(TextView(act).apply {
            text = "YESHU READER"
            textSize = 8.5f
            setTextColor(pal.textT)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.24f
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = Glass.dp(3, d)
            layoutParams = lp
        })
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        fun topBtn(icon: String, filled: Boolean, desc: String, onClick: () -> Unit): FrameLayout = FrameLayout(act).apply {
            background = if (filled) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(T.accent)
            } else Glass.iconBg()
            foreground = Glass.pressFx()
            // 触控目标 48dp（图标视觉尺寸保持 22dp）
            val lp = LinearLayout.LayoutParams(Glass.dp(48, d), Glass.dp(48, d))
            lp.marginStart = Glass.dp(12, d)
            layoutParams = lp
            contentDescription = desc
            addView(
                IconView(act, icon, 21, if (filled) Color.WHITE else pal.icon),
                FrameLayout.LayoutParams(Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER)
            )
            setOnClickListener { onClick() }
        }
        // 主操作：添加（导入/新建 bottom sheet）；次操作：更多（文件夹/批量/背景/设置）
        top.addView(topBtn("plus", true, "添加") { showAddSheet() })
        top.addView(topBtn("more", false, "更多") { showMoreSheet() })
        content.addView(top)

        // ---- 搜索框：48dp、#1C1C1E 实底、圆角16、内置放大镜与清除/取消 ----
        val searchWrap = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(T.rCard, d).toFloat()
                setColor(pal.surface)
            }
            setPadding(Glass.dp(14, d), 0, Glass.dp(6, d), 0)
        }
        val swp = LinearLayout.LayoutParams(-1, Glass.dp(48, d))
        swp.setMargins(Glass.dp(T.pagePad, d), Glass.dp(10, d), Glass.dp(T.pagePad, d), 0)
        searchWrap.layoutParams = swp
        searchWrap.addView(IconView(act, "search", 18, pal.icon))
        etSearch = android.widget.EditText(act).apply {
            hint = "搜索书名、作者或文件夹"
            textSize = 15f
            setTextColor(pal.textP)
            setHintTextColor(pal.textT)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(Glass.dp(10, d), 0, Glass.dp(4, d), 0)
            setSingleLine(true)
        }
        searchWrap.addView(etSearch, LinearLayout.LayoutParams(0, -2, 1f))
        val btnClear = FrameLayout(act).apply {
            visibility = View.GONE
            addView(IconView(act, "close", 14, pal.icon), FrameLayout.LayoutParams(Glass.dp(16, d), Glass.dp(16, d), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(Glass.dp(36, d), Glass.dp(36, d))
            foreground = Glass.pressFx()
            setOnClickListener {
                etSearch.setText("")
                visibility = View.GONE
            }
        }
        searchWrap.addView(btnClear)
        val btnCancel = TextView(act).apply {
            text = "取消"
            textSize = 15f
            setTextColor(T.accent)
            visibility = View.GONE
            setPadding(Glass.dp(8, d), Glass.dp(8, d), Glass.dp(6, d), Glass.dp(8, d))
            setOnClickListener {
                etSearch.setText("")
                visibility = View.GONE
                btnClear.visibility = View.GONE
                etSearch.clearFocus()
            }
        }
        searchWrap.addView(btnCancel)
        etSearch.setOnFocusChangeListener { _, hasFocus ->
            btnCancel.visibility = if (hasFocus) View.VISIBLE else View.GONE
            if (!hasFocus && etSearch.text.isNullOrBlank()) btnClear.visibility = View.GONE
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                btnClear.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                scheduleSearchRefresh()
            }
        })
        content.addView(searchWrap)

        // ---- 统计行 + 排序按钮 + segmented control ----
        val metaRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(T.pagePad, d), Glass.dp(12, d), Glass.dp(T.pagePad, d), Glass.dp(2, d))
        }
        statTv = TextView(act).apply {
            textSize = 12f
            setTextColor(pal.textS)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        metaRow.addView(statTv)
        sortBtn = TextView(act).apply {
            textSize = 12f
            setTextColor(pal.textS)
            background = Glass.pillBg(Color.argb(36, 255, 255, 255))
            setPadding(Glass.dp(10, d), Glass.dp(5, d), Glass.dp(10, d), Glass.dp(5, d))
            setOnClickListener {
                val cur = db.getSetting("shelf_sort") ?: "recent"
                db.setSetting("shelf_sort", ShelfSort.next(cur))
                refresh()
            }
        }
        metaRow.addView(sortBtn)
        content.addView(metaRow)

        // segmented control：列表 | 宫格（双状态选中态）
        segWrap = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(12, d).toFloat()
                setColor(pal.surface2)
            }
        }
        val segInner = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun segSeg(label: String, marginEnd: Int): TextView = TextView(act).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).also {
                it.setMargins(Glass.dp(3, d), Glass.dp(3, d), Glass.dp(marginEnd, d), Glass.dp(3, d))
            }
        }
        segList = segSeg("列表", 0).also { segInner.addView(it) }
        segGrid = segSeg("宫格", 0).also { segInner.addView(it) }
        fun styleSeg() {
            val gridOn = (db.getSetting("shelf_view") ?: "list") == "grid"
            for ((tv, on) in listOf(segList to !gridOn, segGrid to gridOn)) {
                tv.setTextColor(if (on) pal.textP else pal.textS)
                tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (on) GradientDrawable().apply {
                    cornerRadius = Glass.dp(9, d).toFloat()
                    setColor(pal.surface3)
                } else null
            }
        }
        styleSeg()
        segStyleFn = ::styleSeg
        segList.setOnClickListener {
            if ((db.getSetting("shelf_view") ?: "list") != "list") {
                db.setSetting("shelf_view", "list"); styleSeg(); refresh()
            }
        }
        segGrid.setOnClickListener {
            if ((db.getSetting("shelf_view") ?: "list") != "grid") {
                db.setSetting("shelf_view", "grid"); styleSeg(); refresh()
            }
        }
        segWrap.addView(segInner, FrameLayout.LayoutParams(-1, Glass.dp(38, d)))
        val segLp = LinearLayout.LayoutParams(-1, -2)
        segLp.setMargins(Glass.dp(T.pagePad, d), Glass.dp(10, d), Glass.dp(T.pagePad, d), 0)
        content.addView(segWrap, segLp)

        // 筛选 chips 行：全部 / 收藏 / 在读 / 未读 / 读完
        val fRow = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL })
            visibility = View.GONE
        }
        filterHost = fRow
        filterRow = fRow.getChildAt(0) as LinearLayout
        val fLp = LinearLayout.LayoutParams(-1, -2)
        fLp.setMargins(Glass.dp(T.pagePad, d), Glass.dp(10, d), 0, 0)
        content.addView(fRow, fLp)

        // ---- 内容列表 ----
        listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        listBox.setPadding(Glass.dp(T.pagePad, d), Glass.dp(4, d), Glass.dp(T.pagePad, d), Glass.dp(120, d))
        val sc = ScrollView(act)
        sc.addView(listBox, LayoutParams(-1, -2))
        content.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        // 批量模式底部浮条：N 选 · 全选 · 移动 · 删除 · 完成
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.darkCard()
            elevation = Glass.dp(10, d).toFloat()
            setPadding(Glass.dp(18, d), Glass.dp(12, d), Glass.dp(12, d), Glass.dp(12, d))
            visibility = View.GONE
        }
        fun barBtn(label: String, onClick: () -> Unit): TextView = TextView(act).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Glass.pillBg(Color.argb(70, 255, 255, 255))
            setPadding(Glass.dp(16, d), Glass.dp(8, d), Glass.dp(16, d), Glass.dp(8, d))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginStart = Glass.dp(8, d)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
        val count = TextView(act).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        multiCountTv = count
        bar.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(barBtn("全选") { selectAllVisible() })
        bar.addView(barBtn("移动") { batchMove() })
        bar.addView(barBtn("删除") { batchDelete() })
        bar.addView(barBtn("完成") { toggleMultiMode() })
        val bb = LayoutParams(-1, -2, Gravity.BOTTOM)
        bb.setMargins(Glass.dp(12, d), 0, Glass.dp(12, d), Glass.dp(94, d))
        addView(bar, bb)
        multiBar = bar

        refresh()
    }

    private fun toggleMultiMode() {
        multiMode = !multiMode
        selected.clear()
        multiBar?.visibility = if (multiMode) View.VISIBLE else View.GONE
        updateMultiCount()
        refresh()
    }

    /** 添加 sheet：导入本地文件 / 新建文件夹 */
    private fun showAddSheet() {
        showSheet("添加到页枢") {
            // 走 Activity 的持久化导入通道（takePersistableUriPermission + WorkManager），
            // 旧的一次性 GET_CONTENT + 匿名 Thread 会在进程被回收时丢掉导入。
            shelfItem("book", "导入本地文件") { (act as MainActivity).importDocuments() }
            shelfItem("folder", "新建分类") { newFolderDialog() }
        }
    }

    /**
     * 更多 sheet：按「书架管理 / AI 与数据 / 外观」分组，每项带一行说明。
     *
     * 入口名称与工作台、阅读器里的提示完全一致（都是「AI 设置」），
     * 用户是照着提示来找入口的，命名不一致会让人以为功能不存在。
     */
    private fun showMoreSheet() {
        val cfg = AiClient.config(db)
        val aiReady = AiClient.isReady(cfg)
        val trash = db.listDeletedBooks().size
        showSheet("更多") {
            section("书架管理", "整理、统计与批量操作")
            item("check", "批量管理", "多选删除、移动分类或加入收藏") {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                toggleMultiMode()
            }
            item("sort", "阅读统计", "总时长、近 7 天与阅读排行") {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                (act as MainActivity).showStats()
            }
            item("book", "回收站（$trash）", if (trash > 0) "有 $trash 项待恢复或彻底删除" else "当前是空的") {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                showRecycleBin()
            }

            section("AI 与数据", "由你自己提供接口，费用由服务商收取")
            item(
                "sliders",
                "AI 设置",
                if (aiReady) "接口与模型已就绪：${cfg.model}" else "填写接口地址、Key 和模型名后即可使用"
            ) {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                (act as MainActivity).showSettings()
            }
            item("bulb", "AI 推荐下一本", "根据已读内容推荐，会发送阅读记录摘要") {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                aiRecommend()
            }

            section("外观")
            item("palette", "更换背景", "选一张本地图片作为书架背景") {
                activeSheet = null
                sheetBackCallback?.isEnabled = false
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                @Suppress("DEPRECATION")
                act.startActivityForResult(i, Glass.REQ_BG)
            }
        }
    }

    /**
     * 通用 AI 任务执行：配置检查 + 视图内进度提示 + 子线程 + UI 回调。
     * 不使用 ProgressDialog：它持有 Activity 窗口且无法取消；进度浮层随本视图分离自动消失，
     * 回调前校验 isAttachedToWindow，避免向已销毁的界面写数据。
     * 请求带 CancelToken 并走流式：浮层点按即停，增量上屏让等待可见。
     */
    private fun aiTask(loading: String, promptSys: String, buildPrompt: () -> String, onDone: (String) -> Unit) {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            AlertDialog.Builder(act)
                .setTitle("未配置 AI")
                .setMessage("请先在「设置」填写接口地址、Key 和模型名。")
                .setPositiveButton("去设置") { _, _ -> (act as MainActivity).showSettings() }
                .setNegativeButton("取消", null)
                .show().also { Glass.styleDialog(it, density(act)) }
            return
        }
        if (aiBusy) return
        aiBusy = true
        val token = AiClient.CancelToken().also { aiToken = it }
        val pill = showAiProgress("生成中… 点按停止", token)
        pill.contentDescription = "$loading，点按停止"
        Thread({
            var err: String? = null
            var reply = ""
            val streamed = StringBuilder()
            try {
                reply = AiClient.withCancellation(token) {
                    AiClient.chat(
                        cfg, promptSys, buildPrompt(),
                        onDelta = { delta ->
                            streamed.append(delta)
                            act.runOnUiThread {
                                if (aiToken !== token || !isAttachedToWindow) return@runOnUiThread
                                pill.text = streamed.toString().trim().takeLast(PROGRESS_TAIL_CHARS)
                            }
                        },
                        onRestart = {
                            // 断流重发：作废已上屏增量，避免「半截 + 全文」重复
                            streamed.setLength(0)
                            act.runOnUiThread {
                                if (aiToken === token && isAttachedToWindow) pill.text = "生成中… 点按停止"
                            }
                        },
                        timeoutMs = 120_000
                    )
                }
            } catch (t: Throwable) {
                // 统一走 userFacingError：错误文案与其他 AI 入口一致且不泄漏响应正文
                if (!token.isCancelled()) err = AiClient.userFacingError(t)
            }
            val e = err
            val cancelled = token.isCancelled()
            act.runOnUiThread {
                if (aiToken === token) aiToken = null
                aiBusy = false
                if (!isAttachedToWindow) return@runOnUiThread
                dismissAiProgress()
                when {
                    cancelled -> Unit
                    e != null -> showResult("AI 调用失败", e)
                    else -> onDone(reply)
                }
            }
        }, "yeshu-ai-shelf").start()
    }

    /**
     * 视图内进度提示：不持有 Activity 窗口，随 ShelfView 分离自然消失。
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
        val lp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Glass.dp(112, d)
        }
        addView(tv, lp)
        aiProgress = tv
        return tv
    }

    private fun dismissAiProgress() {
        aiProgress?.let { if (it.parent === this) removeView(it) }
        aiProgress = null
    }

    /** 搜索输入防抖：停止输入后再刷新一次，避免逐字符重建整棵列表。 */
    private fun scheduleSearchRefresh() {
        searchDebounce?.let(searchHandler::removeCallbacks)
        val task = Runnable {
            searchDebounce = null
            if (!isAttachedToWindow) return@Runnable
            refresh(etSearch.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
        }
        searchDebounce = task
        searchHandler.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    /**
     * 显示底部弹层：弹层直接挂在 decorView 上，导航返回键默认不会关闭它，
     * 因此显示期间注册一个优先消费返回键的回调，先关弹层再退出页面。
     */
    private fun showSheet(title: String, configure: BottomSheet.() -> Unit) {
        activeSheet?.dismiss()
        val sheet = BottomSheet(act, title)
        sheet.configure()
        activeSheet = sheet
        ensureSheetBackCallback().isEnabled = true
        sheet.show()
    }

    /**
     * R44：阅读器记录的最近位置（「第 3 章 · 第 12 段」/「第 5 / 120 页」）。
     * 没有记录时返回空串，界面就不显示这一段，避免出现「· 未知」。
     */
    private fun rememberedPosition(bookId: Long): String =
        db.getSetting(ReaderView.positionSettingKey(bookId)).orEmpty().trim()

    /** 弹层菜单项：点击后弹层自行关闭，这里同步清理返回键状态。 */
    private fun BottomSheet.shelfItem(icon: String, label: String, onClick: () -> Unit) =
        item(icon, label) {
            activeSheet = null
            sheetBackCallback?.isEnabled = false
            onClick()
        }

    private fun ensureSheetBackCallback(): OnBackPressedCallback {
        sheetBackCallback?.let { return it }
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                activeSheet?.dismiss()
                activeSheet = null
                isEnabled = false
            }
        }
        val host = act as? ComponentActivity
        if (host != null) host.onBackPressedDispatcher.addCallback(callback)
        sheetBackCallback = callback
        return callback
    }

    override fun onDetachedFromWindow() {
        // 离开页面即中断进行中的 AI 请求，避免继续占用连接
        aiToken?.cancel()
        aiToken = null
        // 进度浮层随视图分离移除；后台任务在回调前会再次校验附着状态
        dismissAiProgress()
        aiBusy = false
        // 弹层挂在 decorView 上，页面销毁时必须一并清理，否则会浮在下一个页面之上
        activeSheet?.dismiss()
        activeSheet = null
        sheetBackCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        sheetBackCallback = null
        searchDebounce?.let(searchHandler::removeCallbacks)
        searchDebounce = null
        super.onDetachedFromWindow()
    }

    /** ✨ AI 推荐下一本：书单+进度上下文 → 推荐与理由，可直接开读 */
    private fun aiRecommend() {
        val all = db.listBooks()
        if (all.isEmpty()) {
            android.widget.Toast.makeText(act, "书架还是空的，先导入一本书吧", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val list = all.joinToString("\n") { b ->
            val st = when {
                b.progress >= 0.99f -> "已读完"
                b.progress > 0.005f -> "读到 ${((b.progress * 100).toInt())}%"
                else -> "没读过"
            }
            "- 《${b.title}》(${b.format.uppercase()}, $st)"
        }
        aiTask("正在分析你的书架…",
            "你是懂读者的选书顾问。用简体中文回答。",
            buildPrompt = {
                "这是我的书架和阅读状态：\n$list\n\n" +
                    "请推荐我「下一本读什么」：挑一本最值得现在读的（优先考虑在读但未读完的书），" +
                    "输出格式：第一行只写书名，空一行后给 2-3 句推荐理由。不要推荐书架以外的书。"
            },
            onDone = { reply ->
                // 解析第一行书名并尝试匹配
                val firstLine = reply.lineSequence().firstOrNull()?.trim()
                    ?.removePrefix("《")?.removeSuffix("》")?.removeSuffix("》") ?: ""
                val match = all.firstOrNull {
                    it.title == firstLine || it.title.contains(firstLine) || firstLine.contains(it.title)
                }
                if (match != null) {
                    AlertDialog.Builder(act)
                        .setTitle("下一本：《${match.title}》")
                        .setMessage(reply.substringAfter("\n").trim().ifEmpty { "就是它了！" })
                        .setPositiveButton("去阅读") { _, _ -> openReader(match.id) }
                        .setNegativeButton("关闭", null)
                        .show().also { Glass.styleDialog(it, density(act)) }
                } else {
                    showResult("AI 推荐", reply)
                }
            })
    }

    /** AI 找书：自然语言描述 → 匹配书架书目 */
    private fun aiFindBook(query: String) {
        val all = db.listBooks()
        val list = all.joinToString("\n") { "- 《${it.title}》(格式${it.format.uppercase()})" }
        aiTask("让 AI 在书架里找…",
            "你是图书检索助手。只输出书名本身，不要任何多余内容。",
            buildPrompt = {
                "我的书架有这些书：\n$list\n\n我想找：「$query」\n\n" +
                    "从上面书架中选出最匹配的一本，只输出它的完整书名。若无匹配只输出：无匹配"
            },
            onDone = { name ->
                val clean = name.trim().removePrefix("《").removeSuffix("》")
                val hit = all.firstOrNull {
                    it.title == clean || it.title.contains(clean) || clean.contains(it.title)
                }
                when {
                    hit != null -> {
                        android.widget.Toast.makeText(act, "找到了：《${hit.title}》", android.widget.Toast.LENGTH_SHORT).show()
                        etSearch.setText(hit.title)
                    }
                    else -> showResult("AI 找书", "书架上没有找到与「$query」匹配的书。\n可以换个说法再试试，或者导入新书。")
                }
            })
    }

    private fun updateMultiCount() {
        multiCountTv?.text = "已选 ${selected.size} 本"
    }

    private fun selectAllVisible() {
        val books = db.listBooks(curFolder)
        if (selected.size >= books.size) selected.clear() else books.forEach { selected.add(it.id) }
        updateMultiCount()
        refresh()
    }

    private fun batchMove() {
        if (selected.isEmpty()) return
        moveDialog(targetBook = -1, bookIds = selected.toSet())
    }

    private fun batchDelete() {
        if (selected.isEmpty()) return
        AlertDialog.Builder(act)
            .setTitle("批量删除")
            .setMessage("将选中的 ${selected.size} 项移入回收站？原文件会保留，可恢复。")
            .setPositiveButton("移入回收站") { _, _ ->
                selected.forEach { id ->
                    db.deleteBook(id)
                }
                selected.clear()
                updateMultiCount()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** Soft-deleted items retain originals and covers until the user explicitly purges them. */
    private fun showRecycleBin() {
        val deleted = db.listDeletedBooks()
        if (deleted.isEmpty()) {
            showResult("回收站", "回收站是空的")
            return
        }
        val labels = deleted.map { book ->
            val available = File(act.filesDir, book.fileName).isFile
            (if (available) "" else "⚠ ") + book.title
        }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle("回收站 · ${deleted.size} 项")
            .setItems(labels) { _, index ->
                val book = deleted[index]
                AlertDialog.Builder(act)
                    .setTitle(book.title)
                    .setMessage("恢复会放回原书架；永久删除会移除原文件、封面和数据库记录。")
                    .setPositiveButton("恢复") { _, _ ->
                        if (File(act.filesDir, book.fileName).isFile) {
                            db.restoreDeletedBook(book.id)
                            refresh()
                        } else {
                            showResult("无法恢复", "原文件已不存在，请重新导入。")
                        }
                    }
                    .setNegativeButton("永久删除") { _, _ ->
                        File(act.filesDir, book.fileName).delete()
                        CoverStore.delete(act, book.id)
                        db.purgeBook(book.id)
                        refresh()
                    }
                    .setNeutralButton("取消", null)
                    .show().also { Glass.styleDialog(it, density(act)) }
            }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /**
     * 背景图复用同一个 ImageView：避免每次更换背景都叠加一层全屏视图（泄漏位图与 GPU 层）。
     * 解码时按屏幕上限降采样，兼容历史遗留的全尺寸 bg.img。
     */
    private fun loadBg() {
        val f = File(act.filesDir, "bg.img")
        if (!f.exists()) return
        val host = bgHost ?: return
        val bm = decodeSampledFile(f, BG_MAX_DIMENSION) ?: return
        val iv = bgImage ?: ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            Glass.blur(this)
            host.addView(this, 0, LayoutParams(-1, -1))
            bgImage = this
        }
        val previous = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        iv.setImageBitmap(bm)
        if (previous != null && previous !== bm && !previous.isRecycled) previous.recycle()
    }

    /** 按最大边长降采样读取本地图片，避免全尺寸解码导致 OOM。 */
    private fun decodeSampledFile(file: File, maxDimension: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun enterFolder(folderId: Long) {
        val destination = if (folderId == 0L || db.getFolder(folderId) != null) folderId else 0L
        curFolder = destination
        val hadQuery = ::etSearch.isInitialized && etSearch.text?.isNotBlank() == true
        if (hadQuery) {
            // TextWatcher refreshes synchronously after the current folder has changed.
            etSearch.setText("")
            etSearch.clearFocus()
        } else {
            refresh()
        }
    }

    /** Root + clickable path segments + an explicit current-level create action. */
    private fun folderNavigator(d: Float): View {
        val path = db.folderPath(curFolder)
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(T.rCard, d).toFloat()
                setColor(pal.surface)
            }
            setPadding(Glass.dp(8, d), Glass.dp(7, d), Glass.dp(8, d), Glass.dp(7, d))
        }

        if (curFolder != 0L) {
            row.addView(TextView(act).apply {
                text = "‹"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(pal.textP)
                background = Glass.pressFx()
                contentDescription = "返回上一级"
                setOnClickListener { enterFolder(db.getFolder(curFolder)?.parentId ?: 0L) }
            }, LinearLayout.LayoutParams(Glass.dp(38, d), Glass.dp(38, d)))
        }

        val pathRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addSegment(label: String, folderId: Long, active: Boolean) {
            if (pathRow.childCount > 0) {
                pathRow.addView(TextView(act).apply {
                    text = "›"
                    textSize = 16f
                    setTextColor(pal.textT)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(Glass.dp(22, d), Glass.dp(34, d)))
            }
            pathRow.addView(TextView(act).apply {
                text = label
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.WHITE else pal.textS)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(11, d).toFloat()
                    setColor(if (active) T.accent else Color.TRANSPARENT)
                }
                setPadding(Glass.dp(11, d), 0, Glass.dp(11, d), 0)
                setOnClickListener { if (!active) enterFolder(folderId) }
            }, LinearLayout.LayoutParams(-2, Glass.dp(34, d)))
        }
        addSegment("根目录", 0L, curFolder == 0L)
        path.forEachIndexed { index, folder ->
            addSegment(folder.name, folder.id, index == path.lastIndex)
        }

        val scroller = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(pathRow, LayoutParams(-2, -1))
            post { fullScroll(android.view.View.FOCUS_RIGHT) }
        }
        row.addView(scroller, LinearLayout.LayoutParams(0, Glass.dp(38, d), 1f))
        row.addView(TextView(act).apply {
            text = "+ 分类"
            textSize = 12f
            setTextColor(T.accent)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Glass.pillBg(Color.argb(32, 91, 95, 245))
            setPadding(Glass.dp(12, d), 0, Glass.dp(12, d), 0)
            setOnClickListener { newFolderDialog() }
        }, LinearLayout.LayoutParams(-2, Glass.dp(36, d)).also {
            it.marginStart = Glass.dp(7, d)
        })
        return row
    }

    /**
     * Refresh-time fallback for imports whose background extraction had not finished yet.
     * Successful detection invalidates the placeholder immediately; known misses are cached by
     * CoverStore and therefore do not repeatedly parse a large PDF/EPUB.
     */
    private fun ensureAutomaticCover(book: Book) {
        if (CoverStore.hasCover(act, book.id)) return
        val source = File(act.filesDir, book.fileName)
        if (!source.isFile) return
        CoverStore.ensureAutoAsync(act, book.id, source, book.format) { generated ->
            if (!generated || !isAttachedToWindow) return@ensureAutoAsync
            val query = etSearch.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            refresh(query)
        }
    }

    /**
     * 封面异步加载：主线程先显示程序化占位，后台线程解码真实封面后再回填。
     * 用 tag 记录目标 bookId，回填前校验附着状态与 tag，避免复用的行显示过期封面。
     */
    private fun loadCoverAsync(iv: ImageView, bookId: Long, title: String, format: String, w: Int, h: Int) {
        iv.tag = bookId
        iv.setImageBitmap(BookCover.placeholder(title, format, w, h))
        coverExecutor.execute {
            val bm = CoverStore.load(act.applicationContext, bookId)
            if (bm == null || bm.isRecycled) return@execute
            act.runOnUiThread {
                if (iv.tag == bookId && iv.isAttachedToWindow && !bm.isRecycled) iv.setImageBitmap(bm)
            }
        }
    }

    fun refresh(query: String? = null) {
        val d = density(act)
        listBox.removeAllViews()
        // A folder can disappear after restoring a backup or deleting it from another screen.
        // Never leave the shelf stranded on an invalid level.
        if (curFolder != 0L && db.getFolder(curFolder) == null) curFolder = 0L
        val searching = !query.isNullOrBlank()
        val rawBooks = if (searching) db.searchBooks(query) else db.listBooks(curFolder)
        // 全部书目/分类各只取一次并缓存：文件夹计数与搜索结果所属分类都在内存里查表，
        // 避免每个分类再各查两次、每本书再查一次（N+1 查询会让每次搜索输入都明显卡顿）。
        var allBooksCache: List<Book>? = null
        var allFoldersCache: List<Folder>? = null
        fun allBooksOnce(): List<Book> = allBooksCache ?: db.listBooks().also { allBooksCache = it }
        fun allFoldersOnce(): List<Folder> = allFoldersCache ?: db.allFolders().also { allFoldersCache = it }
        val subs = if (searching) {
            allFoldersOnce().filter { it.name.contains(query.orEmpty(), ignoreCase = true) }
        } else {
            db.listFolders(curFolder)
        }
        val childBookCounts: Map<Long, Int> =
            if (subs.isEmpty()) emptyMap() else allBooksOnce().groupingBy { it.folderId }.eachCount()
        val childFolderCounts: Map<Long, Int> =
            if (subs.isEmpty()) emptyMap() else allFoldersOnce().groupingBy { it.parentId }.eachCount()
        val folderNames: Map<Long, String> =
            if (searching) allFoldersOnce().associate { it.id to it.name } else emptyMap()

        // 排序：最近阅读(默认) / 标题 / 加入时间
        val sortMode = db.getSetting("shelf_sort") ?: "recent"
        // 筛选：全部 / 在读 / 未读 / 读完（本地过滤）
        val filter = db.getSetting("shelf_filter") ?: "all"
        val filtered = when (filter) {
            "favorite" -> rawBooks.filter { it.favorite }
            "reading" -> rawBooks.filter { it.progress > 0.005f && it.progress < 0.99f }
            "unread" -> rawBooks.filter { it.progress <= 0.005f }
            "done" -> rawBooks.filter { it.progress >= 0.99f }
            else -> rawBooks
        }
        // 排序规则集中在 ShelfSort 里，便于单测（顺序改坏时很难从界面反推原因）。
        val books: List<Book> = ShelfSort.sort(
            items = filtered,
            mode = sortMode,
            title = { it.title },
            author = { it.author },
            addedAt = { it.addedAt },
            lastReadAt = { it.lastReadAt },
        )
        books.forEach(::ensureAutomaticCover)

        // 筛选 chips（非搜索时显示）
        if (!searching && filterRow != null && filterHost != null) {
            filterRow!!.removeAllViews()
            listOf("全部" to "all", "收藏" to "favorite", "在读" to "reading", "未读" to "unread", "读完" to "done").forEach { (label, key) ->
                val active = filter == key
                val c = TextView(act).apply {
                    text = label; textSize = 12f
                    setTextColor(if (active) Color.WHITE else pal.textS)
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = Glass.dp(999, d).toFloat()
                        setColor(if (active) T.accent else pal.surface2)
                    }
                    setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(6, d))
                    val lp = LinearLayout.LayoutParams(-2, -2)
                    lp.marginEnd = Glass.dp(8, d)
                    layoutParams = lp
                    setOnClickListener {
                        db.setSetting("shelf_filter", key); refresh(query)
                    }
                }
                filterRow!!.addView(c)
            }
            filterHost!!.visibility = View.VISIBLE
        } else {
            filterHost?.visibility = View.GONE
        }

        // 统计信息迁至搜索框下方常驻行（含排序按钮文案）
        val finishedN = books.count { it.progress >= 0.99f }
        val readingN = books.count { it.progress > 0.005f && it.progress < 0.99f }
        val sortLabel = ShelfSort.label(sortMode)
        statTv.text = buildString {
            append("${books.size} 本书")
            if (subs.isNotEmpty()) append(" · ${subs.size} 个分类")
            if (!searching && readingN > 0) append(" · 在读 $readingN")
            if (!searching && finishedN > 0) append(" · 已读完 $finishedN")
        }
        sortBtn.text = "排序：$sortLabel"
        segWrap.visibility = if (searching) View.GONE else View.VISIBLE

        // Current-level navigator. Every segment is clickable, so a deep hierarchy never
        // requires repeatedly backing out one level at a time.
        if (!searching) {
            listBox.addView(folderNavigator(d), LinearLayout.LayoutParams(-1, -2).also {
                it.setMargins(0, Glass.dp(4, d), 0, 0)
            })
        }

        // 「最近在读」横滑条：仅根目录非搜索时展示
        if (curFolder == 0L && !searching) {
            val recent = allBooksOnce().filter { it.progress > 0.005f }
                .sortedByDescending { it.lastReadAt }
                .take(6)
            recent.forEach(::ensureAutomaticCover)
            if (recent.isNotEmpty()) {
                val sec = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.setMargins(Glass.dp(2, d), Glass.dp(14, d), 0, 0)
                    layoutParams = lp
                    addView(TextView(act).apply {
                        text = "最近在读"
                        textSize = 13f
                        setTextColor(pal.textP)
                        setTypeface(null, Typeface.BOLD)
                        alpha = 0.85f
                    })
                    addView(android.widget.HorizontalScrollView(act).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(LinearLayout(act).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, Glass.dp(10, d), 0, Glass.dp(4, d))
                            for (rb in recent) {
                                val cw = Glass.dp(76, d)
                                val ch = Glass.dp(96, d)
                                val cell = LinearLayout(act).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                                        it.marginEnd = Glass.dp(12, d)
                                    }
                                    val face = FrameLayout(act).apply {
                                        outlineProvider = object : android.view.ViewOutlineProvider() {
                                            override fun getOutline(v: View, o: android.graphics.Outline) {
                                                o.setRoundRect(0, 0, v.width, v.height, Glass.dp(T.rCover, d).toFloat())
                                            }
                                        }
                                        clipToOutline = true
                                        val cover: android.view.View =
                                            if (CoverStore.file(act, rb.id).exists()) ImageView(act).apply {
                                                scaleType = ImageView.ScaleType.CENTER_CROP
                                                loadCoverAsync(this, rb.id, rb.title, rb.format, 160, 220)
                                            } else ImageView(act).apply {
                                                scaleType = ImageView.ScaleType.FIT_CENTER
                                                setImageBitmap(BookCover.placeholder(rb.title, rb.format, 160, 220))
                                            }
                                        addView(cover, LayoutParams(-1, ch))
                                    // 底部渐变 + 进度文字
                                    addView(View(act).apply {
                                        background = android.graphics.drawable.GradientDrawable(
                                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                            intArrayOf(Color.TRANSPARENT, Color.argb(190, 0, 0, 0))
                                        )
                                    }, LayoutParams(-1, Glass.dp(34, d)).also {
                                        it.gravity = Gravity.BOTTOM
                                    })
                                    addView(TextView(act).apply {
                                        text = "${(rb.progress * 100).toInt()}%"
                                        textSize = 11f
                                        setTextColor(Color.WHITE)
                                        setTypeface(null, Typeface.BOLD)
                                        gravity = Gravity.BOTTOM or Gravity.START
                                        setPadding(Glass.dp(6, d), 0, 0, Glass.dp(5, d))
                                    }, LayoutParams(-1, -2).also { it.gravity = Gravity.BOTTOM })
                                    // 按压反馈
                                    setOnTouchListener { v, ev ->
                                        when (ev.actionMasked) {
                                            android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.75f
                                            android.view.MotionEvent.ACTION_UP,
                                            android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                                        }
                                        false
                                    }
                                    setOnClickListener { openReader(rb.id) }
                                    }
                                    addView(face, LinearLayout.LayoutParams(cw, ch))
                                    addView(TextView(act).apply {
                                        text = rb.title
                                        textSize = 11f
                                        setTextColor(pal.textP)
                                        maxLines = 1
                                        ellipsize = android.text.TextUtils.TruncateAt.END
                                        setPadding(0, Glass.dp(5, d), 0, 0)
                                    }, LinearLayout.LayoutParams(cw, -2))
                                }
                                addView(cell)
                            }
                        })
                    })
                }
                listBox.addView(sec)
            }
        }

        // 文件夹行：紧凑横卡 68-76dp，实底 surface、统一图标、chevron
        for ((fi, f) in subs.withIndex()) {
            val childBooks = childBookCounts[f.id] ?: 0
            val childFolders = childFolderCounts[f.id] ?: 0
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(T.rCard, d).toFloat()
                    setColor(pal.surface)
                }
                setPadding(Glass.dp(16, d), Glass.dp(12, d), Glass.dp(16, d), Glass.dp(12, d))
                addView(FrameLayout(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(26, 255, 255, 255))
                    }
                    foreground = null
                    addView(IconView(act, "folder", 20, pal.icon), FrameLayout.LayoutParams(
                        Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER))
                }, LinearLayout.LayoutParams(Glass.dp(40, d), Glass.dp(40, d)))
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also {
                        it.marginStart = Glass.dp(14, d)
                    }
                    addView(TextView(act).apply {
                        text = f.name
                        textSize = 15f
                        setTextColor(pal.textP)
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 1
                    })
                    addView(TextView(act).apply {
                        text = buildString {
                            append("$childBooks 本书")
                            if (childFolders > 0) append(" · $childFolders 个子分类")
                        }
                        textSize = 12f
                        setTextColor(pal.textS)
                    }, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = Glass.dp(3, d) })
                })
                addView(IconView(act, "chevron", 16, pal.icon))
                // 按压：轻微缩放反馈
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(T.durFast.toLong()).start()
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(T.durNorm.toLong()).start()
                    }
                    false
                }
                setOnClickListener { enterFolder(f.id) }
                setOnLongClickListener {
                    val options = arrayOf("打开", "重命名", "移动到…", "删除")
                    AlertDialog.Builder(act)
                        .setTitle(f.name)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> enterFolder(f.id)
                                1 -> renameFolderDialog(f)
                                2 -> moveDialog(targetFolder = f.id)
                                3 -> AlertDialog.Builder(act)
                                    .setTitle("删除分类")
                                    .setMessage("删除「${f.name}」？其中的书和子分类会移到上一级。")
                                    .setPositiveButton("删除") { _, _ ->
                                        db.deleteFolder(f.id); refresh()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show().also { Glass.styleDialog(it, density(act)) }
                            }
                        }
                        .show().also { Glass.styleDialog(it, density(act)) }
                    true
                }
            }
            val cp = LinearLayout.LayoutParams(-1, -2)
            cp.setMargins(0, Glass.dp(if (fi == 0 && books.isEmpty()) 10 else 14, d), 0, 0)
            listBox.addView(card, cp)
        }

        if (books.size == 0) {
            if (subs.isEmpty()) {
                val hint = when {
                    searching -> Glass.emptyState(act, "🔍", "没有匹配的书或分类", "搜索会查找整个书架", icon = "search")
                    else -> Glass.emptyState(
                        act, "📚", "这一层还是空的", "导入书籍，或在当前层新建分类",
                        icon = "book",
                        actionLabel = "导入书籍",
                        onAction = { (act as MainActivity).importDocuments() },
                    )
                }
                val hp = LinearLayout.LayoutParams(-1, -2)
                hp.setMargins(0, Glass.dp(60, d), 0, 0)
                listBox.addView(hint, hp)
                // 搜索无结果 → AI 找书兜底入口
                if (searching && query != null) {
                    val aiRow = TextView(act).apply {
                        text = "没找到？让 AI 帮你找「${query.take(12)}」"
                        textSize = 14f
                        setTextColor(T.accent)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            cornerRadius = Glass.dp(T.rCard, d).toFloat()
                            setColor(Color.parseColor("#12233D"))
                        }
                        setPadding(Glass.dp(16, d), Glass.dp(14, d), Glass.dp(16, d), Glass.dp(14, d))
                        setOnClickListener { aiFindBook(query) }
                    }
                    val ap = LinearLayout.LayoutParams(-1, -2)
                    ap.setMargins(Glass.dp(20, d), Glass.dp(16, d), Glass.dp(20, d), 0)
                    listBox.addView(aiRow, ap)
                }
            }
            return
        }
        // Apple Books 式两列大封面网格（搜索模式保持列表以显示文件夹信息）
        val gridMode = (db.getSetting("shelf_view") ?: "list") == "grid" && !searching
        if (gridMode) {
            val sw = act.resources.displayMetrics.widthPixels
            val gutter = Glass.dp(14, d)
            val sidePad = Glass.dp(T.pagePad, d)
            val cw = (sw - sidePad * 2 - gutter) / 2
            var row: LinearLayout? = null
            books.forEachIndexed { gi, b ->
                if (gi % 2 == 0) {
                    row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    val rp = LinearLayout.LayoutParams(-1, -2)
                    rp.setMargins(0, Glass.dp(if (gi == 0) 10 else 14, d), 0, 0)
                    listBox.addView(row, rp)
                }
                var cell = gridCard(b, cw)
                if (multiMode) cell = wrapSelectable(cell, b.id)
                (row as LinearLayout).addView(cell, LinearLayout.LayoutParams(cw, -2))
            }
            return
        }
        for ((i, b) in books.withIndex()) {
            val kb = formatBytes(b.sizeBytes)
            val pct = ((b.progress * 100).toInt()).toString() + "%"
            // R44：百分比之外再给出「读到哪一章 / 第几页」，比 37% 更有信息量
            val position = rememberedPosition(b.id)

            // 左封面：56x84dp，真封面或程序化占位
            val tw = Glass.dp(T.coverWList, d)
            val th = Glass.dp(T.coverHList, d)
            val thumb: android.view.View = if (CoverStore.file(act, b.id).exists()) {
                ImageView(act).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    loadCoverAsync(this, b.id, b.title, b.format, 160, 240)
                }
            } else {
                ImageView(act).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(BookCover.placeholder(b.title, b.format, 160, 240))
                }
            }
            val thumbWrap = FrameLayout(act).apply {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, Glass.dp(8, d).toFloat())
                    }
                }
                clipToOutline = true
            }
            thumbWrap.addView(thumb, LayoutParams(tw, th))

            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(T.rCard, d).toFloat()
                    setColor(pal.surface)
                }
                setPadding(Glass.dp(12, d), Glass.dp(12, d), Glass.dp(12, d), Glass.dp(12, d))
                val texts = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    lp.marginStart = Glass.dp(14, d)
                    layoutParams = lp
                    val t1 = TextView(context).apply {
                        // 搜索模式：命中片段金黄高亮
                        if (searching && query != null) {
                            val idx = b.title.lowercase().indexOf(query.lowercase())
                            if (idx >= 0) {
                                val sp = android.text.SpannableString(b.title)
                                sp.setSpan(
                                    android.text.style.BackgroundColorSpan(Color.parseColor("#FFE082")),
                                    idx, idx + query.length,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                text = sp
                            } else text = b.title
                        } else text = b.title
                        textSize = 15f
                        setTextColor(pal.textP)
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 2
                    }
                    addView(t1, LinearLayout.LayoutParams(-2, -2))
                    // 元信息：格式胶囊（低饱和）+ 大小
                    val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    meta.addView(TextView(context).apply {
                        text = b.format.uppercase().ifEmpty { "TXT" }
                        textSize = 9f
                        setTextColor(pal.textS)
                        setTypeface(null, Typeface.BOLD)
                        letterSpacing = 0.06f
                        background = GradientDrawable().apply {
                            cornerRadius = Glass.dp(4, d).toFloat()
                            setColor(Color.argb(30, 255, 255, 255))
                        }
                        setPadding(Glass.dp(5, d), Glass.dp(2, d), Glass.dp(5, d), Glass.dp(2, d))
                    })
                    meta.addView(TextView(context).apply {
                        text = "  " + kb + if (searching) {
                            if (b.folderId == 0L) "" else " · " + (folderNames[b.folderId] ?: "")
                        } else {
                            if (position.isBlank()) "" else " · " + position
                        }
                        textSize = 11f
                        setTextColor(pal.textT)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(meta, LinearLayout.LayoutParams(-2, -2).also {
                        it.topMargin = Glass.dp(6, d)
                    })
                    // 进度：细条 + 百分比（在读的书才显示；accent 蓝）
                    if (b.progress > 0.005f) {
                        val prow = LinearLayout(context).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            val lp = LinearLayout.LayoutParams(-1, -2)
                            lp.topMargin = Glass.dp(10, d)
                            layoutParams = lp
                        }
                        val trackBg = GradientDrawable().apply {
                            cornerRadius = (1.5f * d)
                            setColor(Color.argb(36, 255, 255, 255))
                        }
                        val trackFill = android.graphics.drawable.ClipDrawable(
                            GradientDrawable().apply {
                                cornerRadius = (1.5f * d)
                                setColor(T.accent)
                            },
                            Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL
                        ).apply { level = (b.progress * 10000).toInt() }
                        val track = View(context).apply {
                            background = android.graphics.drawable.LayerDrawable(arrayOf(trackBg, trackFill))
                        }
                        prow.addView(track, LinearLayout.LayoutParams(0, Glass.dp(3, d), 1f))
                        prow.addView(TextView(context).apply {
                            text = pct
                            textSize = 10f
                            setTextColor(T.accent)
                            setTypeface(null, Typeface.BOLD)
                            val plp = LinearLayout.LayoutParams(-2, -2)
                            plp.marginStart = Glass.dp(8, d)
                            layoutParams = plp
                        })
                        addView(prow)
                    }
                }
                addView(thumbWrap)
                addView(texts)
                addView(IconView(act, "chevron", 14, pal.icon), LinearLayout.LayoutParams(-2, -2).also {
                    it.marginStart = Glass.dp(6, d)
                })
                // 按压：轻微缩放
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(T.durFast.toLong()).start()
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(T.durNorm.toLong()).start()
                    }
                    false
                }
                setOnClickListener { openReader(b.id) }
                setOnLongClickListener { bookLongPress(b); true }
            }
            val cp = LinearLayout.LayoutParams(-1, -2)
            cp.setMargins(0, Glass.dp(if (i == 0) 10 else 12, d), 0, 0)
            var cardView: android.view.View = card
            if (multiMode) cardView = wrapSelectable(card, b.id)
            listBox.addView(cardView, cp)
        }
    }

    /** 多选包装：左上角勾选圈，点击切换选中 */
    private fun wrapSelectable(content: android.view.View, bookId: Long): android.view.View {
        val d = density(act)
        val on = bookId in selected
        val badge = TextView(act).apply {
            text = if (on) "✓" else ""
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = if (on) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3B82F6"))
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(110, 0, 0, 0))
                    setStroke(Glass.dp(2, d), Color.WHITE)
                }
            }
            elevation = Glass.dp(5, d).toFloat()
        }
        val wrap = FrameLayout(act)
        wrap.addView(content, LayoutParams(-1, -2))
        val bp = LayoutParams(Glass.dp(26, d), Glass.dp(26, d), Gravity.TOP or Gravity.START)
        bp.setMargins(Glass.dp(10, d), Glass.dp(10, d), 0, 0)
        wrap.addView(badge, bp)
        content.setOnClickListener {
            if (multiMode) {
                if (bookId in selected) selected.remove(bookId) else selected.add(bookId)
                updateMultiCount()
                refresh()
            } else openReader(bookId)
        }
        content.setOnLongClickListener(null)
        return wrap
    }

    /** 网格模式书籍卡：大封面(3:4) + 书名 + 格式/进度角标 */
    private fun gridCard(b: Book, cw: Int): android.view.View {
        val d = density(act)
        val coverH = cw * 3 / 2   // 接近 2:3 竖版比例
        val pct = ((b.progress * 100).toInt()).toString() + "%"
        // R44：网格卡片同样给出「读到哪一章 / 第几页」
        val position = rememberedPosition(b.id)
        val thumb: android.view.View = if (CoverStore.file(act, b.id).exists()) {
            ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                loadCoverAsync(this, b.id, b.title, b.format, 320, 480)
            }
        } else {
            ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BookCover.placeholder(b.title, b.format, 320, 480))
            }
        }
        val face = FrameLayout(act).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, Glass.dp(T.rCover, d).toFloat())
                }
            }
            clipToOutline = true
            elevation = Glass.dp(2, d).toFloat()   // 柔和低阴影
            addView(thumb, LayoutParams(-1, coverH))
            // 封面底部 2dp 细进度条（进度为 0 时不显示）
            if (b.progress > 0.005f) {
                addView(View(act).apply {
                    background = android.graphics.drawable.ClipDrawable(
                        GradientDrawable().apply { setColor(T.accent) },
                        Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL
                    ).apply { level = (b.progress * 10000).toInt() }
                }, LayoutParams(-1, (2.5f * d).toInt()).also {
                    it.gravity = Gravity.BOTTOM
                })
            }
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(2, d), 0, Glass.dp(2, d), 0)
            addView(face)
            addView(TextView(act).apply {
                text = b.title
                textSize = 13f
                setTextColor(pal.textP)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(8, d) })
            addView(TextView(act).apply {
                text = buildString {
                    append(b.format.uppercase().ifEmpty { "TXT" }).append(" · ").append(pct)
                    if (position.isNotBlank()) append(" · ").append(position)
                }
                textSize = 11f
                setTextColor(pal.textS)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = Glass.dp(3, d) })
            // 按压：轻微缩放
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(T.durFast.toLong()).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(T.durNorm.toLong()).start()
                }
                false
            }
            setOnClickListener { openReader(b.id) }
            setOnLongClickListener { bookLongPress(b); true }
        }
    }

    /** 书籍详情：元信息一览 */
    /** 书籍详情：元信息一览 + AI 简介 */
    private fun bookDetail(b: Book) {
        val d = density(act)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        val kb = if (b.sizeBytes < 1024) "${b.sizeBytes} B"
                 else (b.sizeBytes / 1024).toString() + " KB"
        val pct = ((b.progress * 100).toInt()).toString() + "%"
        val folderName = if (b.folderId == 0L) "根目录" else db.getFolder(b.folderId)?.name ?: "?"
        val lastRead = if (b.lastReadAt > 0) fmt.format(java.util.Date(b.lastReadAt)) else "还没翻开过"
        val readMs = try { db.totalReadMs(b.id) } catch (e: Exception) { 0L }
        val readLabel = when {
            readMs < 60_000 -> if (readMs <= 0) "暂无记录" else "不足 1 分钟"
            else -> "${readMs / 3600000} 小时 ${(readMs % 3600000) / 60000} 分钟".trim().let { if (it.startsWith("0 ")) it.substringAfter(" ") else it }
        }
        // 已有 AI 简介则展示：listNotes 按 id DESC 返回，最新一条在最前面
        val intro = db.listNotes(b.id, "intro").firstOrNull()?.content
        val introText = intro ?: "还没有简介，点「AI 简介」一键生成"
        val body = TextView(act).apply {
            textSize = 14f
            setTextColor(pal.textP)
            setLineSpacing(Glass.dp(4, d).toFloat(), 1f)
            setPadding(Glass.dp(24, d), Glass.dp(18, d), Glass.dp(24, d), Glass.dp(8, d))
            text = "$introText\n\n" +
                   "作者：${b.author.ifBlank { "未填写" }}    收藏：${if (b.favorite) "是" else "否"}\n" +
                   "标签：${b.tags.ifBlank { "未填写" }}\n" +
                   "封面：${CoverStore.sourceLabel(act, b.id)}\n" +
                   "格式：${b.format.uppercase()}    大小：$kb\n" +
                   "阅读进度：$pct    累计时长：$readLabel\n" +
                   "所在位置：$folderName\n" +
                   "加入书架：${fmt.format(java.util.Date(b.addedAt))}\n" +
                   "最近阅读：$lastRead\n" +
                   "文件：${b.fileName.take(40)}"
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(act)
            .setTitle("《${b.title}》")
            .setView(body)
            .setNeutralButton(if (intro == null) "AI 简介" else "换个简介") { _, _ -> aiIntro(b) }
            .setPositiveButton("开始阅读") { _, _ -> openReader(b.id) }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, d) }
    }

    private fun editMetadata(b: Book) {
        val d = density(act)
        val authorInput = EditText(act).apply {
            hint = "作者"
            setText(b.author)
            setSingleLine(true)
        }
        val tagsInput = EditText(act).apply {
            hint = "标签（用逗号分隔）"
            setText(b.tags)
            setSingleLine(true)
        }
        val form = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(22, d), Glass.dp(8, d), Glass.dp(22, d), 0)
            addView(authorInput, LinearLayout.LayoutParams(-1, -2))
            addView(tagsInput, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(8, d) })
        }
        AlertDialog.Builder(act)
            .setTitle("编辑信息 · ${b.title}")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                db.updateMetadata(b.id, authorInput.text.toString(), tagsInput.text.toString())
                refresh()
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, d) }
    }

    /** AI 简介：取书前几千字生成百字简介+类型标签，存 notes(kind=intro) */
    private fun aiIntro(b: Book) {
        aiTask("正在为《${b.title.take(10)}》生成简介…",
            // 简介语言跟随书籍正文：英文/日文书不该拿到中文简介
            "你是专业的图书编辑。输出语言：与书籍主要语言一致。",
            buildPrompt = {
                val f = File(act.filesDir, b.fileName)
                val head = try {
                    if (b.format == "pdf") "(PDF 文档)" else DocParser.parseText(f).fullText.take(6000)
                } catch (e: Exception) { "(无法提取文本)" }
                // 把开头正文一并给出，模型据此判断书籍主要语言
                "请为这本书写一段吸引人但不剧透的简介（80-120 字），输出语言：与书籍主要语言一致，" +
                    "结尾用【】标注类型标签（如【科幻·悬疑】）。\n\n" +
                    "书名：《${b.title}》\n【开头内容】\n$head"
            },
            onDone = { reply ->
                // 简介只保留最新一条：先清掉旧的 intro 行再写入，
                // 否则每次重新生成都会追加一行，notes 表无限增长且详情页取不到新内容。
                db.listNotes(b.id, "intro").forEach { db.deleteNote(it.id) }
                db.addNote(b.id, "intro", reply)
                android.widget.Toast.makeText(act, "简介已生成 ✓", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
                bookDetail(b)
            })
    }

    /** 书籍长按操作菜单（列表/网格共用） */
    private fun bookLongPress(b: Book): Boolean {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "打开" to { openReader(b.id) }
        actions += "详情" to { bookDetail(b) }
        actions += (if (b.favorite) "取消收藏" else "收藏") to {
            db.setFavorite(b.id, !b.favorite)
            refresh()
        }
        actions += "更换封面（当前：${CoverStore.sourceLabel(act, b.id)}）" to {
            chooseCustomCover(b)
        }
        if (CoverStore.isCustom(act, b.id)) {
            actions += "恢复自动封面" to { confirmRestoreAutomaticCover(b) }
        }
        actions += "编辑作者与标签" to { editMetadata(b) }
        actions += "AI 摘要" to { aiSummary(b) }
        actions += "笔记" to { (act as MainActivity).showNotes(b.id) }
        actions += "移动到…" to { moveDialog(targetBook = b.id) }
        actions += "导出笔记" to { exportNotes(b) }
        actions += "删除" to {
            AlertDialog.Builder(act)
                .setTitle("删除")
                .setMessage("将《" + b.title + "》移入回收站？原文件会保留，可恢复。")
                .setPositiveButton("移入回收站") { _, _ ->
                    db.deleteBook(b.id)
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show().also { Glass.styleDialog(it, density(act)) }
        }
        AlertDialog.Builder(act)
            .setTitle(b.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .show().also { Glass.styleDialog(it, density(act)) }
        return true
    }

    private fun chooseCustomCover(book: Book) {
        pendingCoverBookId = book.id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(intent, REQ_CUSTOM_COVER)
        } catch (_: Exception) {
            pendingCoverBookId = -1L
            android.widget.Toast.makeText(act, "无法打开图片选择器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRestoreAutomaticCover(book: Book) {
        AlertDialog.Builder(act)
            .setTitle("恢复自动封面")
            .setMessage("将移除自定义封面，并重新从原书检测封面；检测不到时使用书架占位封面。")
            .setPositiveButton("恢复") { _, _ -> restoreAutomaticCover(book) }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun restoreAutomaticCover(book: Book) {
        val source = File(act.filesDir, book.fileName)
        if (!source.isFile) {
            android.widget.Toast.makeText(act, "原书文件不存在，无法恢复", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(act, "正在重新检测自动封面…", android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val detected = CoverStore.regenerateAutoSync(act.applicationContext, book.id, source, book.format)
            act.runOnUiThread {
                refresh(etSearch.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                val message = if (detected) "已恢复自动封面" else "原文件没有可识别封面，已使用占位封面"
                android.widget.Toast.makeText(act, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }, "yeshu-cover-restore-${book.id}").start()
    }

    /** 新建分类（固定建在打开对话框时所在的层级）。 */
    private fun newFolderDialog() {
        val d = density(act)
        val parentAtOpen = curFolder
        val location = if (parentAtOpen == 0L) "根目录" else {
            db.folderPath(parentAtOpen).joinToString(" / ") { it.name }.ifBlank { "根目录" }
        }
        val input = EditText(act).apply {
            hint = "分类名称"
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            background = Glass.pillBg()
            setPadding(Glass.dp(18, d), Glass.dp(12, d), Glass.dp(18, d), Glass.dp(12, d))
            setSingleLine(true)
        }
        val wrap = FrameLayout(act).apply {
            setPadding(Glass.dp(24, d), Glass.dp(8, d), Glass.dp(24, d), 0)
            addView(input, LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(act)
            .setTitle("新建分类")
            .setMessage("创建位置：$location")
            .setView(wrap)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            Glass.styleDialog(dialog, d)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> input.error = "请输入分类名称"
                    db.listFolders(parentAtOpen).any { it.name.equals(name, ignoreCase = true) } ->
                        input.error = "当前层已有同名分类"
                    else -> {
                        db.addFolder(name, parentAtOpen)
                        dialog.dismiss()
                        if (curFolder == parentAtOpen) refresh()
                    }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        dialog.show()
    }

    private fun renameFolderDialog(f: Folder) {
        val d = density(act)
        val input = EditText(act).apply {
            setText(f.name)
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            background = Glass.pillBg()
            setPadding(Glass.dp(18, d), Glass.dp(12, d), Glass.dp(18, d), Glass.dp(12, d))
            setSingleLine(true)
        }
        val wrap = FrameLayout(act).apply {
            setPadding(Glass.dp(24, d), Glass.dp(8, d), Glass.dp(24, d), 0)
            addView(input, LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(act)
            .setTitle("重命名分类")
            .setView(wrap)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            Glass.styleDialog(dialog, d)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> input.error = "请输入分类名称"
                    db.listFolders(f.parentId).any { it.id != f.id && it.name.equals(name, ignoreCase = true) } ->
                        input.error = "同一层已有同名分类"
                    else -> {
                        db.renameFolder(f.id, name)
                        dialog.dismiss()
                        refresh()
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * 移动对话框：逐层浏览分类，避免深层目录被压缩成难以点击的缩进长列表。
     * targetBook 非空 = 移动书；targetFolder 非空 = 移动分类（跳过自身及子孙防环）。
     */
    private fun moveDialog(targetBook: Long = -1, targetFolder: Long = -1, bookIds: Set<Long> = emptySet()) {
        val d = density(act)
        val title = when {
            bookIds.isNotEmpty() -> "${bookIds.size} 本书"
            targetBook > 0 -> db.getBook(targetBook)?.title ?: return
            else -> "分类「" + (db.getFolder(targetFolder)?.name ?: return) + "」"
        }

        val sourceParent = when {
            targetFolder > 0 -> db.getFolder(targetFolder)?.parentId ?: 0L
            targetBook > 0 -> db.getBook(targetBook)?.folderId ?: curFolder
            else -> curFolder
        }
        var browsing = sourceParent.takeIf { it == 0L || db.getFolder(it) != null } ?: 0L
        val destinationText = TextView(act).apply {
            textSize = 13f
            setTextColor(pal.textS)
            setPadding(Glass.dp(4, d), 0, Glass.dp(4, d), Glass.dp(8, d))
        }
        val pathScroller = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
        }
        val folderList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(20, d), Glass.dp(8, d), Glass.dp(20, d), Glass.dp(4, d))
            addView(destinationText, LinearLayout.LayoutParams(-1, -2))
            addView(pathScroller, LinearLayout.LayoutParams(-1, Glass.dp(42, d)))
            addView(android.widget.ScrollView(act).apply {
                addView(folderList, LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, Glass.dp(300, d)))
        }

        fun allowed(folderId: Long): Boolean = targetFolder <= 0L ||
            !db.isSelfOrDescendant(targetFolder, folderId)

        fun renderLevel() {
            val path = db.folderPath(browsing)
            destinationText.text = "目标位置：" + if (path.isEmpty()) {
                "根目录"
            } else {
                "根目录 / " + path.joinToString(" / ") { it.name }
            }

            val pathRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun pathButton(label: String, id: Long, active: Boolean): TextView = TextView(act).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.WHITE else pal.textS)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(10, d).toFloat()
                    setColor(if (active) T.accent else pal.surface2)
                }
                setPadding(Glass.dp(12, d), 0, Glass.dp(12, d), 0)
                setOnClickListener {
                    browsing = id
                    renderLevel()
                }
            }
            pathRow.addView(pathButton("根目录", 0L, browsing == 0L),
                LinearLayout.LayoutParams(-2, Glass.dp(34, d)))
            path.forEachIndexed { index, folder ->
                pathRow.addView(TextView(act).apply {
                    text = "›"
                    textSize = 16f
                    setTextColor(pal.textT)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(Glass.dp(24, d), Glass.dp(34, d)))
                pathRow.addView(pathButton(folder.name, folder.id, index == path.lastIndex),
                    LinearLayout.LayoutParams(-2, Glass.dp(34, d)))
            }
            pathScroller.removeAllViews()
            pathScroller.addView(pathRow, LayoutParams(-2, -1))
            pathScroller.post { pathScroller.fullScroll(android.view.View.FOCUS_RIGHT) }

            folderList.removeAllViews()
            val children = db.listFolders(browsing).filter { allowed(it.id) }
            if (browsing != 0L) {
                folderList.addView(TextView(act).apply {
                    text = "‹  返回上一级"
                    textSize = 14f
                    setTextColor(T.accent)
                    gravity = Gravity.CENTER_VERTICAL
                    background = Glass.pressFx()
                    setPadding(Glass.dp(12, d), 0, Glass.dp(12, d), 0)
                    setOnClickListener {
                        browsing = db.getFolder(browsing)?.parentId ?: 0L
                        renderLevel()
                    }
                }, LinearLayout.LayoutParams(-1, Glass.dp(48, d)))
            }
            children.forEach { folder ->
                folderList.addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Glass.pressFx()
                    setPadding(Glass.dp(12, d), 0, Glass.dp(8, d), 0)
                    addView(IconView(act, "folder", 18, pal.icon), LinearLayout.LayoutParams(
                        Glass.dp(24, d), Glass.dp(24, d)))
                    addView(TextView(act).apply {
                        text = folder.name
                        textSize = 15f
                        setTextColor(pal.textP)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f).also {
                        it.marginStart = Glass.dp(10, d)
                    })
                    addView(IconView(act, "chevron", 15, pal.icon))
                    setOnClickListener {
                        browsing = folder.id
                        renderLevel()
                    }
                }, LinearLayout.LayoutParams(-1, Glass.dp(52, d)))
            }
            if (children.isEmpty()) {
                folderList.addView(TextView(act).apply {
                    text = "没有子分类，可直接移到当前位置"
                    textSize = 13f
                    setTextColor(pal.textT)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, Glass.dp(72, d)))
            }
        }

        val dialog = AlertDialog.Builder(act)
            .setTitle("移动「$title」")
            .setView(body)
            .setPositiveButton("移到这里", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            Glass.styleDialog(dialog, d)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dest = browsing
                if (bookIds.isNotEmpty()) {
                    bookIds.forEach { db.moveBook(it, dest) }
                    selected.clear()
                    updateMultiCount()
                } else if (targetBook > 0) db.moveBook(targetBook, dest)
                else db.moveFolderTo(targetFolder, dest)
                dialog.dismiss()
                refresh()
            }
        }
        renderLevel()
        dialog.show()
    }

    fun handleResult(req: Int, data: Intent?) {
        if (req == REQ_CUSTOM_COVER) {
            val bookId = pendingCoverBookId
            pendingCoverBookId = -1L
            val selected = data?.data
            if (bookId > 0 && selected != null) importCustomCover(bookId, selected)
            return
        }
        if (req == REQ_EXPORT_NOTES) {
            val target = data?.data
            if (target != null) writeExportDocument(target) else pendingExportMarkdown = null
            return
        }
        val uri: Uri = data?.data ?: return
        if (req == Glass.REQ_BG) importBackground(uri)
    }

    private fun importCustomCover(bookId: Long, uri: Uri) {
        val book = db.getBook(bookId)
        if (book == null) {
            android.widget.Toast.makeText(act, "这本书已不在书架中", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(act, "正在处理封面…", android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val saved = CoverStore.saveCustom(act.applicationContext, bookId, uri)
            act.runOnUiThread {
                if (saved) {
                    refresh(etSearch.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                    android.widget.Toast.makeText(act, "《${book.title}》封面已更新", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(act, "无法读取这张图片，请选择 JPG、PNG 或 WebP", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "yeshu-cover-custom-$bookId").start()
    }

    /** 自定义背景：后台降采样解码 → 有损压缩写回 bg.img（避免备份膨胀与全屏大图 OOM）。 */
    private fun importBackground(uri: Uri) {
        Thread({
            val saved = try {
                saveBackground(uri)
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
            act.runOnUiThread {
                if (!isAttachedToWindow) return@runOnUiThread
                if (saved) loadBg()
                else android.widget.Toast.makeText(act, "背景导入失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, "yeshu-bg-import").start()
    }

    /**
     * 保存背景：先只读图片头计算 inSampleSize，再降采样解码并转 JPEG 落盘。
     * 旧格式的 bg.img 由 loadBg() 继续兼容读取。
     */
    private fun saveBackground(uri: Uri): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > BG_MAX_DIMENSION) sample *= 2
        val bm = act.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val f = File(act.filesDir, "bg.img")
        val tmp = File(act.filesDir, "bg.img.tmp")
        tmp.delete()
        val compressed = try {
            tmp.outputStream().buffered().use {
                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
            }
        } finally {
            bm.recycle()
        }
        if (!compressed || tmp.length() <= 0L) {
            tmp.delete()
            return false
        }
        if (f.exists() && !f.delete()) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** AI 全文摘要：子线程调用，结果存 notes 表并弹出展示 */
    private fun aiSummary(b: Book) {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            AlertDialog.Builder(act)
                .setTitle("未配置 AI")
                .setMessage("请先在书架「更多」→「AI 设置」填写接口地址、Key 和模型名。")
                .setPositiveButton("去设置") { _, _ -> (act as MainActivity).showSettings() }
                .setNegativeButton("取消", null)
                .show().also { Glass.styleDialog(it, density(act)) }
            return
        }
        if (aiBusy) return
        aiBusy = true
        val token = AiClient.CancelToken().also { aiToken = it }
        val pill = showAiProgress("生成中… 点按停止", token)
        pill.contentDescription = "正在生成《${b.title}》摘要，点按停止"
        Thread {
            var err: String? = null
            var reply = ""
            val streamed = StringBuilder()
            try {
                val f = File(act.filesDir, b.fileName)
                val text = if (b.format == "pdf") throw RuntimeException("PDF 文本提取将在后续版本支持")
                           else DocParser.parseText(f).fullText
                if (text.isBlank()) throw RuntimeException("文档没有可读文本")
                val hint = languageHint(text.take(2000))
                reply = AiClient.withCancellation(token) {
                    AiClient.chat(
                        cfg,
                        "你是专业的阅读助手。输出语言：${hint ?: "与书籍主要语言一致"}，输出使用简洁的结构化格式。",
                        "请为下面的内容生成摘要：先一句话概括，再用 3-6 个要点列出核心内容。\n\n【内容开始】\n" +
                            text.take(24000) + "\n【内容结束】",
                        onDelta = { delta ->
                            streamed.append(delta)
                            act.runOnUiThread {
                                if (aiToken !== token || !isAttachedToWindow) return@runOnUiThread
                                pill.text = streamed.toString().trim().takeLast(PROGRESS_TAIL_CHARS)
                            }
                        },
                        onRestart = {
                            // 断流重发：作废已上屏增量，避免「半截 + 全文」重复
                            streamed.setLength(0)
                            act.runOnUiThread {
                                if (aiToken === token && isAttachedToWindow) pill.text = "生成中… 点按停止"
                            }
                        },
                        timeoutMs = 120_000
                    )
                }
                // 先落库再回主线程：视图若已分离，结果也不该丢；取消则不写半截结果
                if (!token.isCancelled()) db.addNote(b.id, "summary", reply)
            } catch (t: Throwable) {
                // 统一走 userFacingError：错误文案与其他 AI 入口一致且不泄漏响应正文
                if (!token.isCancelled()) err = AiClient.userFacingError(t)
            }
            val e = err
            val cancelled = token.isCancelled()
            act.runOnUiThread {
                if (aiToken === token) aiToken = null
                aiBusy = false
                if (!isAttachedToWindow) return@runOnUiThread
                dismissAiProgress()
                when {
                    cancelled -> Unit
                    e != null -> showResult("AI 调用失败", e)
                    else -> showResult("《${b.title}》· AI 摘要", reply)
                }
            }
        }.start()
    }

    /**
     * 书籍主要语言的弱判断。DocumentAiService.detectLanguage 尚未落地，
     * 这里只按正文字符脚本兜底，避免英文/日文书拿到中文摘要与简介。
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

    private fun showResult(title: String, body: String) {
        val d = density(act)
        val scroll = ScrollView(act)
        val tv = TextView(act).apply {
            this.text = body
            textSize = 14f
            setTextColor(Color.parseColor("#222222"))
            setLineSpacing(Glass.dp(3, d).toFloat(), 1f)
            setPadding(Glass.dp(20, d), Glass.dp(14, d), Glass.dp(20, d), Glass.dp(20, d))
        }
        scroll.addView(tv)
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    /** 导出笔记为 Markdown，走系统分享面板（可存文件/发给自己） */
    private fun exportNotes(b: Book) {
        val notes = db.listNotes(b.id)
        if (notes.isEmpty()) {
            AlertDialog.Builder(act).setMessage("这本书还没有笔记，先在阅读器里用 AI 生成一些吧。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val md = StringBuilder("# 《${b.title}》 笔记导出\n\n")
        for (n in notes) {
            md.append("## 【${NoteKindLabels.label(n.kind)}】\n\n").append(n.content).append("\n\n---\n\n")
        }
        exportMarkdown("《${b.title}》笔记导出", "《${b.title}》笔记导出.md", md.toString())
    }

    /**
     * 分享 Markdown：文本走 ACTION_SEND，超过 [MAX_SHARE_TEXT_CHARS] 时改走
     * ACTION_CREATE_DOCUMENT 落盘。Intent extra 过大会触发 TransactionTooLargeException，
     * 这里既给出可见提示，也不再直接崩溃。
     */
    private fun exportMarkdown(subject: String, suggestedName: String, markdown: String) {
        if (markdown.length <= MAX_SHARE_TEXT_CHARS) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, markdown)
            }
            val started = runCatching { act.startActivity(Intent.createChooser(intent, "导出笔记")) }.isSuccess
            if (!started) {
                android.widget.Toast.makeText(act, "无法打开分享面板，请稍后重试", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        pendingExportMarkdown = markdown
        android.widget.Toast.makeText(act, "笔记内容较多，请选择保存位置", android.widget.Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        val started = try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(intent, REQ_EXPORT_NOTES)
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            pendingExportMarkdown = null
            android.widget.Toast.makeText(act, "无法打开文件保存面板", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 把待导出的 Markdown 写入用户选定的文档 URI。 */
    private fun writeExportDocument(uri: Uri) {
        val markdown = pendingExportMarkdown ?: return
        pendingExportMarkdown = null
        val written = runCatching {
            act.contentResolver.openOutputStream(uri, "wt")?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        android.widget.Toast.makeText(
            act,
            if (written) "笔记已导出" else "导出失败：无法写入所选位置",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun openReader(id: Long) {
        (act as MainActivity).openReader(id)
    }

    private companion object {
        const val REQ_CUSTOM_COVER = 104
        const val REQ_EXPORT_NOTES = 105
        // 背景图最大边长：全屏展示足够，同时限制备份体积与解码内存
        const val BG_MAX_DIMENSION = 1600
        // 搜索输入防抖时长（毫秒）
        const val SEARCH_DEBOUNCE_MS = 200L
        // 走 ACTION_SEND 的文本上限（UTF-16 下约 400 KB），超过则改为写入文件，
        // 避免 Intent extra 撞上 Binder 事务上限抛 TransactionTooLargeException
        const val MAX_SHARE_TEXT_CHARS = 200_000
        // 流式进度浮层里回显的尾部字符数：够看出在生成，又不至于撑爆浮层
        const val PROGRESS_TAIL_CHARS = 80
        // 封面解码线程池：避免在 refresh() 主线程解码大图造成掉帧/ANR
        val coverExecutor: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "yeshu-shelf-cover").apply { isDaemon = true }
        }
    }
}

/**
 * 旧版 View 页面的主题快照。
 * Compose 侧通过 DataStore 异步读取主题偏好，View 层需要同步取值；
 * 这里缓存最近一次读到的值（默认跟随系统），并在每次取值时后台刷新一次，
 * 让新建页面在下一次导航就能拿到最新偏好，无需改造 MainActivity/YeshuApp。
 */
object LegacyTheme {
    @Volatile private var cached: String? = null
    @Volatile private var refreshing = false

    private fun isDarkMode(context: Context, mode: String): Boolean = when (mode) {
        "dark" -> true
        "light" -> false
        else -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 同步返回当前是否深色；同时触发一次后台刷新，让后续页面取到最新偏好。 */
    fun isDark(context: Context): Boolean {
        val mode = cached
        refresh(context)
        return isDarkMode(context, mode ?: "system")
    }

    private fun refresh(context: Context) {
        if (refreshing) return
        refreshing = true
        val app = context.applicationContext
        Thread({
            cached = runCatching {
                runBlocking { UserPreferences(app).themeMode.first() }
            }.getOrDefault("system")
            refreshing = false
        }, "yeshu-theme").start()
    }
}

/**
 * 旧版 View 页面的浅色/深色调色板，与 Compose 主题偏好保持一致，
 * 避免在 Compose 与 View 页面之间导航时明暗表面来回跳变。
 */
class LegacyPalette private constructor(
    val dark: Boolean,
    val bg: Int,
    val bgGradientTop: Int,
    val surface: Int,
    val surface2: Int,
    val surface3: Int,
    val textP: Int,
    val textS: Int,
    val textT: Int,
    val icon: Int,
    val bar: Int,
    val bubble: Int,
    val bubbleText: Int,
    /** 底部弹层等遮罩色：深浅主题下都要压暗背景，不能共用同一个常量。 */
    val scrim: Int
) {
    companion object {
        private val DARK = LegacyPalette(
            dark = true,
            bg = T.bg,
            bgGradientTop = Color.parseColor("#10131C"),
            surface = T.surface,
            surface2 = T.surface2,
            surface3 = T.surface3,
            textP = T.textP,
            textS = T.textS,
            textT = T.textT,
            icon = Color.WHITE,
            bar = Color.parseColor("#161B26"),
            bubble = Color.argb(150, 34, 38, 48),
            bubbleText = Color.parseColor("#E8EAEE"),
            scrim = Color.argb(140, 0, 0, 0)
        )
        private val LIGHT = LegacyPalette(
            dark = false,
            bg = Color.parseColor("#F7F8FC"),
            bgGradientTop = Color.parseColor("#EEF1F8"),
            surface = Color.WHITE,
            surface2 = Color.parseColor("#EEF0FA"),
            surface3 = Color.parseColor("#DDE1F0"),
            textP = Color.parseColor("#171A2B"),
            textS = Color.argb(180, 23, 26, 43),
            textT = Color.argb(130, 23, 26, 43),
            icon = Color.parseColor("#171A2B"),
            bar = Color.WHITE,
            bubble = Color.parseColor("#EEF0FA"),
            bubbleText = Color.parseColor("#171A2B"),
            scrim = Color.argb(90, 0, 0, 0)
        )

        fun of(context: Context): LegacyPalette = if (LegacyTheme.isDark(context)) DARK else LIGHT
    }
}
