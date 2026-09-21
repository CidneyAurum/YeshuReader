package app.yeshu.reader

import android.graphics.Color

/**
 * 单一强调色系统。
 *
 * 之前同时存在 4 种「蓝/紫」：Compose 的 `ElectricBlue #5B5FF5` / `ActiveViolet`、
 * legacy 的 `T.accent #0A84FF`、阅读器的 `#B7C0FF`/`#AAB5FF`、对话框按钮的 `#8FB6FF`。
 * 同一屏里出现两种蓝，视觉上像几个 App 拼在一起——这是「鸡肋」观感的直接来源。
 *
 * 约定：
 * - [primary] 只用于「主操作 / 选中态 / 进度填充」；
 * - [onPrimary] 与 [primarySoft] 只用于主色之上的文字（深底/浅底各一档）；
 * - [violet]/[cyan] 保留为「次级分类色」，只做标签与图标底色，不再参与主操作。
 * 新增颜色一律加在这里，禁止在页面代码里写死十六进制。
 */
object Accent {
    /** 主强调色。 */
    val primary = Color.parseColor("#5B5FF5")

    /** 主色之上的文字（深底场景）。 */
    val onPrimarySoft = Color.parseColor("#AAB5FF")

    /** 浅底场景的主色文字（如对话框按钮）。 */
    val primarySoft = Color.parseColor("#B7C0FF")

    /** 次级分类色：仅用于标签/图标底，不用于主操作。 */
    val violet = Color.parseColor("#A855F7")
    val cyan = Color.parseColor("#22D3EE")

    /** 阅读器深色 chrome 上的正文/次要文字，避免各处再写死。 */
    val chromeTextPrimary = Color.parseColor("#F7F8FC")
    val chromeTextSecondary = Color.parseColor("#AEB4C2")
    val chromeTextTertiary = Color.parseColor("#B8BECC")
    val chromeTextMuted = Color.parseColor("#F4F5FA")
    val chromeAccentText = Color.parseColor("#B7C0FF")
    val chromeAccentTextSoft = Color.parseColor("#AAB5FF")
    val dangerText = Color.parseColor("#E57373")
}
