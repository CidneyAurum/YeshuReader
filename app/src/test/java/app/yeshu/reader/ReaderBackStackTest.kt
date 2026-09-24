package app.yeshu.reader

import app.yeshu.reader.parse.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 返回栈。
 *
 * 这里盯的是用户实际会遇到的几条路径——尤其是「从工作台进书、返回要回工作台」，
 * 这正是之前写死「阅读器 → 书架」时坏掉的那条。
 */
class ReaderBackStackTest {

    private val book = Destination.Reader(7L)
    private val chat = Destination.Chat(7L, listOf(Block(Block.TEXT, "正文")))

    @Test
    fun `从工作台进书 返回回工作台`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Workbench)
        assertEquals(Destination.Workbench, stack.onBack(book))
    }

    @Test
    fun `从书架进书 返回回书架`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Shelf)
        assertEquals(Destination.Shelf, stack.onBack(book))
    }

    @Test
    fun `工作台进书再进笔记 逐层退回`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Workbench)
        stack.onNavigate(Destination.BookNotes(7L), book)
        assertEquals(book, stack.onBack(Destination.BookNotes(7L)))
        assertEquals(Destination.Workbench, stack.onBack(book))
    }

    @Test
    fun `切底栏 tab 不产生返回记录`() {
        val stack = ReaderBackStack()
        // 工作台 → 书架：换地方，不是往里走
        stack.onNavigate(Destination.Shelf, Destination.Workbench)
        assertEquals(0, stack.depth())
        // 从书架按返回：历史为空，兜底回工作台而不是退出应用
        assertEquals(Destination.Workbench, stack.onBack(Destination.Shelf))
    }

    @Test
    fun `切 tab 会清掉之前压的详情历史`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Shelf)
        assertEquals(1, stack.depth())
        // 在阅读器里点底栏回工作台，历史必须清空：
        // 否则从工作台再按返回会被送回那本书。
        stack.onNavigate(Destination.Workbench, book)
        assertEquals(0, stack.depth())
        assertNull(stack.onBack(Destination.Workbench))
    }

    @Test
    fun `重复导航到同一目的地不压栈`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Shelf)
        stack.onNavigate(book, book)
        assertEquals(1, stack.depth())
    }

    @Test
    fun `工作台上按返回表示退出应用`() {
        assertNull(ReaderBackStack().onBack(Destination.Workbench))
    }

    @Test
    fun `历史为空时按返回回工作台而不是退出`() {
        // 进程被杀后从 Bundle 恢复、或历史被清空时，按一次返回就退出应用太意外。
        assertNull(ReaderBackStack().onBack(Destination.Workbench))
        assertEquals(Destination.Workbench, ReaderBackStack().onBack(Destination.Settings))
        assertEquals(Destination.Workbench, ReaderBackStack().onBack(book))
    }

    @Test
    fun `历史里的自身会被跳过`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Workbench)
        // 在阅读器里又打开同一本书（例如从引用 chip 跳转），历史里会多一条相同的 Reader
        stack.onNavigate(book, book)
        assertEquals(Destination.Workbench, stack.onBack(book))
    }

    @Test
    fun `快照与恢复保持返回路径`() {
        val stack = ReaderBackStack()
        stack.onNavigate(book, Destination.Shelf)
        stack.onNavigate(chat, book)
        val snapshot = stack.snapshot()
        assertEquals(listOf(Destination.Shelf, book), snapshot)

        val restored = ReaderBackStack()
        restored.restore(snapshot)
        assertEquals(book, restored.onBack(chat))
        assertEquals(Destination.Shelf, restored.onBack(book))
    }

    @Test
    fun `恢复时保留作为来处的顶层 tab`() {
        // 栈里存的是「从哪来」，而来处通常正是某个 tab。过滤掉顶层会把来处弄丢，
        // 旋屏后按返回就回不到书架了。
        val stack = ReaderBackStack()
        stack.restore(listOf(Destination.Shelf, book))
        assertEquals(2, stack.depth())
        assertEquals(book, stack.onBack(Destination.BookNotes(7L)))
        assertEquals(Destination.Shelf, stack.onBack(book))
    }

    @Test
    fun `统计页从书架进就回书架 从工作台进就回工作台`() {
        val fromShelf = ReaderBackStack().apply { onNavigate(Destination.Stats, Destination.Shelf) }
        assertEquals(Destination.Shelf, fromShelf.onBack(Destination.Stats))
        val fromWorkbench = ReaderBackStack().apply { onNavigate(Destination.Stats, Destination.Workbench) }
        assertEquals(Destination.Workbench, fromWorkbench.onBack(Destination.Stats))
    }

    @Test
    fun `顶层判定与底栏一致`() {
        listOf(Destination.Workbench, Destination.Shelf, Destination.Notes, Destination.Settings)
            .forEach { assertEquals("$it 应算顶层", true, it.isTopLevel()) }
        listOf<Destination>(Destination.Stats, book, Destination.BookNotes(7L), chat)
            .forEach { assertEquals("$it 不应算顶层", false, it.isTopLevel()) }
    }
}