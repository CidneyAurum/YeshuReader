package app.yeshu.reader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 设置页：任意 OpenAI 兼容接口的 Base URL / API Key / 模型名 */
class SettingsView(private val act: Activity) : FrameLayout(act) {

    private val db = Db(act)
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText

    init {
        val d = density(act)

        setBackgroundColor(Color.parseColor("#22334A"))

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(-1, -1))
        applySystemBarInsets(col)

        // 顶栏
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Glass.dp(16, d), Glass.dp(14, d), Glass.dp(16, d), Glass.dp(8, d))
        }
        top.addView(FrameLayout(act).apply {
            background = Glass.iconBg()
            foreground = Glass.pressFx()
            layoutParams = LinearLayout.LayoutParams(Glass.dp(42, d), Glass.dp(42, d))
            addView(IconView(act, "back", 22), FrameLayout.LayoutParams(Glass.dp(24, d), Glass.dp(24, d), Gravity.CENTER))
            setOnClickListener { (act as MainActivity).showShelf() }
        })
        top.addView(TextView(act).apply {
            text = "AI 设置"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top, LayoutParams(-1, -2))

        val sc = ScrollView(act)
        col.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Glass.dp(18, d), Glass.dp(10, d), Glass.dp(18, d), Glass.dp(30, d))
        }
        sc.addView(box, LayoutParams(-1, -2))

        // 分组卡片容器：AI 服务 / 外观 / 数据
        fun section(title: String): LinearLayout {
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.darkCard()
                setPadding(Glass.dp(18, d), Glass.dp(16, d), Glass.dp(18, d), Glass.dp(18, d))
            }
            card.addView(TextView(act).apply {
                text = title
                textSize = 12f
                setTextColor(Color.argb(165, 255, 255, 255))
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.12f
            })
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Glass.dp(14, d)
            box.addView(card, lp)
            return card
        }
        val aiCard = section("AI 服务")
        val lookCard = section("外观")
        val dataCard = section("数据")

        fun cardLabel(host: LinearLayout, text: String) {
            host.addView(label(text, d))
        }

        cardLabel(aiCard, "接口地址（Base URL，OpenAI 兼容）")
        etUrl = aiCard.addView_ret(buildEdit(d, "例如 https://api.deepseek.com/v1")) as EditText

        // 常用服务商预设：一键填充地址+模型（Key 仍需自填）
        val presets = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun presetBtn(name: String, url: String, model: String): TextView = TextView(act).apply {
            text = name
            textSize = 12f
            setTextColor(Color.WHITE)
            background = Glass.pillBg(Color.argb(60, 255, 255, 255))
            setPadding(Glass.dp(14, d), Glass.dp(7, d), Glass.dp(14, d), Glass.dp(7, d))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Glass.dp(8, d)
            layoutParams = lp
            setOnClickListener {
                etUrl.setText(url)
                etModel.setText(model)
                android.widget.Toast.makeText(act, "已填入 $name 预设，请确认 Key", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        presets.addView(presetBtn("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"))
        presets.addView(presetBtn("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"))
        presets.addView(presetBtn("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"))
        presets.addView(presetBtn("Ollama", "http://10.0.2.2:11434/v1", "qwen2.5:7b"))
        val pp = LinearLayout.LayoutParams(-1, -2)
        pp.topMargin = Glass.dp(8, d)
        box.addView(presets, pp)

        box.addView(label("API Key", d))
        etKey = box.addView_ret(buildEdit(d, "API Key", password = true)) as EditText

        box.addView(label("模型名称", d))
        etModel = box.addView_ret(buildEdit(d, "例如 deepseek-chat / gpt-4o-mini / qwen-plus")) as EditText

        box.addView(TextView(act).apply {
            text = "兼容所有 OpenAI 格式接口：DeepSeek、Kimi、智谱、通义、OpenAI、本地 Ollama 等。\n本地服务请填 http://10.0.2.2:端口/v1（模拟器访问本机）。"
            textSize = 12f
            setTextColor(Color.argb(190, 255, 255, 255))
            setPadding(0, Glass.dp(16, d), 0, 0)
        })

        val save = TextView(act).apply {
            text = "保存设置"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Glass.darkCard()
            setPadding(0, Glass.dp(14, d), 0, Glass.dp(14, d))
            setOnClickListener { save() }
        }
        val sp = LinearLayout.LayoutParams(-1, -2)
        sp.topMargin = Glass.dp(24, d)
        box.addView(save, sp)

        // 测试连接：用当前输入直接发一条 1 token 请求
        val test = TextView(act).apply {
            text = "测试连接"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Glass.pillBg(Color.argb(90, 90, 140, 255))
            setPadding(0, Glass.dp(13, d), 0, Glass.dp(13, d))
            setPadding(0, Glass.dp(13, d), 0, Glass.dp(13, d))
            setOnClickListener { v ->
                val btn = v as TextView
                val url = etUrl.text.toString().trim()
                val key = etKey.text.toString().trim()
                val model = etModel.text.toString().trim()
                if (url.isEmpty() || model.isEmpty()) {
                    android.widget.Toast.makeText(act, "请先填接口地址和模型", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btn.text = "测试中…"
                btn.isEnabled = false
                Thread {
                    var msg: String
                    try {
                        val cfg = AiClient.Config(AiClient.normalizeBase(url), key, model)
                        val r = AiClient.chat(cfg, null, "回复OK两个字母即可", timeoutMs = 45_000)
                        msg = if (r.isNotBlank()) "✓ 连接成功：${r.take(24)}" else "✗ 返回为空"
                    } catch (e: Exception) {
                        val raw = (e.message ?: e.javaClass.simpleName)
                        msg = when {
                            raw.contains("timeout", true) || raw.contains("timed out", true) ->
                                "✗ 连接超时——检查网络/该中转站是否可达，或稍后再试"
                            raw.contains("Unable to resolve host", true) ->
                                "✗ 域名解析失败——检查地址拼写与网络"
                            else -> "✗ $raw".take(120)
                        }
                    }
                    act.runOnUiThread {
                        btn.text = "测试连接"
                        btn.isEnabled = true
                        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
        val sp2 = LinearLayout.LayoutParams(-1, -2)
        sp2.topMargin = Glass.dp(12, d)
        box.addView(test, sp2)

        // ---------- 书库备份 / 恢复 ----------
        box.addView(label("书库备份（书籍元数据与进度，不含文件本体）", d))

        // 夜间模式跟随系统
        val nightRow = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Glass.dp(8, d), 0, 0)
        }
        val nightLabel = TextView(act).apply {
            text = "夜间模式跟随系统"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        lateinit var nightToggle: TextView
        fun refreshNight() {
            val on = Db(act).getSetting("night_follow_sys") == "1"
            nightToggle.text = if (on) "开" else "关"
            nightToggle.background = Glass.pillBg(if (on) Color.argb(150, 90, 170, 110) else Color.argb(60, 255, 255, 255))
        }
        nightToggle = TextView(act).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(Glass.dp(18, d), Glass.dp(7, d), Glass.dp(18, d), Glass.dp(7, d))
            setOnClickListener {
                val db2 = Db(act)
                db2.setSetting("night_follow_sys", if (db2.getSetting("night_follow_sys") == "1") "0" else "1")
                refreshNight()
                android.widget.Toast.makeText(act, "下次打开书籍生效", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        refreshNight()
        nightRow.addView(nightLabel)
        nightRow.addView(nightToggle)
        box.addView(nightRow)
        val bkRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun bigBtn(label: String, tint: Int, onClick: () -> Unit): TextView = TextView(act).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Glass.pillBg(tint)
            setPadding(0, Glass.dp(11, d), 0, Glass.dp(11, d))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = Glass.dp(10, d) }
            setOnClickListener { onClick() }
        }
        bkRow.addView(bigBtn("导出备份", Color.argb(90, 80, 130, 220)) { exportBackup() })
        bkRow.addView(bigBtn("导入恢复", Color.argb(90, 90, 160, 110)) { importBackup() })
        val bp = LinearLayout.LayoutParams(-1, -2)
        bp.topMargin = Glass.dp(8, d)
        box.addView(bkRow, bp)

        load()
    }

    /** 备份：books+folders 元数据 JSON，走 SAF 让用户选保存位置 */
    private fun exportBackup() {
        try {
            val db = Db(act)
            val root = org.json.JSONObject()
                .put("app", "shuge")
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
            val folders = org.json.JSONArray()
            db.allFolders().forEach { f ->
                folders.put(org.json.JSONObject()
                    .put("id", f.id).put("name", f.name).put("parentId", f.parentId))
            }
            root.put("folders", folders)
            val books = org.json.JSONArray()
            var dirOk = 0
            db.listBooks().forEach { b ->
                if (java.io.File(act.filesDir, b.fileName).exists()) {
                    dirOk++
                    books.put(org.json.JSONObject()
                        .put("title", b.title).put("fileName", b.fileName)
                        .put("format", b.format).put("sizeBytes", b.sizeBytes)
                        .put("progress", b.progress.toDouble())
                        .put("addedAt", b.addedAt).put("lastReadAt", b.lastReadAt)
                        .put("folderId", b.folderId))
                }
            }
            root.put("books", books)
            pendingBackup = root.toString()
            // SAF 创建文档：用户选目录与文件名
            val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_TITLE, "shuge_backup_${System.currentTimeMillis()}.json")
            }
            act.startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            android.widget.Toast.makeText(act, "备份失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private var pendingBackup: String? = null

    fun handleExportResult(uri: android.net.Uri): Boolean {
        val payload = pendingBackup ?: return false
        return try {
            act.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            android.widget.Toast.makeText(act, "备份已保存 ✓（${payload.length} 字节）", android.widget.Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            android.widget.Toast.makeText(act, "写文件失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
            false
        } finally {
            pendingBackup = null
        }
    }

    companion object {
        const val REQ_RESTORE = 4701
        const val REQ_EXPORT = 4702
    }

    /** 恢复：从用户选择的 JSON 读回；按标题+大小去重，文件夹按名字重建映射 */
    fun importBackup() {
        val i = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        i.type = "*/*"
        act.startActivityForResult(i, REQ_RESTORE)
    }

    fun handleRestoreResult(uri: android.net.Uri) {
        try {
            val text = act.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            val root = org.json.JSONObject(text)
            if (root.optString("app") != "shuge") throw RuntimeException("不是书阁备份文件")
            val db = Db(act)
            val existing = db.allFolders().associateBy { it.name }
            val idMap = mutableMapOf<Long, Long>()
            val folders = root.optJSONArray("folders") ?: org.json.JSONArray()
            for (k in 0 until folders.length()) {
                val fo = folders.getJSONObject(k)
                val oldId = fo.getLong("id")
                val name = fo.getString("name")
                val mapped = existing[name]?.id ?: run {
                    // 父级映射后插入
                    val newParent = idMap[fo.getLong("parentId")] ?: 0L
                    db.addFolder(name, newParent)
                }
                idMap[oldId] = mapped
            }
            var added = 0
            var skipped = 0
            val books = root.optJSONArray("books") ?: org.json.JSONArray()
            for (k in 0 until books.length()) {
                val bo = books.getJSONObject(k)
                val title = bo.getString("title")
                val size = bo.getLong("sizeBytes")
                if (db.findBook(title, size) != null) { skipped++; continue }
                val fileName = bo.getString("fileName")
                if (!java.io.File(act.filesDir, fileName).exists()) { skipped++; continue } // 无文件本体跳过
                val newFolder = idMap[bo.getLong("folderId")] ?: 0L
                db.restoreBook(
                    title, fileName, bo.getString("format"), size,
                    bo.getDouble("progress").toFloat(),
                    bo.getLong("addedAt"), bo.getLong("lastReadAt"), newFolder
                )
                added++
            }
            android.app.AlertDialog.Builder(act)
                .setTitle("恢复完成")
                .setMessage("新增 $added 本，跳过 $skipped 本（已存在或文件缺失）。")
                .setPositiveButton("好") { _, _ -> (act as MainActivity).showShelf() }
                .show().also { Glass.styleDialog(it, density(act)) }
        } catch (e: Exception) {
            android.widget.Toast.makeText(act, "恢复失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun label(text: String, d: Float): TextView = TextView(act).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(0, Glass.dp(16, d), 0, Glass.dp(6, d))
    }

    private fun buildEdit(d: Float, hint: String, password: Boolean = false): EditText =
        EditText(act).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            background = Glass.pillBg()
            setPadding(Glass.dp(18, d), Glass.dp(12, d), Glass.dp(18, d), Glass.dp(12, d))
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

    // LinearLayout.addView 返回值不便链式，这里做个扩展收口
    private fun LinearLayout.addView_ret(v: android.view.View): android.view.View {
        addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun load() {
        etUrl.setText(db.getSetting("ai_base_url") ?: "")
        etKey.setText(db.getAiKey())
        etModel.setText(db.getSetting("ai_model") ?: "")
    }

    private fun save() {
        db.setSetting("ai_base_url", etUrl.text.toString().trim())
        db.setAiKey(etKey.text.toString())
        db.setSetting("ai_model", etModel.text.toString().trim())
        Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show()
    }
}
