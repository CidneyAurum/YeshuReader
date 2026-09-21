package app.yeshu.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** 让根布局避开系统栏：顶部状态栏/刘海用 padding 下移内容；底部手势导航区用 margin 抬高 content（背景保持全屏 edge-to-edge） */
fun View.applySystemBarInsets(content: View? = null): View {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.navigationBars()
        )
        v.setPadding(bars.left, bars.top, bars.right, 0)
        if (content != null) {
            val lp = content.layoutParams
            if (lp is android.view.ViewGroup.MarginLayoutParams) {
                if (lp.bottomMargin != bars.bottom) {
                    lp.bottomMargin = bars.bottom
                    content.layoutParams = lp
                }
            }
        }
        insets
    }
    ViewCompat.requestApplyInsets(this)
    return this
}

/** 玻璃拟态公共组件：全局背景 + 毛玻璃卡片 */
object Glass {
    const val REQ_BG = 101

    /** iOS 毛玻璃卡片：半透明白 + 大圆角 + 细白描边 */
    fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(96, 255, 255, 255))
        cornerRadius = 40f
        setStroke(1, Color.argb(50, 255, 255, 255))
    }

    fun darkCard(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(120, 18, 20, 26))
        cornerRadius = 40f
        setStroke(1, Color.argb(45, 255, 255, 255))
    }

    /** 圆形图标按钮底（iOS 圆形 chip） */
    fun iconBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(80, 255, 255, 255))
        setStroke(2, Color.argb(60, 255, 255, 255))
    }

    /** 按压反馈：按下时白色蒙层变亮 */
    fun pressFx(): android.graphics.drawable.Drawable {
        val normal = Color.argb(0, 255, 255, 255)
        val pressed = Color.argb(60, 255, 255, 255)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply { setColor(pressed); cornerRadius = T.rPill.toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(normal) })
        }
    }

    /** 胶囊形底（搜索框/主按钮） */
    fun pillBg(tint: Int = Color.argb(100, 255, 255, 255)): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = T.rPill.toFloat()
            setColor(tint)
            setStroke(2, Color.argb(55, 255, 255, 255))
        }

    /**
     * 对话框玻璃化：圆角浅底 + 无系统直角框，show() 之后调用。
     * 底色跟随当前主题（夜间阅读时不能再整屏闪白），palette 默认从对话框上下文解析，
     * 因此 ReaderView/ShelfView 等旧调用点无需改动。
     */
    fun styleDialog(
        dlg: android.app.Dialog,
        density: Float,
        palette: LegacyPalette = LegacyPalette.of(dlg.context)
    ) {
        dlg.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(if (palette.dark) palette.surface else Color.argb(242, 249, 250, 253))
                cornerRadius = dp(26, density).toFloat()
            }
        )
        // 平台浅色主题给对话框内部文字的是深色，深底上会读不出来，需显式改成调色板颜色
        if (palette.dark) {
            (dlg.window?.decorView as? android.view.ViewGroup)?.let { recolorDialogText(it, palette) }
        }
    }

    /** 深色对话框：递归把标题/正文/按钮/输入框的文字改成浅色（EditText 连 hint 一起改） */
    private fun recolorDialogText(v: View, palette: LegacyPalette) {
        when (v) {
            is android.widget.EditText -> {
                v.setTextColor(palette.textP)
                v.setHintTextColor(palette.textT)
            }
            is android.widget.Button -> v.setTextColor(Color.parseColor("#8FB6FF"))
            is android.widget.TextView -> v.setTextColor(palette.textP)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) recolorDialogText(v.getChildAt(i), palette)
        }
    }

    /** iOS 风格空状态卡：大 emoji + 主文案 + 副文案（跟随主题的表面/文字色） */
    fun emptyState(
        activity: Activity,
        emoji: String,
        title: String,
        sub: String,
        palette: LegacyPalette = LegacyPalette.of(activity)
    ): android.view.View {
        val d = density(activity)
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16 * 3, d).toFloat()
                setColor(if (palette.dark) Color.parseColor("#1C1C1E") else palette.surface)
            }
            setPadding(dp(28, d), dp(34, d), dp(28, d), dp(34, d))
        }
        box.addView(android.widget.TextView(activity).apply {
            text = emoji
            textSize = 44f
            gravity = Gravity.CENTER
        })
        box.addView(android.widget.TextView(activity).apply {
            text = title
            textSize = 17f
            setTextColor(if (palette.dark) Color.parseColor("#F5F5F7") else palette.textP)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12, d), 0, 0)
        })
        box.addView(android.widget.TextView(activity).apply {
            text = sub
            textSize = 13f
            setTextColor(if (palette.dark) Color.argb(158, 245, 245, 247) else palette.textS)
            gravity = Gravity.CENTER
            setPadding(0, dp(6, d), 0, 0)
        })
        return box
    }

    /** 细圆角阅读进度条：background.level 0..10000 控制填充比例 */
    fun progressTrack(activity: Activity): View {
        val dd = activity.resources.displayMetrics.density
        val r = dp(2, dd).toFloat()
        val track = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.argb(45, 255, 255, 255))
        }
        val fill = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.argb(235, 255, 255, 255))
        }
        val clip = android.graphics.drawable.ClipDrawable(
            fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        return View(activity).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(track, clip))
            background!!.level = 0
        }
    }

    /** API31+ 真实高斯模糊；低版本静默跳过（半透明材质仍成立） */
    fun blur(view: View) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(22f, 22f, android.graphics.Shader.TileMode.CLAMP)
            )
        }
    }

    /** 默认壁纸：深邃夜空底 + 克制柔光（低饱和，供毛玻璃出质感） */
    fun defaultWallpaper(activity: Activity): Bitmap {
        val w = 720; val h = 1440
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#171E2E"), Color.parseColor("#0B0F18"))
        )
        base.setBounds(0, 0, w, h)
        base.draw(c)
        // 低饱和柔光斑：靛蓝/暖琥珀双主调，避免花哨
        fun blob(colorHex: String, cx: Float, cy: Float, r: Float, alpha: Int = 70) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(colorHex)
                this.alpha = alpha
            }
            c.drawCircle(cx * w, cy * h, r, p)
        }
        blob("#4A6FB5", 0.22f, 0.15f, 300f, 60)
        blob("#C98A5A", 0.85f, 0.24f, 260f, 40)
        blob("#3D8A78", 0.12f, 0.68f, 280f, 42)
        blob("#6C5FC7", 0.88f, 0.82f, 300f, 48)
        blob("#2E4A7A", 0.45f, 0.95f, 240f, 55)
        // 轻噪点避免 banding
        val noise = android.graphics.Paint().apply { alpha = 7 }
        var seed = 42L
        for (i in 0 until 2200) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val x = ((seed shr 33) % w).toInt()
            val y = ((seed shr 21) % h).toInt()
            c.drawPoint(x.toFloat(), y.toFloat(), noise)
        }
        return bmp
    }

    /** dp → px */
    fun dp(v: Int, density: Float) = (v * density).roundToInt()
}

