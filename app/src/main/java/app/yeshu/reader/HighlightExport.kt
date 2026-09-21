package app.yeshu.reader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 划线 / 书签 / 笔记的 Markdown 导出。
 *
 * 参照 Readwise 的做法：按书分节，每条带位置与时间，方便直接粘进 Obsidian / Notion。
 * 纯本地字符串拼接，不涉及网络，也不包含任何密钥或服务地址。
 */
object HighlightExport {

    /** 高亮的 kind 取值，与 ReaderView 写入时保持一致。 */
    private const val KIND_HIGHLIGHT = "highlight"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    /**
     * 单本书的导出。[db] 由调用方传入，避免这里再建一个连接。
     *
     * 同一位置既有书签又有划线时都会保留：它们是两种不同的意图，
     * 合并会让用户以为丢了一条。
     */
    fun forBook(db: Db, bookId: Long, title: String): String {
        // 用 detail 版查询：导出需要 anchor 与 status，NoteRow 不带这两列。
        val notes = db.allNoteDetails(bookId)
        val bookmarks = db.listBookmarks(bookId)
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("> 由页枢导出 · ${timeFormat.format(Date())}")
            appendLine()

            val highlights = notes.filter { it.kind == KIND_HIGHLIGHT }
            if (highlights.isNotEmpty()) {
                appendLine("## 划线（${highlights.size}）")
                appendLine()
                highlights.sortedBy { it.createdAt }.forEach { note ->
                    appendLine("> ${note.content.replace("\n", "\n> ")}")
                    appendLine()
                    appendLine(metaLine(note.anchor, note.status, note.createdAt))
                    appendLine()
                }
            }

            val others = notes.filter { it.kind != KIND_HIGHLIGHT }
            if (others.isNotEmpty()) {
                appendLine("## 笔记与 AI 结果（${others.size}）")
                appendLine()
                others.sortedBy { it.createdAt }.forEach { note ->
                    appendLine("### ${NoteKindLabels.label(note.kind)}")
                    appendLine()
                    appendLine(note.content)
                    appendLine()
                    appendLine(metaLine(note.anchor, "", note.createdAt))
                    appendLine()
                }
            }

            if (bookmarks.isNotEmpty()) {
                appendLine("## 书签（${bookmarks.size}）")
                appendLine()
                bookmarks.sortedBy { it.createdAt }.forEach { mark ->
                    val label = mark.label.ifBlank { mark.anchor }
                    appendLine("- **$label**" + if (mark.excerpt.isNotBlank()) " — ${mark.excerpt.replace("\n", " ")}" else "")
                    appendLine("  - ${timeFormat.format(Date(mark.createdAt))}")
                }
                appendLine()
            }

            if (highlights.isEmpty() && others.isEmpty() && bookmarks.isEmpty()) {
                appendLine("_这本书还没有划线、笔记或书签。_")
                appendLine()
            }
        }
    }

    /** 全部书籍的导出：按书分节，书与书之间用分隔线。 */
    fun forAllBooks(db: Db): String {
        val books = db.listBooks()
        return buildString {
            appendLine("# 页枢 · 全部划线与笔记")
            appendLine()
            appendLine("> 共 ${books.size} 本书 · ${timeFormat.format(Date())}")
            appendLine()
            books.forEach { book ->
                appendLine("---")
                appendLine()
                append(forBook(db, book.id, book.title))
                appendLine()
            }
        }
    }

    private fun metaLine(anchor: String, color: String, createdAt: Long): String {
        val parts = buildList {
            if (anchor.isNotBlank()) add(anchor)
            if (color.isNotBlank()) add("颜色：${HighlightColors.label(color)}")
            add(timeFormat.format(Date(createdAt)))
        }
        return "_${parts.joinToString(" · ")}_"
    }
}

/** 高亮颜色的中文名。导出时写「amber」对读者没有意义。 */
object HighlightColors {
    private val names = mapOf(
        "amber" to "琥珀",
        "green" to "绿",
        "blue" to "蓝",
        "rose" to "玫红",
    )

    fun label(key: String): String = names[key] ?: key
}
