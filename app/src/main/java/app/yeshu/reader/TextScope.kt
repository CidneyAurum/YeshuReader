package app.yeshu.reader

/**
 * 字符数量的展示口径。
 *
 * 之前多处直接用 `n / 1000` 拼「k 字符」，几百字的短文档会显示成「0k」——
 * 读起来像「什么都没发出去」，比不显示更糟。小于 1000 时给精确值。
 */
internal fun formatChars(chars: Int): String =
    if (chars < 1000) chars.toString() else "${chars / 1000}k"

/**
 * 图片集 / 固定版式文档解析出的「正文」只有一行占位摘要（如「图片集：共 12 页」）。
 * 把它当作正文发给文本模型既浪费一次付费调用，又会让用户以为发送失败，
 * 因此在所有文本 AI 入口前统一判断，改为引导到视觉通道。
 */
private val IMAGE_ONLY_PREFIXES = listOf("图片集：", "图片集:", "图片版式：", "图片版式:", "漫画包：", "漫画包:")

/** 占位摘要最长也就「图片集：共 300 页」这种量级，超过这个长度就当成真正文。 */
private const val PLACEHOLDER_MAX_CHARS = 60

/** 少于此长度视为没有实质正文，不足以支撑摘要/问答。 */
private const val MIN_EXTRACTABLE_CHARS = 40

internal fun hasExtractableText(fullText: String): Boolean {
    val text = fullText.trim()
    if (text.length < MIN_EXTRACTABLE_CHARS) return false
    if (text.length <= PLACEHOLDER_MAX_CHARS && IMAGE_ONLY_PREFIXES.any { text.startsWith(it) }) return false
    return true
}

/**
 * 文件大小：书架上各处口径必须一致。
 * 之前有的地方用 KB 整除（<1KB 显示成「0 KB」），有的地方只显示字节数。
 */
internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value < 1024 -> "$value B"
        value < 1024L * 1024 -> "${value / 1024} KB"
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
        else -> "%.1f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }
}