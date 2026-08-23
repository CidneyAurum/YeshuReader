package com.example.helloandroid

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator

/**
 * 通用底部弹层：圆角面板 + 拖拽指示条 + 可选标题 + 图标菜单项。
 * 蒙层点击关闭；进入/退出 220ms 平台缓动。用完即销毁，不驻留。
 */
class BottomSheet(private val act: Activity, private val title: String? = null) {

    private val d = density(act)
    private lateinit var overlay: FrameLayout
    private lateinit var panel: LinearLayout
    private val items = mutableListOf<Triple<String, String, () -> Unit>>()  // icon, label, onClick

    fun item(icon: String, label: String, onClick: () -> Unit): BottomSheet {
        items.add(Triple(icon, label, onClick))
        return this
    }

    fun show() {
        val root = act.window.decorView as android.view.ViewGroup
        overlay = FrameLayout(act).apply { setBackgroundColor(T.scrim) }
        overlay.setOnClickListener { dismiss() }

        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    Glass.dp(T.rSheet, d).toFloat(), Glass.dp(T.rSheet, d).toFloat(),
                    Glass.dp(T.rSheet, d).toFloat(), Glass.dp(T.rSheet, d).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(T.surface)
            }
            setPadding(Glass.dp(8, d), Glass.dp(10, d), Glass.dp(8, d), Glass.dp(18, d))
        }

        // 拖拽指示条
        panel.addView(FrameLayout(act).apply {
            addView(View(act).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Glass.dp(2, d).toFloat()
                    setColor(Color.argb(60, 255, 255, 255))
                }
            }, FrameLayout.LayoutParams(Glass.dp(36, d), Glass.dp(4, d), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, Glass.dp(16, d)))

        // 标题
        if (!title.isNullOrBlank()) {
            panel.addView(TextView(act).apply {
                text = title
                textSize = 13f
                setTextColor(T.textT)
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(Glass.dp(14, d), Glass.dp(6, d), Glass.dp(14, d), Glass.dp(10, d))
            })
        }

        // 菜单项：56dp 高、图标+标签，按压反馈
        for ((icon, label, onClick) in items) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                foreground = Glass.pressFx()
                setPadding(Glass.dp(12, d), 0, Glass.dp(12, d), 0)
                setOnClickListener {
                    dismiss()
                    onClick()
                }
            }
            row.addView(FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(28, 255, 255, 255))
                }
                addView(IconView(act, icon, 19))
            }, LinearLayout.LayoutParams(Glass.dp(38, d), Glass.dp(38, d)))
            row.addView(TextView(act).apply {
                text = label
                textSize = 15f
                setTextColor(T.textP)
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.marginStart = Glass.dp(14, d)
                layoutParams = lp
            }, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(row, LinearLayout.LayoutParams(-1, Glass.dp(54, d)))
        }

        overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // 底部安全区：navigation bar inset 转为面板 padding
        overlay.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                android.view.WindowInsets.Type.navigationBars() or
                    android.view.WindowInsets.Type.displayCutout()
            )
            v.setPadding(bars.left, 0, bars.right, 0)
            panel.setPadding(
                Glass.dp(8, d), Glass.dp(10, d), Glass.dp(8, d),
                Glass.dp(18, d) + bars.bottom
            )
            insets
        }

        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // 进入动画：蒙层淡入 + 面板上滑
        panel.post {
            panel.translationY = panel.height.toFloat()
            overlay.alpha = 0f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(panel, "translationY", panel.height.toFloat(), 0f),
                    ObjectAnimator.ofFloat(overlay, "alpha", 0f, 1f)
                )
                duration = T.durNorm.toLong()
                interpolator = DecelerateInterpolator(1.6f)
                start()
            }
        }
    }

    fun dismiss() {
        if (!this::overlay.isInitialized) return
        val p = panel
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(p, "translationY", 0f, p.height.toFloat()),
                ObjectAnimator.ofFloat(overlay, "alpha", 1f, 0f)
            )
            duration = T.durFast.toLong()
            interpolator = DecelerateInterpolator(2f)
            start()
        }
        overlay.postDelayed({
            (act.window.decorView as android.view.ViewGroup).removeView(overlay)
        }, T.durFast.toLong())
    }
}
