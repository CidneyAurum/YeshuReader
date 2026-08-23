package app.yeshu.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.yeshu.reader.parse.DocParser
import java.io.File

class ShelfView(private val act: Activity) : FrameLayout(act) {

    private val db = Db(act)
    private lateinit var listBox: LinearLayout
    private var bgHost: FrameLayout? = null
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

    init {
        val d = density(act)

        // 背景：近黑纯色 + 极微弱深蓝渐变（自定义 bg.img 存在时仍尊重用户选择）
        val root = FrameLayout(act).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#10131C"), T.bg, T.bg)
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
            setTextColor(T.textP)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        titleCol.addView(TextView(act).apply {
            text = "SHUGE READER"
            textSize = 8.5f
            setTextColor(T.textT)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.24f
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.topMargin = Glass.dp(3, d)
            layoutParams = lp
        })
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        fun topBtn(icon: String, filled: Boolean, onClick: () -> Unit): FrameLayout = FrameLayout(act).apply {
            background = if (filled) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(T.accent)
            } else Glass.iconBg()
            foreground = Glass.pressFx()
            val lp = LinearLayout.LayoutParams(Glass.dp(44, d), Glass.dp(44, d))
            lp.marginStart = Glass.dp(12, d)
            layoutParams = lp
            addView(IconView(act, icon, 21), FrameLayout.LayoutParams(Glass.dp(22, d), Glass.dp(22, d), Gravity.CENTER))
            setOnClickListener { onClick() }
        }
        // 主操作：添加（导入/新建 bottom sheet）；次操作：更多（文件夹/批量/背景/设置）
        top.addView(topBtn("plus", true) { showAddSheet() })
        top.addView(topBtn("more", false) { showMoreSheet() })
        content.addView(top)

