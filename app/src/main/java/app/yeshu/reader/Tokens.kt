package app.yeshu.reader

import android.graphics.Color

/**
 * Design Tokens —— 全局唯一视觉常量源。
 * 颜色 / 圆角 / 间距 / 动画时长，禁止在页面代码里散落魔法值。
 */
object T {
    // ---- 颜色 ----
    val bg = Color.parseColor("#0B0B0F")            // 页面主背景（近黑）
    val surface = Color.parseColor("#1C1C1E")       // 一级表面（卡片/搜索框/文件夹行）
    val surface2 = Color.parseColor("#252529")      // 二级表面（segmented 底、次级按钮）
    val surface3 = Color.parseColor("#48484C")      // 三级表面（segmented 选中段）
    val textP = Color.parseColor("#F5F5F7")         // 主文字
    val textS = Color.argb(158, 245, 245, 247)      // 次文字 62%
    val textT = Color.argb(107, 245, 245, 247)      // 弱文字 42%
    val accent = Color.parseColor("#0A84FF")        // 系统蓝强调色
    val hairline = Color.argb(20, 255, 255, 255)    // 发丝分隔线
    val scrim = Color.argb(140, 0, 0, 0)            // 弹层蒙层

    /** 封面占位渐变色对（低饱和、彼此协调）——按书名 hash 稳定取色 */
    val coverPairs = arrayOf(
        "#39456B" to "#1E2438",
        "#5C4A3D" to "#332A22",
        "#3D5548" to "#202E26",
        "#54394A" to "#2E2029",
        "#3A4E5C" to "#1F2B33",
        "#4E4437" to "#2B251E"
    )

    // ---- 圆角(dp) ----
    const val rCard = 16      // 卡片/搜索框/文件夹行
    const val rCover = 13     // 封面
    const val rSheet = 24     // bottom sheet 顶部
    const val rPill = 999

    // ---- 动画时长(ms) ----
    const val durFast = 180
    const val durNorm = 220
    const val durSlow = 280

    // ---- 常用尺寸(dp) ----
    const val pagePad = 20        // 页面左右边距
    const val coverWList = 56     // 列表封面宽
    const val coverHList = 84     // 列表封面高
}