fun density(activity: Activity) = activity.resources.displayMetrics.density

/**
 * 笔记 kind → 中文标签的唯一权威映射。
 * NotesView / StatsView / YeshuApp 都应引用此处，避免各自维护一份缺键（尤其是 AI 书籍简介 "intro"）的副本。
 */
object NoteKindLabels {
    val all: Map<String, String> = mapOf(
        "summary" to "摘要",
        "ask" to "问答",
        "quiz" to "自测",
        "chat" to "聊天",
        "quote" to "金句",
        "digest" to "精读",
        "report" to "报告",
        "intro" to "书籍简介",
        "study_pack" to "理解包",
        "quiz_grade" to "批改",
        // 阅读器段落级操作现在会落库（原先 kind=null 直接丢弃），需要各自的标签，
        // 否则这些产出在笔记列表里都显示成通用「笔记」。
        "explain" to "解释",
        "translate" to "翻译",
        "continue" to "续写",
        "recap" to "前情",
        "cast" to "人物",
        "note" to "笔记"
    )

    /** 展示顺序（筛选 chips 等按此序渲染），与 [all] 的键保持一致 */
    val order: List<String> = all.keys.toList()

    /** 未知 kind 统一回退为“笔记” */
    fun label(kind: String): String = all[kind] ?: "笔记"
}
