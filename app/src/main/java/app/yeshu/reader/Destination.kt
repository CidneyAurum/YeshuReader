package app.yeshu.reader

import app.yeshu.reader.parse.Block

/**
 * 应用内的目的地。
 *
 * 分两类：
 * - **顶层**（[Workbench] / [Shelf] / [Notes] / [Settings]）对应底栏四个 tab，彼此平级；
 * - **下一层**（[Stats] / [Reader] / [BookNotes] / [Chat]）都是从某个 tab 走进去的。
 *
 * 这个区分决定了返回键的行为，见 [ReaderBackStack]。
 */
sealed interface Destination {
    data object Workbench : Destination
    data object Shelf : Destination
    data object Notes : Destination
    data object Settings : Destination
    data object Stats : Destination

    /** [anchor] 非空时进入阅读器后按该锚点定位一次（来自笔记/成果里的引用 chip）。 */
    data class Reader(val bookId: Long, val anchor: String = "") : Destination

    data class BookNotes(val bookId: Long) : Destination

    /**
     * [paragraphBase]/[chapterBase] 是当前章在全书里的编号起点：
     * 聊天里的 [PARAGRAPH:n] 按全书口径编号，不补偏移就指不到正确段落。
     */
    data class Chat(
        val bookId: Long,
        /** 当前章的块列表。传块而不是文本：文本往返会让段落编号与阅读器不一致，引用就跳不回去。 */
        val chapterBlocks: List<Block>,
        val paragraphBase: Int = 0,
        val chapterBase: Int = 0,
    ) : Destination
}

/** 底栏四个 tab 的目的地，返回键与压栈规则都要用。 */
val TOP_LEVEL_DESTINATIONS: Set<Destination> = setOf(
    Destination.Workbench,
    Destination.Shelf,
    Destination.Notes,
    Destination.Settings,
)

/** 是否底栏 tab。 */
fun Destination.isTopLevel(): Boolean = this in TOP_LEVEL_DESTINATIONS