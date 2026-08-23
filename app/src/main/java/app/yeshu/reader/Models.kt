package app.yeshu.reader

/** Unified local item used by both the everyday bookshelf and the document workbench. */
data class LibraryItem(
    val id: Long,
    val title: String,
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val progress: Float,
    val addedAt: Long,
    val lastReadAt: Long,
    val folderId: Long = 0,
    val author: String = "",
    val itemType: String = "book",
    val status: String = "unread",
    val favorite: Boolean = false,
    val tags: String = "",
    val contentHash: String = ""
)

/** Backward-compatible name retained while legacy Views are migrated to Compose. */
typealias Book = LibraryItem

data class NoteRow(
    val id: Long,
    val kind: String,
    val content: String,
    val bookId: Long = 0,
    val createdAt: Long = 0
)

/** Folder/collection. parentId == 0 denotes the library root. */
data class Folder(val id: Long, val name: String, val parentId: Long)

enum class AnchorType { PAGE, SLIDE, CHAPTER, PARAGRAPH, IMAGE, SELECTION }

data class DocumentAnchor(
    val type: AnchorType,
    val index: Int,
    val label: String,
    val excerpt: String = ""
)

data class AiProviderProfile(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val keyAlias: String,
    val textModel: String,
    val visionModel: String,
    val supportsStreaming: Boolean = true,
    val supportsVision: Boolean = false,
    val allowPrivateHttp: Boolean = false
)

data class AiArtifact(
    val id: Long,
    val bookId: Long,
    val kind: String,
    val status: String,
    val content: String,
    val citationsJson: String,
    val documentHash: String,
    val model: String,
    val promptVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class BackupManifest(
    val app: String = "yeshu",
    val version: Int = 2,
    val exportedAt: Long,
    val includesOriginalFiles: Boolean,
    val includesSecrets: Boolean = false
)
