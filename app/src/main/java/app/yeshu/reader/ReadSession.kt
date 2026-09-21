package app.yeshu.reader

import kotlin.math.max
import kotlin.math.min

/**
 * 阅读时长计入规则（纯函数，便于单测）。
 *
 * 原先只有「delta < 1s 丢弃」与一个总上限。补上「不超过本次挂载的真实墙钟」这一条，
 * 是为了兜住宿主重复 resume 造成的重复计时：只要两次 resume 之间没有 pause，
 * 计时起点就只设置一次，累计值不可能超过视图真正处于 attached 状态的时间。
 */
object ReadSession {

    /** 计入时长。返回 0 表示这次会话不计入（噪声、未 attach 或全部被上限截断）。 */
    fun countMs(elapsedMs: Long, wallClockMs: Long, capMs: Long): Long {
        if (elapsedMs < MIN_SESSION_MS) return 0L
        val bounded = min(elapsedMs, max(wallClockMs, 0L))
        if (bounded < MIN_SESSION_MS) return 0L
        return min(bounded, capMs)
    }

    /** 单次会话计入上限：异常超长会话按上限计入，不再整段丢弃。 */
    const val DEFAULT_CAP_MS = 6 * 3600_000L

    private const val MIN_SESSION_MS = 1000L
}