        // ---- 搜索框：48dp、#1C1C1E 实底、圆角16、内置放大镜与清除/取消 ----
        val searchWrap = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(T.rCard, d).toFloat()
                setColor(T.surface)
            }
            setPadding(Glass.dp(14, d), 0, Glass.dp(6, d), 0)
        }
        val swp = LinearLayout.LayoutParams(-1, Glass.dp(48, d))
        swp.setMargins(Glass.dp(T.pagePad, d), Glass.dp(10, d), Glass.dp(T.pagePad, d), 0)
        searchWrap.layoutParams = swp
        searchWrap.addView(IconView(act, "search", 18))
        etSearch = android.widget.EditText(act).apply {
            hint = "搜索书名、作者或文件夹"
            textSize = 15f
            setTextColor(T.textP)
            setHintTextColor(T.textT)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(Glass.dp(10, d), 0, Glass.dp(4, d), 0)
            setSingleLine(true)
        }
        searchWrap.addView(etSearch, LinearLayout.LayoutParams(0, -2, 1f))
        val btnClear = FrameLayout(act).apply {
            visibility = View.GONE
            addView(IconView(act, "close", 14), FrameLayout.LayoutParams(Glass.dp(16, d), Glass.dp(16, d), Gravity.CENTER))
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
                refresh(s?.toString()?.trim()?.takeIf { it.isNotEmpty() })
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
            setTextColor(T.textS)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        metaRow.addView(statTv)
        sortBtn = TextView(act).apply {
            textSize = 12f
            setTextColor(T.textS)
            background = Glass.pillBg(Color.argb(36, 255, 255, 255))
            setPadding(Glass.dp(10, d), Glass.dp(5, d), Glass.dp(10, d), Glass.dp(5, d))
            setOnClickListener {
                val cur = db.getSetting("shelf_sort") ?: "recent"
                db.setSetting("shelf_sort", when (cur) { "recent" -> "title"; "title" -> "added"; else -> "recent" })
                refresh()
            }
        }
        metaRow.addView(sortBtn)
        content.addView(metaRow)

        // segmented control：列表 | 宫格（双状态选中态）
        segWrap = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                cornerRadius = Glass.dp(12, d).toFloat()
                setColor(T.surface2)
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
                tv.setTextColor(if (on) T.textP else T.textS)
                tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (on) GradientDrawable().apply {
                    cornerRadius = Glass.dp(9, d).toFloat()
                    setColor(T.surface3)
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
        BottomSheet(act, "添加到页枢")
            .item("book", "导入本地文件") {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "*/*"
                act.startActivityForResult(i, Glass.REQ_BOOK)
            }
            .item("folder", "新建文件夹") { newFolderDialog() }
            .show()
    }

    /** 更多 sheet：AI 推荐 / 阅读统计 / 批量管理 / 更换背景 / 设置 */
    private fun showMoreSheet() {
        BottomSheet(act, "更多")
            .item("bulb", "✨ AI 推荐下一本") { aiRecommend() }
            .item("sort", "阅读统计") { (act as MainActivity).showStats() }
            .item("check", "批量管理") { toggleMultiMode() }
            .item("book", "回收站 (${db.listDeletedBooks().size})") { showRecycleBin() }
            .item("palette", "更换背景") {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                act.startActivityForResult(i, Glass.REQ_BG)
            }
            .item("sliders", "设置") { (act as MainActivity).showSettings() }
            .show()
    }

    /** 通用 AI 任务执行：配置检查 + 进度框 + 子线程 + UI 回调 */
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
        val pd = android.app.ProgressDialog.show(act, "AI", loading, true, false)
        Thread {
            var err: String? = null
            var reply = ""
            try { reply = AiClient.chat(cfg, promptSys, buildPrompt()) } catch (t: Throwable) { err = t.message ?: t.toString() }
            val e = err
            act.runOnUiThread {
                try { pd.dismiss() } catch (ex: Exception) {}
                if (e != null) showResult("AI 调用失败", e) else onDone(reply)
            }
        }.start()
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
                    showResult("✨ AI 推荐", reply)
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
                        CoverStore.file(act, book.id).delete()
                        db.purgeBook(book.id)
                        refresh()
                    }
                    .setNeutralButton("取消", null)
                    .show().also { Glass.styleDialog(it, density(act)) }
            }
            .setNegativeButton("关闭", null)
            .show().also { Glass.styleDialog(it, density(act)) }
    }

    private fun loadBg() {
        val f = File(act.filesDir, "bg.img")
        if (f.exists()) {
            val bm = BitmapFactory.decodeFile(f.absolutePath)
            if (bm != null) {
                bgHost?.let { host ->
                    val iv = ImageView(act).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bm)
                    }
                    Glass.blur(iv)
                    host.addView(iv, 0, LayoutParams(-1, -1))
                }
            }
        }
    }

    fun refresh(query: String? = null) {
        val d = density(act)
        listBox.removeAllViews()
        val searching = !query.isNullOrBlank()
        val rawBooks = if (searching) db.searchBooks(query) else db.listBooks(curFolder)
        val subs = if (searching) emptyList() else db.listFolders(curFolder)

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
        val books: List<Book> = when (sortMode) {
            "title" -> filtered.sortedBy { it.title }
            "added" -> filtered.sortedByDescending { it.addedAt }
            else -> filtered.sortedByDescending { it.lastReadAt }
        }

        // 筛选 chips（非搜索时显示）
        if (!searching && filterRow != null && filterHost != null) {
            filterRow!!.removeAllViews()
            listOf("全部" to "all", "收藏" to "favorite", "在读" to "reading", "未读" to "unread", "读完" to "done").forEach { (label, key) ->
                val active = filter == key
                val c = TextView(act).apply {
                    text = label; textSize = 12f
                    setTextColor(if (active) Color.WHITE else T.textS)
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = Glass.dp(999, d).toFloat()
                        setColor(if (active) T.accent else T.surface2)
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
        val sortLabel = when (sortMode) { "title" -> "标题"; "added" -> "加入"; else -> "最近" }
        statTv.text = buildString {
            append("${books.size} 本书")
            if (subs.isNotEmpty()) append(" · ${subs.size} 个文件夹")
            if (!searching && readingN > 0) append(" · 在读 $readingN")
            if (!searching && finishedN > 0) append(" · 已读完 $finishedN")
        }
        sortBtn.text = "排序：$sortLabel"
        segWrap.visibility = if (searching) View.GONE else View.VISIBLE

        // 面包屑导航行（子目录内显示）
        if (curFolder != 0L && !searching) {
            val path = db.folderPath(curFolder).joinToString(" / ") { it.name }
            val crumb = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Glass.pillBg(Color.argb(70, 255, 255, 255))
                setPadding(Glass.dp(14, d), Glass.dp(8, d), Glass.dp(16, d), Glass.dp(8, d))
                addView(TextView(act).apply {
                    text = "‹ 上级"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(act).apply {
                    text = "  $path"
                    textSize = 13f
                    setTextColor(Color.argb(220, 255, 255, 255))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { curFolder = db.getFolder(curFolder)?.parentId ?: 0L; refresh() }
            }
            val cp0 = LinearLayout.LayoutParams(-1, -2)
            cp0.setMargins(0, Glass.dp(4, d), 0, 0)
            listBox.addView(crumb, cp0)
        }

        // 「最近在读」横滑条：仅根目录非搜索时展示
        if (curFolder == 0L && !searching) {
            val recent = db.listBooks().filter { it.progress > 0.005f }
                .sortedByDescending { it.lastReadAt }
                .take(6)
            if (recent.isNotEmpty()) {
                val sec = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.setMargins(Glass.dp(2, d), Glass.dp(14, d), 0, 0)
                    layoutParams = lp
                    addView(TextView(act).apply {
                        text = "最近在读"
                        textSize = 13f
                        setTextColor(Color.WHITE)
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
                                                val bm = CoverStore.load(act, rb.id)
                                                if (bm != null) setImageBitmap(bm)
                                                else setImageBitmap(BookCover.placeholder(rb.title, rb.format, 160, 220))
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
                                        setTextColor(Color.WHITE)
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
            val childBooks = db.listBooks(f.id).size
            val childFolders = db.listFolders(f.id).size
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(T.rCard, d).toFloat()
                    setColor(T.surface)
                }
                setPadding(Glass.dp(16, d), Glass.dp(12, d), Glass.dp(16, d), Glass.dp(12, d))
                addView(FrameLayout(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(26, 255, 255, 255))
                    }
                    foreground = null
                    addView(IconView(act, "folder", 20), FrameLayout.LayoutParams(
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
                        setTextColor(T.textP)
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 1
                    })
                    addView(TextView(act).apply {
                        text = buildString {
                            append("$childBooks 本书")
                            if (childFolders > 0) append(" · $childFolders 个文件夹")
                        }
                        textSize = 12f
                        setTextColor(T.textS)
                    }, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = Glass.dp(3, d) })
                })
                addView(IconView(act, "chevron", 16))
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
                setOnClickListener { curFolder = f.id; refresh() }
                setOnLongClickListener {
                    val options = arrayOf("打开", "重命名", "移动到…", "删除")
                    AlertDialog.Builder(act)
                        .setTitle("📁 ${f.name}")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> { curFolder = f.id; refresh() }
                                1 -> renameFolderDialog(f)
                                2 -> moveDialog(targetFolder = f.id)
                                3 -> AlertDialog.Builder(act)
                                    .setTitle("删除文件夹")
                                    .setMessage("删除「${f.name}」？其中的书和子文件夹会移到上一级。")
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
                    searching -> Glass.emptyState(act, "🔍", "没有匹配的书", "搜索会查找所有文件夹中的书")
                    else -> Glass.emptyState(act, "📚", "这里还没有书", "点右上 + 导入书籍\n长按书籍可移动到文件夹")
                }
                val hp = LinearLayout.LayoutParams(-1, -2)
                hp.setMargins(0, Glass.dp(60, d), 0, 0)
                listBox.addView(hint, hp)
                // 搜索无结果 → AI 找书兜底入口
                if (searching && query != null) {
                    val aiRow = TextView(act).apply {
                        text = "✨ 没找到？让 AI 帮你找「${query.take(12)}」"
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
            val kb = if (b.sizeBytes < 1024) "${b.sizeBytes} B"
                     else (b.sizeBytes / 1024).toString() + " KB"
            val pct = ((b.progress * 100).toInt()).toString() + "%"

            // 左封面：56x84dp，真封面或程序化占位
            val tw = Glass.dp(T.coverWList, d)
            val th = Glass.dp(T.coverHList, d)
            val thumb: android.view.View = if (CoverStore.file(act, b.id).exists()) {
                ImageView(act).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val bm = CoverStore.load(act, b.id)
                    if (bm != null) setImageBitmap(bm)
                    else setImageBitmap(BookCover.placeholder(b.title, b.format, 160, 240))
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
                    setColor(T.surface)
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
                        setTextColor(T.textP)
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 2
                    }
                    addView(t1, LinearLayout.LayoutParams(-2, -2))
                    // 元信息：格式胶囊（低饱和）+ 大小
                    val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    meta.addView(TextView(context).apply {
                        text = b.format.uppercase().ifEmpty { "TXT" }
                        textSize = 9f
                        setTextColor(T.textS)
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
                            if (b.folderId == 0L) "" else " · " + (db.getFolder(b.folderId)?.name ?: "")
                        } else ""
                        textSize = 11f
                        setTextColor(T.textT)
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
                addView(IconView(act, "chevron", 14), LinearLayout.LayoutParams(-2, -2).also {
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
        val thumb: android.view.View = if (CoverStore.file(act, b.id).exists()) {
            ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bm = CoverStore.load(act, b.id)
                if (bm != null) setImageBitmap(bm)
                else setImageBitmap(BookCover.placeholder(b.title, b.format, 320, 480))
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
                setTextColor(T.textP)
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = Glass.dp(8, d) })
            addView(TextView(act).apply {
                text = b.format.uppercase().ifEmpty { "TXT" } + " · " + pct
                textSize = 11f
                setTextColor(T.textS)
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
        val folderName = if (b.folderId == 0L) "📂 根目录" else db.getFolder(b.folderId)?.name ?: "?"
        val lastRead = if (b.lastReadAt > 0) fmt.format(java.util.Date(b.lastReadAt)) else "还没翻开过"
        val readMs = try { db.totalReadMs(b.id) } catch (e: Exception) { 0L }
        val readLabel = when {
            readMs < 60_000 -> if (readMs <= 0) "暂无记录" else "不足 1 分钟"
            else -> "${readMs / 3600000} 小时 ${(readMs % 3600000) / 60000} 分钟".trim().let { if (it.startsWith("0 ")) it.substringAfter(" ") else it }
        }
        // 已有 AI 简介则展示
        val intro = db.listNotes(b.id, "intro").lastOrNull()?.content
        val introText = intro ?: "还没有简介，点「AI 简介」一键生成"
        val body = TextView(act).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(Glass.dp(4, d).toFloat(), 1f)
            setPadding(Glass.dp(24, d), Glass.dp(18, d), Glass.dp(24, d), Glass.dp(8, d))
            text = "📖 $introText\n\n" +
                   "作者：${b.author.ifBlank { "未填写" }}    收藏：${if (b.favorite) "是" else "否"}\n" +
                   "标签：${b.tags.ifBlank { "未填写" }}\n" +
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
            .setNeutralButton(if (intro == null) "✨ AI 简介" else "✨ 换个简介") { _, _ -> aiIntro(b) }
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
            "你是专业的图书编辑。用简体中文回答。",
            buildPrompt = {
                val f = File(act.filesDir, b.fileName)
                val head = try {
                    if (b.format == "pdf") "(PDF 文档)" else DocParser.parseText(f).fullText.take(6000)
                } catch (e: Exception) { "(无法提取文本)" }
                "请为这本书写一段吸引人但不剧透的简介（80-120 字），结尾用【】标注类型标签（如【科幻·悬疑】）。\n\n" +
                    "书名：《${b.title}》\n【开头内容】\n$head"
            },
            onDone = { reply ->
                db.addNote(b.id, "intro", reply)
                android.widget.Toast.makeText(act, "简介已生成 ✓", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
                bookDetail(b)
            })
    }

    /** 书籍长按操作菜单（列表/网格共用） */
    private fun bookLongPress(b: Book): Boolean {
        val options = arrayOf(
            "打开", "详情", if (b.favorite) "取消收藏" else "收藏",
            "编辑作者与标签", "AI 摘要", "笔记", "移动到…", "导出笔记", "删除"
        )
        AlertDialog.Builder(act)
            .setTitle(b.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openReader(b.id)
                    1 -> bookDetail(b)
                    2 -> { db.setFavorite(b.id, !b.favorite); refresh() }
                    3 -> editMetadata(b)
                    4 -> aiSummary(b)
                    5 -> (act as MainActivity).showNotes(b.id)
                    6 -> moveDialog(targetBook = b.id)
                    7 -> exportNotes(b)
                    8 -> AlertDialog.Builder(act)
                        .setTitle("删除")
                        .setMessage("将《" + b.title + "》移入回收站？原文件会保留，可恢复。")
                        .setPositiveButton("移入回收站") { _, _ ->
                            db.deleteBook(b.id)
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show().also { Glass.styleDialog(it, density(act)) }
                }
            }
            .show().also { Glass.styleDialog(it, density(act)) }
        return true
    }

    /** 新建文件夹（建在当前目录） */
    private fun newFolderDialog() {
        val d = density(act)
        val input = EditText(act).apply {
            hint = "文件夹名称"
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
        AlertDialog.Builder(act)
            .setTitle("新建文件夹")
            .setView(wrap)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { db.addFolder(name, curFolder); refresh() }
            }
            .setNegativeButton("取消", null)
            .show().also {
                Glass.styleDialog(it, d)
                it.window?.let { w -> w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
            }
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
        AlertDialog.Builder(act)
            .setTitle("重命名文件夹")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { db.renameFolder(f.id, name); refresh() }
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, d) }
    }

    /**
     * 移动对话框：树形缩进列出所有可选目标。
     * targetBook 非空 = 移动书；targetFolder 非空 = 移动文件夹（跳过自身及子孙防环）。
     */
    private fun moveDialog(targetBook: Long = -1, targetFolder: Long = -1, bookIds: Set<Long> = emptySet()) {
        val d = density(act)
        data class Node(val id: Long, val label: String)
        val nodes = mutableListOf(Node(0L, "📂 根目录"))
        fun walk(parent: Long, depth: Int) {
            for (f in db.listFolders(parent)) {
                if (targetFolder == f.id) continue          // 自身不可选
                if (targetFolder > 0 && db.isSelfOrDescendant(targetFolder, f.id)) continue // 子孙防环
                nodes.add(Node(f.id, "　".repeat(depth) + "📁 " + f.name))
                walk(f.id, depth + 1)
            }
        }
        walk(0L, 0)

        val title = when {
            bookIds.isNotEmpty() -> "${bookIds.size} 本书"
            targetBook > 0 -> db.getBook(targetBook)?.title ?: return
            else -> "📁 " + (db.getFolder(targetFolder)?.name ?: "")
        }

        AlertDialog.Builder(act)
            .setTitle("移动「$title」到…")
            .setItems(nodes.map { it.label }.toTypedArray()) { _, which ->
                val dest = nodes[which].id
                if (bookIds.isNotEmpty()) {
                    bookIds.forEach { db.moveBook(it, dest) }
                    selected.clear()
                    updateMultiCount()
                }
                else if (targetBook > 0) db.moveBook(targetBook, dest)
                else db.moveFolderTo(targetFolder, dest)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show().also { Glass.styleDialog(it, d) }
    }

    fun handleResult(req: Int, data: Intent?) {
        val uri: Uri = data?.data ?: return
        if (req == Glass.REQ_BG) importBackground(uri)
        else if (req == Glass.REQ_BOOK) importBook(uri)
    }

    private fun importBackground(uri: Uri) {
        val f = File(act.filesDir, "bg.img")
        try {
            val input = act.contentResolver.openInputStream(uri)
            if (input != null) {
                val output = f.outputStream()
                input.copyTo(output)
                output.close()
                input.close()
                loadBg()
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(act, "背景导入失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun importBook(uri: Uri) {
        var name = queryName(uri)
        if (name == null || name.isEmpty()) name = "book_" + System.currentTimeMillis().toString() + ".txt"
        val fmt = DocParser.detect(name)
        val title = if (fmt.isNotEmpty() && '.' in name)
            name.substringBeforeLast('.')
        else name
        val dest = File(act.filesDir, System.currentTimeMillis().toString() + "_" + name)
        // 后台复制+入库，避免大文件阻塞主线程
        Thread {
            var size = 0L
            var ok = false
            try {
                act.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> size = input.copyTo(output) }
                }
                ok = size > 0
            } catch (_: Exception) { }
            val id = if (ok) db.insertBook(title, dest.name, fmt.ifEmpty { "txt" }, size) else -1L
            if (id > 0) {
                CoverStore.generateAsync(act, id, dest, fmt.ifEmpty { "txt" })
                act.runOnUiThread {
                    android.widget.Toast.makeText(
                        act,
                        if (fmt.isEmpty()) "已导入《$title》（未知格式按 TXT 处理）" else "已导入《$title》",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            } else {
                dest.delete()
                act.runOnUiThread {
                    android.widget.Toast.makeText(act, "导入失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** AI 全文摘要：子线程调用，结果存 notes 表并弹出展示 */
    private fun aiSummary(b: Book) {
        val cfg = AiClient.config(db)
        if (!AiClient.isReady(cfg)) {
            AlertDialog.Builder(act)
                .setTitle("未配置 AI")
                .setMessage("请先点击右上角「AI 设置」填写接口地址、Key 和模型名。")
                .setPositiveButton("去设置") { _, _ -> (act as MainActivity).showSettings() }
                .setNegativeButton("取消", null)
                .show().also { Glass.styleDialog(it, density(act)) }
            return
        }
        val pd = android.app.ProgressDialog.show(act, "AI 摘要", "正在生成，请稍候…", true, false)
        Thread {
            var err: String? = null
            var reply = ""
            try {
                val f = File(act.filesDir, b.fileName)
                val text = if (b.format == "pdf") throw RuntimeException("PDF 文本提取将在后续版本支持")
                           else DocParser.parseText(f).fullText
                if (text.isBlank()) throw RuntimeException("文档没有可读文本")
                reply = AiClient.chat(
                    cfg,
                    "你是专业的中文阅读助手。用简体中文回答，输出使用简洁的结构化格式。",
                    "请为下面的内容生成摘要：先一句话概括，再用 3-6 个要点列出核心内容。\n\n【内容开始】\n" +
                        text.take(24000) + "\n【内容结束】"
                )
                db.addNote(b.id, "summary", reply)
            } catch (t: Throwable) {
                err = t.message ?: t.toString()
            }
            val e = err
            act.runOnUiThread {
                try { pd.dismiss() } catch (ex: Exception) {}
                if (e != null) showResult("AI 调用失败", e)
                else showResult("《${b.title}》· AI 摘要", reply)
            }
        }.start()
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
        val label = mapOf("summary" to "摘要", "ask" to "问答", "quiz" to "自测")
        val md = StringBuilder("# 《${b.title}》 笔记导出\n\n")
        for (n in notes) {
            md.append("## 【${label[n.kind] ?: n.kind}】\n\n").append(n.content).append("\n\n---\n\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "《${b.title}》笔记导出")
            putExtra(Intent.EXTRA_TEXT, md.toString())
        }
        act.startActivity(Intent.createChooser(intent, "导出笔记"))
    }

    private fun queryName(uri: Uri): String? {
        val c = act.contentResolver.query(uri, null, null, null, null) ?: return null
        c.use { cc ->
            val idx = cc.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cc.moveToFirst()) return cc.getString(idx)
        }
        return null
    }

    private fun openReader(id: Long) {
        (act as MainActivity).openReader(id)
    }
}
