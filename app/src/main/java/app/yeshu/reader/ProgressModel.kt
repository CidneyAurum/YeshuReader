package app.yeshu.reader

/**
 * 阅读进度的唯一口径。
 *
 * 之前有三套算法同时存在，导致「进度条 92% / 顶栏 92% / 落库 61%」互相矛盾：
 * - 进度条与顶栏用「已渲染前缀」的高度比例（分块加载时前缀只是全书的一小段）；
 * - 落库时又把该比例乘上 `renderedUpTo / total` 并压到 0.98；
 * - 恢复位置时用 `progress * (total - 1)` 反推块下标，与上面两个映射都不互逆。
 *
 * 这里统一按「真实位置下标 / 总下标数」计算，前缀与全量使用同一映射，
 * 恢复位置即其逆运算，往返不再漂移。
 */
object ProgressModel {

    /**
     * @param positionIndex 当前可见块/页的下标（0 基）
     * @param totalCount 总块数/页数；<= 1 表示无法按下标插值
     * @param fullyLoaded 文本流是否已全部渲染。未渲染完时上限压到 0.98，
     *   保证 `Db.statusFor` 不会把「刚滚到已加载末尾」判成读完。
     * @param fitsOnScreen 内容不足一屏，滚动不到底。此时只有整篇已渲染**且用户确实
     *   交互过**才算读完——否则一篇几百字的短文一打开就被记成 100%。
     * @param interacted 用户是否滚动过（或已停留足够久）
     */
    fun progressOf(
        positionIndex: Int,
        totalCount: Int,
        fullyLoaded: Boolean,
        fitsOnScreen: Boolean = false,
        interacted: Boolean = true
    ): Float {
        if (fitsOnScreen || totalCount <= 1) {
            return if (fullyLoaded && interacted) 1f else 0f
        }
        val index = positionIndex.coerceIn(0, totalCount - 1)
        val value = index.toFloat() / (totalCount - 1).toFloat()
        val ceiling = if (fullyLoaded) 1f else 0.98f
        return value.coerceIn(0f, ceiling)
    }

    /** [progressOf] 的逆运算：由进度反推应恢复到的下标。 */
    fun indexForProgress(progress: Float, totalCount: Int): Int {
        if (totalCount <= 1) return 0
        return (progress.coerceIn(0f, 1f) * (totalCount - 1)).toInt().coerceIn(0, totalCount - 1)
    }
}