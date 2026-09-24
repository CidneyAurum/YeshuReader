package app.yeshu.reader

/**
 * 应用内返回栈。
 *
 * 早先的返回逻辑是一张写死的映射表：`Reader -> Shelf`、`Stats -> Workbench`、其余一律回工作台。
 * 于是从工作台的「继续阅读」进书，按返回会被丢到书架，要再按一次才回工作台——
 * 用户看到的现象就是「进了书就回不到主页，再按就退出应用」。
 *
 * 现在改成记录真实来处：进下一层时把当时所在的地方压栈，返回时弹栈。
 * 底栏四个 tab 互不压栈——点底栏是在「换地方」，不是在「往里走」，
 * 这时还留着上一个 tab 的返回记录，返回键就会莫名其妙地跳来跳去。
 */
class ReaderBackStack {

    private val history = ArrayDeque<Destination>()

    /**
     * 记录一次跳转。
     *
     * [target] 是平级 tab 时清空历史；跳到当前所在地则忽略（重复导航不该产生返回记录）。
     */
    fun onNavigate(target: Destination, current: Destination) {
        if (target == current) return
        if (target.isTopLevel()) {
            history.clear()
            return
        }
        history.addLast(current)
    }

    /**
     * 返回键该去哪；返回 null 表示无处可退，调用方应当退出应用。
     *
     * [current] 用来兜底：历史为空（例如进程被杀后从 Bundle 恢复）时也要回到工作台，
     * 而不是直接把应用关掉——用户按一次返回就退出，比返回错了更让人意外。
     */
    fun onBack(current: Destination): Destination? {
        while (history.isNotEmpty()) {
            val previous = history.removeLast()
            // 历史里可能残留与当前相同的目的地（例如在阅读器里又点开同一本书），跳过它。
            if (previous != current) return previous
        }
        return if (current == Destination.Workbench) null else Destination.Workbench
    }

    /** 供 onSaveInstanceState 使用。 */
    fun snapshot(): List<Destination> = history.toList()

    /**
     * 从 Bundle 恢复。
     *
     * 这里**不能**过滤掉顶层目的地：栈里存的是「从哪来」，而来处通常正是某个 tab
     * （从书架点进一本书，历史里就是 [Destination.Shelf]）。过滤掉它，旋屏后按返回就回不到书架了。
     */
    fun restore(items: List<Destination>) {
        history.clear()
        items.forEach { history.addLast(it) }
    }

    /** 仅供测试与断言使用。 */
    fun depth(): Int = history.size
}