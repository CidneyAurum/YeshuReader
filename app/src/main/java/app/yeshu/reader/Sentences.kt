package app.yeshu.reader

/**
 * 把一段文字切成句子。
 *
 * 阅读器原本的长按菜单只能对「整段」做解释、翻译、查词，
 * 但实际最常想处理的是其中某一句话。切句放在这里而不是 UI 里，
 * 是因为它有一堆边界（连着的终止符、收尾引号、小数点、换行），
 * 靠肉眼在设备上是数不清的。
 */
object Sentences {

    /** 句末标点。中文的 `……` 会连着出现，靠下面的合并逻辑吃掉。 */
    private const val TERMINATORS = "。！？!?；;…"

    /** 跟在句末标点后面、属于同一句的收尾符号。 */
    private const val TRAILING = "”’』」）》〉)]}\"'"

    /** 句子上限。真出现超长块时，列表本身就没法用了，不如直接不分。 */
    const val MAX_SENTENCES = 60

    /**
     * 切句。切不出来（没有句末标点）时返回整段，长度为 0 时返回空列表。
     *
     * 英文的 `.` 只在两侧都不是数字时才断句，否则 `3.14`、`v1.2` 会被切开。
     */
    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            // 换行是硬断，不吃后面的收尾符号（否则下一行开头的引号会被粘到上一句）
            if (c == '\n' || c == '\r') {
                flush(sb, out)
                i++
                continue
            }
            sb.append(c)
            if (!isTerminator(trimmed, i)) {
                i++
                continue
            }
            var j = i + 1
            while (j < trimmed.length && (isTerminator(trimmed, j) || trimmed[j] in TRAILING)) {
                sb.append(trimmed[j])
                j++
            }
            flush(sb, out)
            i = j
        }
        flush(sb, out)
        return if (out.size > MAX_SENTENCES) listOf(trimmed) else out
    }

    /**
     * 取句子在原文里的偏移，用于给笔记/划线定位。
     * 找不到时返回 0——调用方只把它当提示，不该因此中断操作。
     */
    fun offsetOf(text: String, sentence: String): Int {
        val at = text.indexOf(sentence)
        return if (at < 0) 0 else at
    }

    private fun flush(sb: StringBuilder, out: MutableList<String>) {
        val piece = sb.toString().trim()
        sb.setLength(0)
        // 不丢弃任何片段：菜单号称「这一段有 N 句」，少列一句就是静默吞内容。
        if (piece.isNotEmpty()) out.add(piece)
    }

    private fun isTerminator(text: String, index: Int): Boolean {
        val c = text[index]
        if (c in TERMINATORS) return true
        if (c != '.') return false
        val prev = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        return !(prev?.isDigit() == true && next?.isDigit() == true)
    }
}