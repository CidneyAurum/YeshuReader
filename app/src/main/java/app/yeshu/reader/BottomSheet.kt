package app.yeshu.reader

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator

/**
 * 通用底部弹层：圆角面板 + 拖拽指示条 + 可选标题 + 图标菜单项 + 分组标题。
 *
 * 分组（[section]）与副标题（`description`）是给 AI 动作列表用的：
 * 用户必须能一眼看出哪些动作本地完成、哪些会把内容发到哪个服务商。
 * 蒙层点击关闭；进入/退出 220ms 平台缓动。用完即销毁，不驻留。
 */
class BottomSheet(private val act: Activity, private val title: String? = null) {

    private val d = density(act)
    // 跟随主题偏好：浅色主题下不能再用近黑面板 + 浅色文字
    private val pal by lazy { LegacyPalette.of(act) }
    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private var panelHost: ScrollView? = null

    private sealed interface Entry {
        data class Section(val title: String, val subtitle: String?) : Entry
        data class Row(
            val icon: String,
            val label: String,
            val description: String?,
            val onClick: () -> Unit
        ) : Entry
    }

    private val entries = mutableListOf<Entry>()

    fun section(title: String, subtitle: String? = null): BottomSheet {
        entries.add(Entry.Section(title, subtitle))
        return this
    }

    fun item(icon: String, label: String, description: String? = null, onClick: () -> Unit): BottomSheet {
        entries.add(Entry.Row(icon, label, description, onClick))
        return this
    }

    fun show() {
        val root = act.window.decorView as android.view.ViewGroup
        // 蒙层同样走调色板：浅色主题下用更轻的压暗，深色主题下更重。
        overlay = FrameLayout(act).apply { setBackgroundColor(pal.scrim) }
        overlay.setOnClickListener { dismiss() }

        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    Glass.dp(T.rSheet, d).toFloat(), Glass.dp(T.rSheet, d).toFloat(),
                    Glass.dp(T.rSheet, d).toFloat(), Glass.dp(T.rSheet, d).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(pal.surface)
            }
            setPadding(Glass.dp(8, d), Glass.dp(10, d), Glass.dp(8, d), Glass.dp(18, d))
        }

        // 拖拽指示条
        panel.addView(FrameLayout(act).apply {
            addView(View(act).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(2, d).toFloat()
                    setColor(if (pal.dark) Color.argb(60, 255, 255, 255) else Color.argb(46, 23, 26, 43))
                }
            }, FrameLayout.LayoutParams(Glass.dp(36, d), Glass.dp(4, d), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, Glass.dp(16, d)))

        // 标题
        if (!title.isNullOrBlank()) {
            panel.addView(TextView(act).apply {
                text = title
                textSize = 13f
                setTextColor(pal.textT)
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(6, d))
            })
        }

        for (entry in entries) {
            when (entry) {
                is Entry.Section -> panel.addView(sectionView(entry))
                is Entry.Row -> panel.addView(rowView(entry))
            }
        }

        // 项目较多（如 AI 动作分组）时内容可能超过一屏：放进可滚动容器，
        // 否则底部条目会被导航栏裁掉且无法访问。
        val maxPanelHeight = (act.resources.displayMetrics.heightPixels * 0.82f).toInt()
        val host = ScrollView(act).apply {
            isFillViewport = false
            addView(panel, FrameLayout.LayoutParams(-1, -2))
        }
        panelHost = host
        overlay.addView(host, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 底部安全区：navigation bar inset 转为面板 padding
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, 0, bars.right, 0)
            panel.setPadding(
                Glass.dp(8, d), Glass.dp(10, d), Glass.dp(8, d),
                Glass.dp(18, d) + bars.bottom
            )
            host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
                height = maxPanelHeight
            }
            insets
        }
        ViewCompat.requestApplyInsets(overlay)

        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // 进入动画：蒙层淡入 + 面板上滑
        host.post {
            host.translationY = host.height.toFloat()
            overlay.alpha = 0f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(host, "translationY", host.height.toFloat(), 0f),
                    ObjectAnimator.ofFloat(overlay, "alpha", 0f, 1f)
                )
                duration = T.durNorm.toLong()
                interpolator = DecelerateInterpolator(1.6f)
                start()
            }
        }
    }

    /** 分组标题：小字 + 一行解释，用于区分「本地」与「会联网」的动作。 */
    private fun sectionView(section: Entry.Section): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Glass.dp(14, d), Glass.dp(12, d), Glass.dp(14, d), Glass.dp(4, d))
        addView(TextView(act).apply {
            text = section.title
            textSize = 11.5f
            setTextColor(pal.textT)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
        })
        section.subtitle?.let { sub ->
            addView(TextView(act).apply {
                text = sub
                textSize = 10.5f
                setTextColor(pal.textT)
                setPadding(0, Glass.dp(2, d), 0, 0)
            })
        }
    }

    /** 菜单项：图标 + 标签（+ 可选一行说明），按压反馈。 */
    private fun rowView(row: Entry.Row): View {
        val hasDescription = !row.description.isNullOrBlank()
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            foreground = Glass.pressFx()
            setPadding(Glass.dp(12, d), 0, Glass.dp(12, d), 0)
            // 自绘图标无自身语义，标签放在整行容器上，TalkBack 只播报一次
            contentDescription = if (hasDescription) "${row.label}，${row.description}" else row.label
            setOnClickListener {
                dismiss()
                row.onClick()
            }
        }
        container.addView(FrameLayout(act).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (pal.dark) Color.argb(28, 255, 255, 255) else Color.argb(20, 23, 26, 43))
            }
            addView(IconView(act, row.icon, 19, pal.icon).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }, LinearLayout.LayoutParams(Glass.dp(38, d), Glass.dp(38, d)))
        container.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = row.label
                textSize = 15f
                setTextColor(pal.textP)
            })
            if (hasDescription) {
                addView(TextView(act).apply {
                    text = row.description
                    textSize = 10.5f
                    setTextColor(pal.textT)
                    setPadding(0, Glass.dp(2, d), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = Glass.dp(14, d) })
        return container
    }

    fun dismiss() {
        if (!this::overlay.isInitialized) return
        val host = panelHost
        AnimatorSet().apply {
            if (host != null) {
                playTogether(
                    ObjectAnimator.ofFloat(host, "translationY", 0f, host.height.toFloat()),
                    ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f)
                )
            } else {
                playTogether(ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f))
            }
            duration = T.durFast.toLong()
            interpolator = DecelerateInterpolator(2f)
            start()
        }
        overlay.postDelayed({
            // Activity 可能在退出动画期间销毁，此时 decorView 已失效，直接放弃移除
            if (act.isFinishing || act.isDestroyed) return@postDelayed
            val root = runCatching { act.window?.decorView as? android.view.ViewGroup }.getOrNull()
                ?: return@postDelayed
            if (overlay.parent === root) root.removeView(overlay)
        }, T.durFast.toLong())
    }
}