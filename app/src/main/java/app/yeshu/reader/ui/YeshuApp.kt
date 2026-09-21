package app.yeshu.reader.ui

import android.graphics.BitmapFactory
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yeshu.reader.AiArtifact
import app.yeshu.reader.AiClient
import app.yeshu.reader.Book
import app.yeshu.reader.ChatView
import app.yeshu.reader.Db
import app.yeshu.reader.Destination
import app.yeshu.reader.LibraryItem
import app.yeshu.reader.MainActivity
import app.yeshu.reader.NoteKindLabels
import app.yeshu.reader.NotesView
import app.yeshu.reader.R
import app.yeshu.reader.ReaderView
import app.yeshu.reader.ShelfView
import app.yeshu.reader.StatsView
import app.yeshu.reader.ai.AiProfileStore
import app.yeshu.reader.ai.AiProviders
import app.yeshu.reader.ai.SavedAiProfile
import app.yeshu.reader.security.KeyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class TopDestination(val label: String, val glyph: String, val destination: Destination)

private data class WorkbenchSnapshot(
    val books: List<LibraryItem>,
    val noteCount: Int,
    val minutes: Long,
    val aiConfigured: Boolean,
    val aiIntroSeen: Boolean
)

/** 首次使用 AI 的一次性说明标记；ReaderView / ShelfView 的入口也读同一个设置键。 */
private const val AI_INTRO_SEEN_KEY = "ai_intro_seen"

/** 视觉模型候选的命名特征；命名不规则的模型用「显示全部」兜底。 */
private val VISION_MODEL_HINT = Regex("vl|vision|llava|omni", RegexOption.IGNORE_CASE)

private val topDestinations = listOf(
    TopDestination("工作台", "⌂", Destination.Workbench),
    TopDestination("书架", "▦", Destination.Shelf),
    TopDestination("笔记", "✎", Destination.Notes),
    TopDestination("设置", "⚙", Destination.Settings)
)

/** 预计算顶层目的地集合，避免每次重组都分配列表。 */
private val topDestinationSet: Set<Destination> = topDestinations.mapTo(mutableSetOf()) { it.destination }

/**
 * “在读”判定必须与 Db.statusFor 完全一致：>0.005 为在读、>=0.99 为读完。
 * Db.statusFor 是私有实现，无法直接调用，这里镜像其比较边界。
 */
private fun isReadingProgress(progress: Float): Boolean = progress > 0.005f && progress < 0.99f

/** 设置页首屏所需的资料，全部在 IO 线程读取后再一次性交给 UI。 */
private data class SettingsBootstrap(
    val profiles: List<SavedAiProfile>,
    val profile: SavedAiProfile,
    val hasSavedKey: Boolean,
    val keyState: KeyState,
    val hasUnboundKey: Boolean,
    val volumeKeyFlip: Boolean
)

@Composable
fun YeshuApp(
    activity: MainActivity,
    destination: Destination,
    libraryRevision: Int,
    onNavigate: (Destination) -> Unit
) {
    val topLevel = destination in topDestinationSet
    val showIllustrations by activity.userPreferences.showIllustrations.collectAsStateWithLifecycle(initialValue = true)
    // The reader owns a dark, immersive chrome even when the rest of the app uses
    // the light theme, so its transparent system bars must keep light icons.
    val dark = destination is Destination.Reader ||
        MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            AuroraBackground()
            val wide = maxWidth >= 840.dp
            if (topLevel && wide) {
                Row(Modifier.fillMaxSize()) {
                    SideDock(destination = destination, onNavigate = onNavigate)
                    Box(
                        Modifier
                            .weight(1f)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        DestinationContent(activity, destination, libraryRevision, showIllustrations, onNavigate)
                    }
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (topLevel) BottomDock(destination = destination, onNavigate = onNavigate)
                    }
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(if (topLevel) padding else PaddingValues(0.dp))
                    ) {
                        DestinationContent(activity, destination, libraryRevision, showIllustrations, onNavigate)
                    }
                }
            }
        }
    }
}

@Composable
private fun SideDock(destination: Destination, onNavigate: (Destination) -> Unit) {
    Box(
        Modifier
            .width(108.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(34.dp),
            elevation = 20.dp
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandMark(48)
                Spacer(Modifier.height(28.dp))
                topDestinations.forEach { item ->
                    SideDockItem(item, destination == item.destination) { onNavigate(item.destination) }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.weight(1f))
                GlassPill(color = LuminousCyan) {
                    Text("本地", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SideDockItem(item: TopDestination, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) ElectricBlue.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(item.glyph, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = navColor(selected))
        Spacer(Modifier.height(4.dp))
        Text(item.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = navColor(selected))
    }
}

@Composable
private fun BottomDock(destination: Destination, onNavigate: (Destination) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(30.dp),
            elevation = 22.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                topDestinations.forEach { item ->
                    val selected = destination == item.destination
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (selected) ElectricBlue.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onNavigate(item.destination) }
                            .padding(top = 6.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(item.glyph, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = navColor(selected))
                        Text(item.label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = navColor(selected))
                    }
                }
            }
        }
    }
}

@Composable
private fun navColor(selected: Boolean): Color =
    if (selected) ElectricBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)

@Composable
private fun DestinationContent(
    activity: MainActivity,
    destination: Destination,
    revision: Int,
    showIllustrations: Boolean,
    onNavigate: (Destination) -> Unit
) {
    when (destination) {
        Destination.Workbench -> WorkbenchScreen(activity, revision, showIllustrations, onNavigate)
        Destination.Shelf -> LegacyHost(activity, revision = revision) { ShelfView(activity) }
        Destination.Notes -> NotesHubScreen(activity, revision)
        Destination.Settings -> SettingsScreen(activity)
        Destination.Stats -> LegacyHost(activity) { StatsView(activity) }
        is Destination.Reader -> DocumentWorkbenchScreen(activity, destination.bookId, destination.anchor, onNavigate)
        is Destination.BookNotes -> LegacyHost(activity) { NotesView(activity, destination.bookId) }
        is Destination.Chat -> LegacyHost(
            activity,
            Modifier.statusBarsPadding().navigationBarsPadding().imePadding()
        ) { ChatView(activity, destination.bookId, destination.chapterContext) }
    }
}

@Composable
private fun DocumentWorkbenchScreen(
    activity: MainActivity,
    bookId: Long,
    anchor: String = "",
    onNavigate: (Destination) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090D1A))) {
        val wide = maxWidth >= 840.dp
        var reader by remember(bookId) { mutableStateOf<ReaderView?>(null) }
        var book by remember(bookId) { mutableStateOf<Book?>(null) }
        // ReaderView 的构造会同步读库并解析文档（大型 EPUB/DOCX 可能很慢），
        // 放到 IO 线程执行，避免阻塞工作台首帧；未就绪时先渲染加载态。
        LaunchedEffect(bookId, wide) {
            reader = null
            val loaded = withContext(Dispatchers.IO) {
                val target = Db(activity).getBook(bookId) ?: return@withContext null
                ReaderView(activity, bookId, showDocumentTabs = !wide) to target
            }
            if (loaded == null) {
                onNavigate(Destination.Shelf)
                return@LaunchedEffect
            }
            reader = loaded.first
            book = loaded.second
            // 引用锚点只消费一次：挂载后按锚点定位，而不是把用户丢在文首
            if (anchor.isNotBlank()) {
                loaded.first.setPendingAnchor(anchor)
                loaded.first.post { loaded.first.jumpToPendingAnchor() }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // ReaderView 内部已通过 applySystemBarInsets 自行处理系统栏，这里不再重复加 padding
                ReaderViewHost(activity, reader, Modifier.weight(1f).fillMaxHeight())
                Box(
                    Modifier.width(286.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(14.dp)
                ) {
                    GlassPanel(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        contentPadding = PaddingValues(16.dp),
                        elevation = 18.dp
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPill(color = ElectricBlue) {
                                Text(book?.format?.uppercase() ?: "DOCUMENT", color = ElectricBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(book?.title ?: "文档工作台", fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("阅读位置与工具面板会保持同步", color = secondaryText(), fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            DocumentTool("阅读", "当前主视图", ElectricBlue, selected = true) { }
                            DocumentTool("目录", "章节或 PDF 页码", LuminousCyan) { reader?.openTableOfContents() }
                            DocumentTool("AI", "理解包、问答与自测", ActiveViolet) { reader?.openAiWorkbench() }
                            DocumentTool("笔记", "批注与 AI 结果", Color(0xFFFF8A65)) { onNavigate(Destination.BookNotes(bookId)) }
                            // 宽屏侧栏原先没有聊天入口，聊天只在窄屏可用
                            DocumentTool("AI 问答", "带着当前章上下文聊天", ElectricBlue) {
                                onNavigate(Destination.Chat(bookId, ""))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("AI 只在你主动触发时发送所选范围。", color = secondaryText(), fontSize = 10.sp)
                        }
                    }
                }
            }
        } else {
            ReaderViewHost(activity, reader)
        }
    }
}

/** 挂载已在后台线程构造完成的 ReaderView；构造期间显示加载态。 */
@Composable
private fun ReaderViewHost(activity: MainActivity, reader: ReaderView?, modifier: Modifier = Modifier) {
    if (reader == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在打开文档…", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
        }
        return
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { reader.also(activity::registerLegacy) },
        update = { activity.registerLegacy(it) }
    )
    DisposableEffect(reader) { onDispose { activity.registerLegacy(null) } }
}

@Composable
private fun DocumentTool(
    title: String,
    subtitle: String,
    accent: Color,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp),
        shape = RoundedCornerShape(20.dp),
        tint = if (selected) accent else null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        elevation = if (selected) 9.dp else 4.dp,
        onClick = onClick
    ) {
        Column {
            Text(title, color = if (selected) Color.White else accent, fontWeight = FontWeight.Black)
            Text(subtitle, color = if (selected) Color.White.copy(alpha = 0.78f) else secondaryText(), fontSize = 10.sp)
        }
    }
}

/** 已挂载旧版视图的槽位（普通持有者，避免 update 时写入 Compose state 触发额外重组）。 */
private class LegacySlot { var view: View? = null }

@Composable
private fun LegacyHost(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    revision: Int = 0,
    factory: () -> View
) {
    val slot = remember { LegacySlot() }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { factory().also { slot.view = it; activity.registerLegacy(it) } },
        update = { activity.registerLegacy(it) }
    )
    // revision 变化（如导入/恢复完成）时刷新书架；首帧沿用初始 revision，不重复加载
    val initialRevision = remember { revision }
    LaunchedEffect(revision) {
        if (revision != initialRevision) (slot.view as? ShelfView)?.refresh()
    }
    DisposableEffect(Unit) { onDispose { activity.registerLegacy(null) } }
}

@Composable
private fun WorkbenchScreen(
    activity: MainActivity,
    revision: Int,
    showIllustrations: Boolean,
    onNavigate: (Destination) -> Unit
) {
    val db = remember { Db(activity) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<WorkbenchSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var showAiIntro by remember { mutableStateOf(false) }
    LaunchedEffect(revision, reloadTick) {
        loading = true
        error = null
        withContext(Dispatchers.IO) {
            runCatching {
                WorkbenchSnapshot(
                    books = db.listBooks(),
                    noteCount = db.noteCount(),
                    minutes = db.totalAllReadMs() / 60_000,
                    // AI 是否可用只在 IO 线程探测一次（含一次 Keystore 读取），入口卡片据此显示状态
                    aiConfigured = AiClient.isReady(AiClient.config(db)),
                    aiIntroSeen = db.getSetting(AI_INTRO_SEEN_KEY) == "1"
                )
            }
        }
            .onSuccess { snapshot = it }
            .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
        loading = false
    }
    val books = snapshot?.books.orEmpty()
    val reading = books.firstOrNull { isReadingProgress(it.progress) } ?: books.firstOrNull()
    val recent = books.take(8)
    val noteCount = snapshot?.noteCount ?: 0
    val minutes = snapshot?.minutes ?: 0
    val failure = error

    /** 首次进入 AI 设置前先说明 BYOK：费用与数据流向讲清楚，再带用户去配置。 */
    fun openAiSettings() {
        if (snapshot?.aiIntroSeen != true) showAiIntro = true else onNavigate(Destination.Settings)
    }

    fun markIntroSeen() {
        snapshot = snapshot?.copy(aiIntroSeen = true)
        scope.launch { withContext(Dispatchers.IO) { db.setSetting(AI_INTRO_SEEN_KEY, "1") } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 1180.dp) 1180.dp else maxWidth
        val horizontal = if (maxWidth >= 700.dp) 28.dp else 16.dp
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal, 18.dp, horizontal, 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { WorkbenchHeader() }
            when {
                failure != null -> item { WorkbenchErrorCard(failure) { reloadTick++ } }
                loading -> item { WorkbenchLoadingCard() }
                else -> {
                    item { HeroCard(reading, activity, showIllustrations) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("${books.size}", "藏书与资料", ElectricBlue, Modifier.weight(1f))
                            MetricCard("$noteCount", "笔记", ActiveViolet, Modifier.weight(1f))
                            MetricCard("$minutes", "阅读分钟", LuminousCyan, Modifier.weight(1f))
                        }
                    }
                    item {
                        SectionHeader("快速开始", "所有 AI 操作都由你主动触发")
                        Spacer(Modifier.height(11.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickAction("＋", "导入资料", "PDF · PPTX · DOCX", ElectricBlue, Modifier.weight(1f)) { activity.importDocuments() }
                            QuickAction("▦", "打开书架", "日常阅读与管理", ActiveViolet, Modifier.weight(1f)) { onNavigate(Destination.Shelf) }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 书架里叫「AI 设置」的入口藏在「更多」里；工作台给出同名入口，避免提示指向不存在的按钮
                        AiSettingsEntry(
                            configured = snapshot?.aiConfigured == true,
                            onOpen = ::openAiSettings
                        )
                    }
                    if (recent.isNotEmpty()) {
                        item { SectionHeader("最近内容", "继续小说，或打开刚收到的资料") }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(recent, key = { it.id }) { item -> RecentItemCard(item) { activity.openReader(item.id) } }
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { onNavigate(Destination.Stats) }) {
                            Text("查看完整阅读统计  →", color = ElectricBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showAiIntro) {
        AlertDialog(
            onDismissRequest = { showAiIntro = false },
            title = { Text("AI 功能需要你自己的接口", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 功能由你自己提供接口与 Key，费用由服务商收取；只有你点按的操作会发送所选范围。")
                    Text("页枢不内置账号，也不会自动上传书籍、笔记或阅读记录。", fontSize = 11.sp, color = secondaryText())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    markIntroSeen()
                    showAiIntro = false
                    onNavigate(Destination.Settings)
                }) { Text("去配置", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    markIntroSeen()
                    showAiIntro = false
                }) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun AiSettingsEntry(configured: Boolean, onOpen: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        elevation = 9.dp,
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(ElectricBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text("◇", fontSize = 20.sp, color = ElectricBlue, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("AI 设置", fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (configured) "接口与模型已就绪，可随时调整" else "填写接口地址、Key 和模型名后即可使用 AI",
                    fontSize = 10.sp,
                    color = secondaryText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GlassPill(color = if (configured) LuminousCyan else ActiveViolet) {
                Text(
                    if (configured) "已配置" else "未配置",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("→", color = ElectricBlue, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorkbenchLoadingCard() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(30.dp),
        elevation = 12.dp
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在读取你的书架…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("本地数据较多时可能需要几秒", color = secondaryText(), fontSize = 11.sp)
        }
    }
}

@Composable
private fun NotesHubLoadingCard() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(30.dp),
        elevation = 12.dp
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在读取本地笔记…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("笔记较多时可能需要几秒", color = secondaryText(), fontSize = 11.sp)
        }
    }
}

@Composable
private fun WorkbenchErrorCard(message: String, title: String = "无法读取本地书架", onRetry: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        contentPadding = PaddingValues(24.dp),
        elevation = 12.dp
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text("数据库可能正在迁移或已损坏：$message", color = secondaryText(), fontSize = 11.sp)
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) { Text("重试") }
        }
    }
}

@Composable
private fun WorkbenchHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(52)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("页枢", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("读书，也读懂资料", color = secondaryText())
        }
        GlassPill(color = LuminousCyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(LuminousCyan))
                Spacer(Modifier.width(6.dp))
                Text("本地优先", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HeroCard(reading: LibraryItem?, activity: MainActivity, showIllustrations: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 680.dp && showIllustrations
        val cardHeight = if (roomy) 286.dp else 266.dp
        val artWidth = if (roomy) 310.dp else maxWidth * 0.37f
        GlassPanel(
            modifier = Modifier.fillMaxWidth().height(cardHeight),
            shape = RoundedCornerShape(32.dp),
            elevation = 24.dp
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(start = if (roomy) 28.dp else 20.dp, top = 22.dp, end = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        GlassPill(modifier = Modifier.widthIn(max = 136.dp), color = ElectricBlue) {
                            Text(if (reading == null) "你的私人书架" else "继续阅读", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ElectricBlue)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            reading?.title ?: "从一本书，抵达更多理解",
                            style = if (roomy) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            if (reading == null) "书籍、讲义与图片，都安静地留在你的设备里。" else "${reading.format.uppercase()}  ·  已读 ${(reading.progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = secondaryText(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (reading != null) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                Modifier.fillMaxWidth(if (roomy) 0.78f else 0.92f).height(5.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(reading.progress.coerceIn(0.02f, 1f)).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(ElectricBlue, ActiveViolet)))
                                )
                            }
                        }
                    }
                    HeroButton(if (reading == null) "导入内容" else "继续阅读") {
                        if (reading == null) activity.importDocuments() else activity.openReader(reading.id)
                    }
                }
                if (showIllustrations) {
                    Box(
                        Modifier.width(artWidth).fillMaxHeight().padding(top = 9.dp, end = 9.dp, bottom = 9.dp).clip(RoundedCornerShape(26.dp))
                    ) {
                        SampledResourceImage(
                            resource = R.drawable.yeshu_art_festival,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            sampleSize = 2,
                            alignment = BiasAlignment(0f, -0.12f)
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent, InkNavy.copy(alpha = 0.16f)))
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(ElectricBlue, ActiveViolet)))
            .clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text("→", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BrandMark(size: Int) {
    GlassPanel(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape((size * 0.30f).dp),
        tint = ElectricBlue,
        elevation = 10.dp
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.76f), ActiveViolet.copy(alpha = 0.68f), LuminousCyan.copy(alpha = 0.54f)))
            ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_yeshu_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding((size * 0.06f).dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun MetricCard(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        elevation = 8.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(7.dp))
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = accent)
            }
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickAction(glyph: String, title: String, subtitle: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(15.dp),
        elevation = 9.dp,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(15.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text(glyph, fontSize = 22.sp, color = accent, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, fontSize = 10.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SampledResourceImage(
    @DrawableRes resource: Int,
    modifier: Modifier,
    contentScale: ContentScale,
    sampleSize: Int,
    alignment: Alignment
) {
    val resources = LocalContext.current.resources
    var bitmap by remember(resource, sampleSize) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(resource, sampleSize) {
        bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(
                resources,
                resource,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
            )?.asImageBitmap()
        }
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment
        )
    }
}

@Composable
private fun RecentItemCard(item: LibraryItem, onClick: () -> Unit) {
    GlassPanel(
        modifier = Modifier.width(184.dp).height(158.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(15.dp),
        elevation = 8.dp,
        onClick = onClick
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (item.itemType == "book") ElectricBlue.copy(alpha = 0.14f) else ActiveViolet.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (item.itemType == "book") "书" else item.format.uppercase().take(3),
                    fontWeight = FontWeight.Black,
                    color = if (item.itemType == "book") ElectricBlue else ActiveViolet
                )
            }
            Column {
                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("${(item.progress * 100).toInt()}%  ·  ${relativeTime(item.lastReadAt)}", fontSize = 10.sp, color = secondaryText(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(subtitle, fontSize = 11.sp, color = secondaryText())
    }
}

/** 可点击的引用锚点：来自成果的 citationsJson，或笔记的 anchor 字段。 */
private data class CitationAnchor(val token: String, val label: String)

/** 笔记中枢里的 AI 成果条目：锚点预先解析好，避免每次重组都解析一遍 citationsJson。 */
private data class ArtifactRow(
    val artifact: AiArtifact,
    val bookTitle: String?,
    val anchors: List<CitationAnchor>,
    val hasNote: Boolean,
    /** 原书已被软删除（在回收站里）。此时既不能跳转，也不该说成「已不在书架」。 */
    val bookInRecycleBin: Boolean = false
) {
    /** 只有书目仍在书架里时才能跳转过去；bookId = 0、书在回收站或已彻底删除时为 null。 */
    val sourceBookId: Long? get() = artifact.bookId.takeIf { it != 0L && bookTitle != null }

    /** 卡片上的来源说明：区分「在回收站」与「已删除」，前者可以一键恢复。 */
    val sourceHint: String
        get() = when {
            sourceBookId != null -> "已在笔记列表中"
            bookInRecycleBin -> "原书在回收站，可恢复后跳转"
            bookTitle == null && artifact.bookId != 0L -> "原书已删除"
            else -> "已在笔记列表中"
        }
}

/** 笔记中枢一次加载的完整数据：笔记（带出处锚点）、书签/重点与全局 AI 成果。 */
private data class NotesHubData(
    val notes: List<Pair<Db.NoteDetail, Book?>>,
    val artifacts: List<ArtifactRow>,
    /** 书签与划过的重点。没有这一区，用户在阅读器里做的标记就再也找不回来。 */
    val marks: List<MarkRow>
)

/** 笔记中枢「标记」区的一行：书签或高亮，点击回到原文位置。 */
private data class MarkRow(
    val key: String,
    val title: String,
    val badge: String,
    val body: String,
    val accent: Color,
    val bookId: Long,
    val anchor: String
)

/** 详情面板要展示的一条内容，笔记与 AI 成果共用。 */
private data class DetailContent(
    val title: String,
    val badge: String,
    val meta: String,
    val body: String,
    val anchors: List<CitationAnchor>,
    /** 为空表示没有可跳转的原书（全局阅读报告，或书目已被删除）。 */
    val bookId: Long?,
    /** 仅在成果还没有对应笔记时提供「保存为笔记」。 */
    val saveAsNoteArtifact: AiArtifact? = null
)

@Composable
private fun NotesHubScreen(activity: MainActivity, revision: Int) {
    val db = remember { Db(activity) }
    val scope = rememberCoroutineScope()
    var localRevision by remember { mutableStateOf(0) }
    // 笔记、书目与 AI 成果在 IO 线程一次性读取后再交给界面：Room 开启了 allowMainThreadQueries，
    // 直接在组合阶段查询会让每次重组都在 UI 线程跑好几条查询。刷新期间保留上一次结果，避免闪白。
    var data by remember { mutableStateOf<NotesHubData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<DetailContent?>(null) }
    var pendingDelete by remember { mutableStateOf<Db.NoteDetail?>(null) }
    // 笔记多起来以后「按时间」很难复习同一本书：提供按书聚合的视图
    var groupByBook by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(revision, localRevision, reloadTick) {
        error = null
        withContext(Dispatchers.IO) {
            runCatching {
                val books = db.listBooks().associateBy { it.id }
                // 回收站里的书：成果卡要据此提示「原书在回收站」并提供恢复
                val deletedBooks = db.listDeletedBooks().associateBy { it.id }
                val notes = db.recentNoteDetails(80).mapNotNull { note ->
                    val book = books[note.bookId]
                    // bookId = 0 是全局阅读报告，不属于任何一本书，不能因为查不到书就丢掉。
                    if (book == null && note.bookId != 0L) null else note to book
                }
                val marks = buildList {
                    db.recentBookmarks(120).forEach { mark ->
                        val book = books[mark.bookId] ?: return@forEach
                        add(
                            MarkRow(
                                key = "bm-${mark.id}",
                                title = book.title,
                                badge = "书签",
                                body = mark.excerpt.ifBlank { mark.label },
                                accent = ElectricBlue,
                                bookId = mark.bookId,
                                anchor = mark.anchor
                            )
                        )
                    }
                    notes.filter { (note, _) -> note.kind == "highlight" }.forEach { (note, book) ->
                        if (book == null) return@forEach
                        add(
                            MarkRow(
                                key = "hl-${note.id}",
                                title = book.title,
                                badge = "重点",
                                body = cleanNoteContent(note.content),
                                accent = ActiveViolet,
                                bookId = note.bookId,
                                anchor = note.anchor
                            )
                        )
                    }
                }
                NotesHubData(
                    notes = notes,
                    // ai_artifacts 以前只有 AI 内部缓存会读：对话框一关，成果就再也没有入口
                    artifacts = db.listAllArtifacts().map { artifact ->
                        ArtifactRow(
                            artifact = artifact,
                            bookTitle = books[artifact.bookId]?.title,
                            bookInRecycleBin = deletedBooks.containsKey(artifact.bookId),
                            anchors = parseCitations(artifact.citationsJson),
                            hasNote = notes.any { (note, _) ->
                                note.bookId == artifact.bookId &&
                                    note.kind == artifact.kind &&
                                    note.content == artifact.content
                            }
                        )
                    },
                    marks = marks
                )
            }
        }
            .onSuccess { data = it }
            .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
    }

    /** 删除笔记时一并清理它对应的 AI 成果；撤销时把两者都原样恢复。 */
    fun deleteWithUndo(note: Db.NoteDetail) {
        pendingDelete = null
        scope.launch {
            val removedArtifact = withContext(Dispatchers.IO) { db.deleteNoteWithArtifact(note.id) }
            localRevision++
            val result = snackbarHostState.showSnackbar(
                message = if (removedArtifact != null) "笔记与对应的 AI 成果已删除" else "笔记已删除",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) {
                    // 带上原 id、createdAt 与出处锚点，否则撤销后的笔记会拿到新 id 与时间戳，
                    // 在按 id DESC 排序的列表里跳到最前面，锚点也会丢失。
                    db.addNote(note.bookId, note.kind, note.content, note.id, note.createdAt, note.anchor, note.status)
                    removedArtifact?.let { db.saveArtifact(it) }
                }
                localRevision++
            }
        }
    }

    /** 打开笔记详情：先用列表里的数据即时上屏，再补上成果里的引用锚点。 */
    fun openNoteDetail(note: Db.NoteDetail, book: Book?) {
        val immediate = detailContentOf(note, book, emptyList())
        detail = immediate
        scope.launch {
            val enriched = withContext(Dispatchers.IO) {
                val fresh = db.getNoteDetail(note.id) ?: note
                val artifact = db.listArtifacts(fresh.bookId)
                    .firstOrNull { it.kind == fresh.kind && it.content == fresh.content }
                detailContentOf(fresh, book, parseCitations(artifact?.citationsJson.orEmpty()))
            }
            // 用户已经关掉面板或切到了别的详情时不要覆盖
            if (detail == immediate) detail = enriched
        }
    }

    /** 把只存在于缓存里的成果补一条笔记，让它在笔记列表里也有入口。 */
    fun saveArtifactAsNote(artifact: AiArtifact) {
        scope.launch {
            withContext(Dispatchers.IO) { db.ensureNoteForArtifact(artifact) }
            detail = null
            localRevision++
        }
    }

    val loaded = data
    val failure = error
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 900.dp) 900.dp else maxWidth
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(18.dp, 20.dp, 18.dp, 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenHeader("NOTES", "笔记中枢", "阅读摘记、AI 摘要和问答都汇在这里") }
            when {
                failure != null -> item { WorkbenchErrorCard(failure, "无法读取本地笔记") { reloadTick++ } }
                loaded == null -> item { NotesHubLoadingCard() }
                loaded.notes.isEmpty() && loaded.artifacts.isEmpty() && loaded.marks.isEmpty() -> item {
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        contentPadding = PaddingValues(30.dp),
                        elevation = 12.dp
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(54.dp).clip(RoundedCornerShape(19.dp)).background(ElectricBlue.copy(alpha = 0.13f)),
                                contentAlignment = Alignment.Center
                            ) { Text("✎", fontSize = 27.sp, color = ElectricBlue) }
                            Spacer(Modifier.height(12.dp))
                            Text("笔记还在等第一句话", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("打开书籍或资料，摘录重点、生成摘要或记录想法", color = secondaryText(), fontSize = 12.sp)
                        }
                    }
                }
                else -> {
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !groupByBook,
                                onClick = { groupByBook = false },
                                label = { Text("按时间") },
                                shape = RoundedCornerShape(16.dp)
                            )
                            FilterChip(
                                selected = groupByBook,
                                onClick = { groupByBook = true },
                                label = { Text("按书（${loaded.notes.map { it.first.bookId }.distinct().size}）") },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                    if (groupByBook) {
                        // 按书聚合：每本书一个组头（书名 + 条数），组内仍按时间倒序
                        val grouped = loaded.notes.groupBy { it.first.bookId }
                            .toList()
                            .sortedByDescending { (_, items) -> items.maxOf { it.first.createdAt } }
                        grouped.forEach { (bookId, items) ->
                            val bookTitle = items.firstOrNull()?.second?.title
                                ?: if (bookId == 0L) "阅读报告" else "已删除的书目"
                            item(key = "group-$bookId") {
                                SectionHeader(bookTitle, "${items.size} 条 · ${relativeTime(items.first().first.createdAt)}")
                            }
                            items(items, key = { "gnote-${it.first.id}" }) { (note, book) ->
                                NoteHubCard(
                                    title = bookTitle,
                                    kind = noteKindLabel(note.kind, note.content),
                                    content = cleanNoteContent(note.content),
                                    expandBody = false,
                                    sourceHint = if (note.bookId == 0L) null else "点击查看全文",
                                    onOpen = { openNoteDetail(note, book) },
                                    onDelete = { pendingDelete = note }
                                )
                            }
                        }
                    } else {
                        items(loaded.notes, key = { "note-${it.first.id}" }) { (note, book) ->
                            NoteHubCard(
                                title = book?.title ?: "阅读报告",
                                kind = noteKindLabel(note.kind, note.content),
                                content = cleanNoteContent(note.content),
                                // bookId = 0 的全局阅读报告不属于任何一本书：正文直接铺开，也没有可跳转的来源
                                expandBody = note.bookId == 0L,
                                sourceHint = if (note.bookId == 0L) null else "点击查看全文",
                                onOpen = { openNoteDetail(note, book) },
                                onDelete = { pendingDelete = note }
                            )
                        }
                    }
                    if (loaded.marks.isNotEmpty()) {
                        item { SectionHeader("标记", "书签与划过的重点，点击回到原文位置") }
                        items(loaded.marks, key = { it.key }) { mark ->
                            MarkHubCard(mark) { activity.openReader(mark.bookId, mark.anchor) }
                        }
                    }
                    if (loaded.artifacts.isNotEmpty()) {
                        item { SectionHeader("AI 成果", "生成过的理解包，关闭对话框后仍可回看") }
                        items(loaded.artifacts, key = { "artifact-${it.artifact.id}" }) { row ->
                            ArtifactHubCard(
                                row = row,
                                onOpen = {
                                    detail = DetailContent(
                                        title = row.bookTitle ?: if (row.bookInRecycleBin) "原书在回收站" else "已不在书架的书目",
                                        badge = NoteKindLabels.label(row.artifact.kind),
                                        meta = "模型 ${row.artifact.model.ifBlank { "未知" }}  ·  ${relativeTime(row.artifact.updatedAt)}",
                                        body = row.artifact.content,
                                        anchors = row.anchors,
                                        bookId = row.sourceBookId,
                                        saveAsNoteArtifact = row.artifact.takeIf { !row.hasNote }
                                    )
                                },
                                onOpenSource = row.sourceBookId?.let { id ->
                                    { anchorToken: String ->
                                        detail = null
                                        activity.openReader(id, anchorToken)
                                    }
                                },
                                onSaveAsNote = { saveArtifactAsNote(row.artifact) },
                                onRestoreSource = if (row.bookInRecycleBin) {
                                    {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                db.restoreDeletedBook(row.artifact.bookId)
                                            }
                                            localRevision++
                                        }
                                    }
                                } else {
                                    null
                                },
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.deleteArtifact(row.artifact.id) }
                                        localRevision++
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
        detail?.let { request ->
            DetailSheet(
                request = request,
                onOpenSource = { anchorToken: String ->
                    request.bookId?.let { id ->
                        detail = null
                        activity.openReader(id, anchorToken)
                    }
                },
                onSaveAsNote = request.saveAsNoteArtifact?.let { artifact -> { saveArtifactAsNote(artifact) } },
                onDismiss = { detail = null }
            )
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条笔记？", fontWeight = FontWeight.Black) },
            text = { Text("删除后可在底部提示中撤销。若它来自 AI 生成，对应的 AI 成果也会一并删除，撤销时会一起恢复。") },
            confirmButton = {
                TextButton(
                    onClick = { deleteWithUndo(note) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除笔记", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 笔记详情内容：状态为 unvalidated 时提示“未通过校验但已保留”。 */
private fun detailContentOf(
    note: Db.NoteDetail,
    book: Book?,
    extraAnchors: List<CitationAnchor>
): DetailContent = DetailContent(
    title = book?.title ?: "阅读报告",
    badge = noteKindLabel(note.kind, note.content),
    meta = relativeTime(note.createdAt) + when (note.status) {
        "unvalidated" -> "  ·  校验未通过，已保留"
        "edited" -> "  ·  已手动编辑"
        else -> ""
    },
    body = cleanNoteContent(note.content),
    anchors = (noteAnchors(note.anchor) + extraAnchors).distinct(),
    bookId = book?.id
)

/** 解析成果 citationsJson（[{token,type,index,label,excerpt}]）为可点击锚点；坏数据直接忽略。 */
private fun parseCitations(json: String): List<CitationAnchor> = runCatching {
    val array = JSONArray(json)
    buildList<CitationAnchor> {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val token = item.optString("token").trim()
            if (token.isBlank()) continue
            add(CitationAnchor(token, item.optString("label").trim().ifBlank { token }))
        }
    }.distinct()
}.getOrDefault(emptyList())

/** 笔记 anchor 字段形如 CHAPTER:3 / PAGE:12，统一成 [CHAPTER:3] 形式展示。 */
private fun noteAnchors(anchor: String): List<CitationAnchor> =
    Regex("[A-Za-z]+\\s*:\\s*\\d+").findAll(anchor).map { match ->
        val normalized = match.value.replace(" ", "")
        CitationAnchor("[$normalized]", normalized)
    }.distinct().toList()

@Composable
private fun NoteHubCard(
    title: String,
    kind: String,
    content: String,
    expandBody: Boolean = false,
    /** 为空表示没有可跳转的来源（如 bookId = 0 的全局阅读报告），此时不显示来源提示。 */
    sourceHint: String? = null,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(if (pressed) 0.982f else 1f, label = "note-card-press")
    val haptics = LocalHapticFeedback.current

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpen()
                }
            ),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp),
        elevation = 8.dp
    ) {
        if (pressed) {
            Box(Modifier.matchParentSize().background(ElectricBlue.copy(alpha = 0.08f)))
        }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    color = ElectricBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                GlassPill(color = ActiveViolet) {
                    Text(kind, fontSize = 10.sp, color = ActiveViolet, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                content,
                // 没有来源可跳转的全局报告直接在卡片里读全文，不再截成三行
                maxLines = if (expandBody) Int.MAX_VALUE else 3,
                overflow = if (expandBody) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (sourceHint != null) {
                    Text(sourceHint, modifier = Modifier.weight(1f), fontSize = 11.sp, color = secondaryText())
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 标记卡片：书签/重点共用，点按跳回原文的精确位置。 */
@Composable
private fun MarkHubCard(mark: MarkRow, onOpen: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp),
        elevation = 6.dp,
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(mark.accent))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mark.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        color = mark.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    GlassPill(color = mark.accent) {
                        Text(mark.badge, fontSize = 10.sp, color = mark.accent, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    mark.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("→", color = mark.accent, fontWeight = FontWeight.Bold)
        }
    }
}

/** AI 成果卡片：kind、模型、时间与引用锚点都直接可见，不必再打开对话框找。 */
@Composable
private fun ArtifactHubCard(
    row: ArtifactRow,
    onOpen: () -> Unit,
    onOpenSource: ((String) -> Unit)?,
    onSaveAsNote: () -> Unit,
    onRestoreSource: (() -> Unit)?,
    onDelete: () -> Unit
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(17.dp),
        elevation = 8.dp,
        onClick = onOpen
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.bookTitle ?: "已不在书架的书目",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    color = ActiveViolet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                GlassPill(color = ActiveViolet) {
                    Text(
                        NoteKindLabels.label(row.artifact.kind),
                        fontSize = 10.sp,
                        color = ActiveViolet,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "模型 ${row.artifact.model.ifBlank { "未知" }}  ·  ${relativeTime(row.artifact.updatedAt)}",
                fontSize = 10.sp,
                color = secondaryText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(9.dp))
            Text(
                cleanNoteContent(row.artifact.content),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
            )
            if (row.anchors.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.anchors.take(8).forEach { anchor -> AnchorChip(anchor, onOpenSource) }
                    if (row.anchors.size > 8) {
                        Text("+${row.anchors.size - 8}", fontSize = 10.sp, color = secondaryText())
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.hasNote) "${row.sourceHint}；删除笔记会一并删除成果" else row.sourceHint,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = secondaryText(),
                    maxLines = 2
                )
                // 原书在回收站时先给「恢复原书」，否则用户只能看着成果点不进去
                if (onRestoreSource != null) {
                    TextButton(onClick = onRestoreSource) {
                        Text("恢复原书", color = ElectricBlue, fontWeight = FontWeight.Bold)
                    }
                }
                if (!row.hasNote) {
                    TextButton(onClick = onSaveAsNote) {
                        Text("保存为笔记", color = ActiveViolet, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 引用锚点：有原书时点击跳转到那本书的对应位置（anchor 交给 ReaderView 定位）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnchorChip(anchor: CitationAnchor, onClick: ((String) -> Unit)?) {
    AssistChip(
        onClick = { onClick?.invoke(anchor.token) },
        enabled = onClick != null,
        label = { Text(anchor.label, fontSize = 11.sp, maxLines = 1) },
        shape = RoundedCornerShape(14.dp)
    )
}

/** 笔记与 AI 成果共用的详情面板：完整正文、引用锚点与来源跳转。 */
@Composable
private fun DetailSheet(
    request: DetailContent,
    onOpenSource: (String) -> Unit,
    onSaveAsNote: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss)
    ) {
        GlassPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 面板自己吃掉点击，否则点在正文上会被蒙层当成“点击关闭”
                .clickable(interactionSource = panelInteraction, indication = null) { }
                .padding(horizontal = 10.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(18.dp),
            elevation = 24.dp
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        request.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassPill(color = ActiveViolet) {
                        Text(request.badge, fontSize = 10.sp, color = ActiveViolet, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(request.meta, fontSize = 10.sp, color = secondaryText())
                if (request.anchors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        request.anchors.forEach { anchor ->
                            AnchorChip(anchor, if (request.bookId != null) onOpenSource else null)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    request.body,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.bookId != null) {
                        OutlinedButton(
                            // 没有具体锚点时只打开原书；有则跳到第一个引用位置
                            onClick = { onOpenSource(request.anchors.firstOrNull()?.token.orEmpty()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("打开原书位置") }
                    }
                    if (onSaveAsNote != null) {
                        OutlinedButton(
                            onClick = onSaveAsNote,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("保存为笔记") }
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) { Text("关闭") }
                }
            }
        }
    }
}

private fun noteKindLabel(kind: String, content: String): String =
    // 统一使用 NoteKindLabels 的权威映射（含 AI 书籍简介 "intro"），仅 chat 按内容区分提问/回复
    if (kind == "chat") {
        if (content.startsWith("U:")) "我的提问" else "AI 回复"
    } else {
        NoteKindLabels.label(kind)
    }

private fun cleanNoteContent(content: String): String =
    content.removePrefix("U:").removePrefix("A:").trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: MainActivity) {
    val db = remember { Db(activity) }
    // 打开设置需要 Keystore 解密 + 多次 Room 查询，全部放到 IO 线程后再进入界面，
    // 避免阻塞首帧；加载完成前不渲染表单，因此不会闪出“未配置”的错误状态。
    var bootstrap by remember { mutableStateOf<SettingsBootstrap?>(null) }
    LaunchedEffect(Unit) {
        bootstrap = withContext(Dispatchers.IO) {
            val profiles = AiProfileStore.list(db)
            val profile = AiProfileStore.active(db)
            val keyState = db.aiKeyState(profile.id, profile.baseUrl)
            SettingsBootstrap(
                profiles = profiles,
                profile = profile,
                hasSavedKey = keyState == KeyState.OK,
                keyState = keyState,
                hasUnboundKey = db.hasUnboundAiKey(),
                // ReaderView 以 "0" 表示关闭音量键翻页，其余（含未写入）视为开启
                volumeKeyFlip = db.getSetting("reader_volume_flip") != "0"
            )
        }
    }
    val boot = bootstrap
    if (boot == null) {
        SettingsLoadingCard()
        return
    }

    var profiles by remember { mutableStateOf(boot.profiles) }
    var activeProfileId by remember { mutableStateOf(boot.profile.id) }
    var profileName by remember { mutableStateOf(boot.profile.name) }
    var baseUrl by remember { mutableStateOf(boot.profile.baseUrl) }
    var textModel by remember { mutableStateOf(boot.profile.textModel) }
    var visionModel by remember { mutableStateOf(boot.profile.visionModel) }
    var chatPath by remember { mutableStateOf(boot.profile.chatPath) }
    var modelsPath by remember { mutableStateOf(boot.profile.modelsPath) }
    var authHeader by remember { mutableStateOf(boot.profile.authHeader) }
    var authPrefix by remember { mutableStateOf(boot.profile.authPrefix) }
    var keyInput by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(boot.hasSavedKey) }
    var keyState by remember { mutableStateOf(boot.keyState) }
    var keyLoading by remember { mutableStateOf(false) }
    var hasUnboundKey by remember { mutableStateOf(boot.hasUnboundKey) }
    var volumeKeyFlip by remember { mutableStateOf(boot.volumeKeyFlip) }
    var allowPrivateHttp by remember { mutableStateOf(boot.profile.allowPrivateHttp) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelPickerTarget by remember { mutableStateOf<String?>(null) }
    var modelDraft by remember { mutableStateOf("") }
    var confirmDeleteProfile by remember { mutableStateOf(false) }
    // 有未保存修改时挂起的动作：参数为真表示先把草稿保存到当前配置，再执行原动作
    var pendingDirtyAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var pendingDuplicate by remember { mutableStateOf(false) }
    var visionShowAll by remember { mutableStateOf(false) }
    var presetHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyLoadToken = remember { intArrayOf(0) }
    val themeMode by activity.userPreferences.themeMode.collectAsStateWithLifecycle(initialValue = "system")
    val showIllustrations by activity.userPreferences.showIllustrations.collectAsStateWithLifecycle(initialValue = true)

    /** Keystore 解密放到 IO 线程；token 防止快速切换时旧结果覆盖新状态。 */
    fun refreshSavedKey(url: String) {
        keyLoading = true
        keyLoadToken[0] += 1
        val token = keyLoadToken[0]
        val profileId = activeProfileId
        scope.launch {
            // 状态而不是「密文是否存在」：解不开的旧 Key 必须让用户看到「需要重新填写」，
            // 否则界面会一直显示「已安全保存」，直到请求被 401 拒绝才暴露。
            val state = withContext(Dispatchers.IO) { db.aiKeyState(profileId, url) }
            if (token == keyLoadToken[0]) {
                keyState = state
                hasSavedKey = state == KeyState.OK
                keyLoading = false
            }
        }
    }

    fun draftProfile() = SavedAiProfile(
        id = activeProfileId,
        name = profileName,
        baseUrl = baseUrl,
        textModel = textModel,
        visionModel = visionModel,
        chatPath = chatPath,
        modelsPath = modelsPath,
        authHeader = authHeader,
        authPrefix = authPrefix,
        allowPrivateHttp = allowPrivateHttp,
        // 已发现的模型随配置一起保存：切换配置或重进设置页都不必重新联网读取
        models = discoveredModels
    )

    /** 草稿与已保存配置是否有差异（只填了 Key 还没保存也算）。 */
    fun isDirty(): Boolean {
        val stored = profiles.firstOrNull { it.id == activeProfileId }
        return keyInput.isNotBlank() || (stored != null && draftProfile() != stored)
    }

    fun loadProfile(profile: SavedAiProfile) {
        activeProfileId = profile.id
        profileName = profile.name
        baseUrl = profile.baseUrl
        textModel = profile.textModel
        visionModel = profile.visionModel
        chatPath = profile.chatPath
        modelsPath = profile.modelsPath
        authHeader = profile.authHeader
        authPrefix = profile.authPrefix
        allowPrivateHttp = profile.allowPrivateHttp
        keyInput = ""
        discoveredModels = profile.models
        visionShowAll = false
        presetHint = null
        refreshSavedKey(profile.baseUrl)
    }

    /**
     * 保存草稿：AiProfileStore 会读写 Room 并可能触发 Keystore 生成/解密，
     * 因此整段放到 IO 线程执行，返回后再在主线程更新界面状态。
     */
    suspend fun saveDraft(activate: Boolean = true): Result<SavedAiProfile> {
        // 组合状态必须在主线程读取，先取出快照再切线程
        val draft = draftProfile()
        val pendingKey = keyInput.takeIf { it.isNotBlank() }
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                AiProfileStore.save(db = db, raw = draft, key = pendingKey, activate = activate)
            }
        }
        val profile = saved.getOrNull()
        if (profile != null) {
            if (pendingKey != null) keyInput = ""
            profiles = withContext(Dispatchers.IO) { AiProfileStore.list(db) }
            loadProfile(profile)
        }
        return saved
    }

    fun applyPickedModel(rawModel: String) {
        val target = modelPickerTarget ?: return
        val chosen = rawModel.trim()
        if (target == "text" && chosen.isBlank()) {
            status = "文本模型不能为空"
            return
        }
        val updated = if (target == "vision") {
            draftProfile().copy(visionModel = chosen)
        } else {
            draftProfile().copy(textModel = chosen)
        }
        // 超长的模型名直接拒绝，不静默截断成另一个名字
        AiProfileStore.lengthError(updated)?.let {
            status = "保存模型失败：$it"
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiProfileStore.save(db, updated, activate = true) }
            }
            val saved = result.getOrNull()
            if (saved == null) {
                status = "保存模型失败：${profileErrorMessage(result.exceptionOrNull()!!)}"
                return@launch
            }
            if (target == "vision") visionModel = saved.visionModel else textModel = saved.textModel
            profiles = withContext(Dispatchers.IO) { AiProfileStore.list(db) }
            activeProfileId = saved.id
            modelPickerTarget = null
            modelDraft = ""
            status = if (target == "vision" && chosen.isBlank()) {
                "视觉模型已清空"
            } else {
                "已选择并保存模型：$chosen"
            }
        }
    }

    /** 有未保存修改时先问一次，避免切换/新建/复制把半成品静默写进原配置。 */
    fun withDirtyCheck(action: (saveDraftFirst: Boolean) -> Unit) {
        if (isDirty()) pendingDirtyAction = action else action(false)
    }

    /** 切换当前调用配置；[saveDraftFirst] 为真时先把草稿保存到原来的配置。 */
    suspend fun activateProfile(target: SavedAiProfile, saveDraftFirst: Boolean) {
        if (saveDraftFirst) {
            val saved = saveDraft(activate = false)
            if (saved.isFailure) {
                status = "切换失败：${profileErrorMessage(saved.exceptionOrNull()!!)}"
                return
            }
        }
        val activated = withContext(Dispatchers.IO) { AiProfileStore.setActive(db, target.id) }
        if (activated == null) {
            status = "切换失败：该配置已不存在"
            return
        }
        loadProfile(activated)
        status = "已切换到 ${activated.name}"
    }

    /** 新建空白配置；[saveDraftFirst] 为真时先把草稿保存到原来的配置。 */
    suspend fun createProfile(saveDraftFirst: Boolean) {
        if (saveDraftFirst) {
            val saved = saveDraft(activate = false)
            if (saved.isFailure) {
                status = "新建失败：${profileErrorMessage(saved.exceptionOrNull()!!)}"
                return
            }
        }
        val result = withContext(Dispatchers.IO) { runCatching { AiProfileStore.create(db, "新配置") } }
        val created = result.getOrNull()
        if (created == null) {
            status = "新建失败：${profileErrorMessage(result.exceptionOrNull()!!)}"
            return
        }
        profiles = withContext(Dispatchers.IO) { AiProfileStore.list(db) }
        loadProfile(created)
        status = "已新建独立配置，请填写接口地址与模型"
    }

    /**
     * 复制当前配置。[copyKey] 决定是否把 API Key 一并复制到新配置，
     * 默认只复制接口元数据，避免同一个 Key 静默出现在两处。
     */
    suspend fun duplicateProfile(copyKey: Boolean) {
        val saved = saveDraft().getOrNull()
        if (saved == null) {
            status = "复制失败：当前配置未能保存"
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { AiProfileStore.duplicate(db, saved, copyKey = copyKey) }
        }
        val copy = result.getOrNull()
        if (copy == null) {
            status = "复制失败：${profileErrorMessage(result.exceptionOrNull()!!)}"
            return
        }
        profiles = withContext(Dispatchers.IO) { AiProfileStore.list(db) }
        loadProfile(copy)
        status = if (copyKey) "已复制配置及其 Key" else "已复制配置（未复制 Key，需要单独填写）"
    }

    if (modelPickerTarget != null) {
        val isVisionPicker = modelPickerTarget == "vision"
        val presetModels = AiProviders.presets.mapNotNull { preset ->
            (if (isVisionPicker) preset.visionModelHint else preset.textModelHint).takeIf(String::isNotBlank)
        }
        val currentModel = if (isVisionPicker) visionModel else textModel
        val modelOptions = (discoveredModels + presetModels + listOf(currentModel))
            .filter(String::isNotBlank)
            .distinct()
        // 视觉模型只列名字看起来支持图片输入的 id；命名不规则的模型用「显示全部」兜底
        val visionOptions = modelOptions.filter { VISION_MODEL_HINT.containsMatchIn(it) }
        val filtered = if (isVisionPicker && !visionShowAll && visionOptions.isNotEmpty()) visionOptions else modelOptions
        AlertDialog(
            onDismissRequest = { modelPickerTarget = null },
            title = { Text(if (isVisionPicker) "选择视觉模型" else "选择文本模型") },
            text = {
                Column(
                    modifier = Modifier.imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "直接输入任意模型 ID，或从接口返回与常用候选中选择。选择后立即保存到当前配置。",
                        fontSize = 11.sp,
                        color = secondaryText()
                    )
                    GlassTextField(modelDraft, { modelDraft = it }, "模型 ID")
                    if (isVisionPicker) {
                        Text(
                            if (!visionShowAll && visionOptions.isNotEmpty()) {
                                "只显示名称含 vl / vision / llava / omni 的模型（${visionOptions.size}/${modelOptions.size}）。"
                            } else {
                                "已列出全部候选（${modelOptions.size}）。名称含 vl / vision / llava / omni 的模型通常支持图片输入。"
                            },
                            fontSize = 10.sp,
                            color = secondaryText()
                        )
                        if (visionOptions.size < modelOptions.size) {
                            TextButton(onClick = { visionShowAll = !visionShowAll }) {
                                Text(if (visionShowAll) "只看视觉模型" else "显示全部")
                            }
                        }
                    }
                    if (filtered.isNotEmpty()) Text("候选模型（${filtered.size}）", fontWeight = FontWeight.Medium)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(filtered, key = { it }) { model ->
                            TextButton(
                                onClick = { applyPickedModel(model) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(model, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modelDraft.isNotBlank(),
                    onClick = { applyPickedModel(modelDraft) }
                ) { Text("使用此模型") }
            },
            dismissButton = {
                Row {
                    if (isVisionPicker && visionModel.isNotBlank()) {
                        TextButton(onClick = { applyPickedModel("") }) { Text("清空") }
                    }
                    TextButton(onClick = { modelPickerTarget = null }) { Text("取消") }
                }
            }
        )
    }

    if (confirmDeleteProfile) {
        // 删除后会激活相邻的配置：先算出是哪一份，确认框里就说清楚会切到哪个
        val index = profiles.indexOfFirst { it.id == activeProfileId }
        val fallback = if (index > 0) profiles.getOrNull(index - 1) else profiles.getOrNull(index + 1)
        val currentName = profiles.firstOrNull { it.id == activeProfileId }?.name ?: profileName
        AlertDialog(
            onDismissRequest = { confirmDeleteProfile = false },
            title = { Text("删除 AI 配置？") },
            text = {
                Text(
                    "将删除「$currentName」及其单独保存的 API Key，其他配置不受影响。" +
                        (fallback?.let { "删除后会自动切换到「${it.name}」。" } ?: "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { AiProfileStore.delete(db, activeProfileId) }
                        }
                        val next = result.getOrNull()
                        confirmDeleteProfile = false
                        if (next == null) {
                            status = "删除失败：${profileErrorMessage(result.exceptionOrNull()!!)}"
                            return@launch
                        }
                        profiles = withContext(Dispatchers.IO) { AiProfileStore.list(db) }
                        loadProfile(next)
                        status = "已删除「$currentName」，当前配置切换为「${next.name}」"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteProfile = false }) { Text("取消") } }
        )
    }

    // 切换/新建前发现草稿有改动：让用户决定写入原配置还是丢弃，绝不静默提交
    pendingDirtyAction?.let { action ->
        val currentName = profiles.firstOrNull { it.id == activeProfileId }?.name ?: profileName
        AlertDialog(
            onDismissRequest = { pendingDirtyAction = null },
            title = { Text("保存对「$currentName」的修改？", fontWeight = FontWeight.Black) },
            text = { Text("当前表单有未保存的修改。保存会写入「$currentName」；放弃则这些修改不会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDirtyAction = null
                    action(true)
                }) { Text("保存修改", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingDirtyAction = null
                        action(false)
                    }) { Text("放弃") }
                    TextButton(onClick = { pendingDirtyAction = null }) { Text("取消") }
                }
            }
        )
    }

    if (pendingDuplicate) {
        AlertDialog(
            onDismissRequest = { pendingDuplicate = false },
            title = { Text("复制配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("先保存当前表单，再复制成一份新配置。")
                    Text(
                        "默认只复制接口地址、路径与模型名；API Key 不会复制到新配置，需要在新配置里单独填写。",
                        fontSize = 11.sp,
                        color = secondaryText()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDuplicate = false
                    scope.launch { duplicateProfile(copyKey = false) }
                }) { Text("仅复制配置", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingDuplicate = false
                        scope.launch { duplicateProfile(copyKey = true) }
                    }) { Text("连 Key 一起复制") }
                    TextButton(onClick = { pendingDuplicate = false }) { Text("取消") }
                }
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 900.dp) 900.dp else maxWidth
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(18.dp, 20.dp, 18.dp, 38.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            item { ScreenHeader("PREFERENCES", "设置", "本地优先；AI 服务由你选择并付费") }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    contentPadding = PaddingValues(18.dp),
                    elevation = 12.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSectionTitle("外观", "◐", ActiveViolet)
                        Text("主题", fontWeight = FontWeight.Medium)
                        Text("应用界面主题；阅读器纸张与夜间模式可独立调整。", fontSize = 10.sp, color = secondaryText())
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = { scope.launch { activity.userPreferences.setThemeMode(mode) } },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("人物插画", fontWeight = FontWeight.Medium)
                                Text("仅显示透明人物切图，可随时关闭", fontSize = 10.sp, color = secondaryText())
                            }
                            Switch(
                                checked = showIllustrations,
                                onCheckedChange = { enabled ->
                                    scope.launch { activity.userPreferences.setShowIllustrations(enabled) }
                                }
                            )
                        }
                    }
                }
            }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    contentPadding = PaddingValues(18.dp),
                    elevation = 11.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSectionTitle("阅读", "▤", LuminousCyan)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("音量键翻页", fontWeight = FontWeight.Medium)
                                Text("阅读器内用音量键上下翻页；关闭后音量键恢复系统音量调节", fontSize = 10.sp, color = secondaryText())
                            }
                            Switch(
                                checked = volumeKeyFlip,
                                onCheckedChange = { enabled ->
                                    volumeKeyFlip = enabled
                                    // ReaderView 读取 settings 表的 reader_volume_flip，"0" 表示关闭
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.setSetting("reader_volume_flip", if (enabled) "1" else "0")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    contentPadding = PaddingValues(18.dp),
                    elevation = 14.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSectionTitle("AI 服务", "◇", ElectricBlue)
                        val effectiveProfile = profiles.firstOrNull { it.id == activeProfileId }
                        GlassPill(color = LuminousCyan) {
                            Text(
                                "当前调用：${effectiveProfile?.name ?: profileName} · " +
                                    effectiveProfile?.textModel.orEmpty().ifBlank { "未选择文本模型" },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text("已保存配置", fontWeight = FontWeight.Bold)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            profiles.forEach { profile ->
                                FilterChip(
                                    selected = profile.id == activeProfileId,
                                    onClick = {
                                        // 切走之前先问一次未保存的草稿，避免静默写进原配置
                                        if (profile.id != activeProfileId) {
                                            withDirtyCheck { saveFirst ->
                                                scope.launch { activateProfile(profile, saveFirst) }
                                            }
                                        }
                                    },
                                    label = { Text(profile.name) },
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    withDirtyCheck { saveFirst ->
                                        scope.launch { createProfile(saveFirst) }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp)
                            ) { Text("新建") }
                            OutlinedButton(
                                onClick = { pendingDuplicate = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp)
                            ) { Text("复制") }
                            OutlinedButton(
                                onClick = { confirmDeleteProfile = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp)
                            ) { Text("删除") }
                        }
                        GlassTextField(profileName, { profileName = it }, "配置名称")
                        Text("接口预设", fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AiProviders.presets.forEach { preset ->
                                AssistChip(
                                    onClick = {
                                        baseUrl = preset.baseUrl
                                        textModel = preset.textModelHint
                                        visionModel = preset.visionModelHint
                                        chatPath = AiClient.DEFAULT_CHAT_PATH
                                        modelsPath = AiClient.DEFAULT_MODELS_PATH
                                        authHeader = AiClient.DEFAULT_AUTH_HEADER
                                        authPrefix = AiClient.DEFAULT_AUTH_PREFIX
                                        // 局域网预设（如 Ollama）本身就走明文 HTTP，直接替用户打开开关
                                        allowPrivateHttp = preset.allowPrivateHttp
                                        presetHint = preset.hint.takeIf(String::isNotBlank)
                                        refreshSavedKey(preset.baseUrl)
                                        status = null
                                    },
                                    label = { Text(preset.name) },
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                        val ollamaHint = AiProviders.presets.firstOrNull { it.id == "ollama" }?.hint
                        val activeHint = presetHint ?: ollamaHint?.takeIf { baseUrl.contains(AiProviders.OLLAMA_PLACEHOLDER_HOST) }
                        activeHint?.let { hint ->
                            GlassPill(color = ActiveViolet) {
                                Text(hint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        GlassTextField(baseUrl, {
                            baseUrl = it
                            refreshSavedKey(it)
                            // 已发现的模型按配置保存：改地址不再清空列表，避免每次敲键都要重新联网读取
                        }, "Base URL（含 https://、端口和基础路径）")
                        Text(
                            "请求体协议：OpenAI Chat Completions。下面的接口路径、鉴权方式和模型均可独立填写。",
                            fontSize = 10.sp,
                            color = secondaryText()
                        )
                        ModelTextField(
                            value = textModel,
                            onValueChange = { textModel = it },
                            label = "文本模型（可手输任意名称）",
                            availableCount = discoveredModels.size,
                            onPick = { modelDraft = textModel; modelPickerTarget = "text" }
                        )
                        ModelTextField(
                            value = visionModel,
                            onValueChange = { visionModel = it },
                            label = "视觉模型（可留空）",
                            // 只统计看起来支持图片输入的模型，避免「选择 80」但只有 3 个真能用
                            availableCount = discoveredModels.count { VISION_MODEL_HINT.containsMatchIn(it) },
                            onPick = {
                                modelDraft = visionModel
                                visionShowAll = false
                                modelPickerTarget = "vision"
                            }
                        )
                        GlassTextField(chatPath, { chatPath = it }, "Chat 接口路径或完整 URL")
                        GlassTextField(modelsPath, { modelsPath = it }, "模型列表路径（留空则不读取）")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                GlassTextField(authHeader, { authHeader = it }, "鉴权 Header")
                            }
                            Box(Modifier.weight(1f)) {
                                GlassTextField(authPrefix, { authPrefix = it }, "Key 前缀")
                            }
                        }
                        Text(
                            "常见鉴权：Authorization + Bearer；Azure 可用 api-key 并把前缀留空；无鉴权服务可不填 Key。",
                            fontSize = 10.sp,
                            color = secondaryText()
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    when {
                                        keyLoading -> "API Key（正在读取…）"
                                        hasSavedKey -> "API Key（已安全保存，留空不修改）"
                                        keyState == KeyState.UNDECRYPTABLE ->
                                            "API Key（需要重新填写）"
                                        keyState == KeyState.ORIGIN_MISMATCH ->
                                            "API Key（已保存的 Key 属于其他服务地址）"
                                        else -> "API Key"
                                    }
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            colors = glassTextFieldColors()
                        )
                        // 状态提示必须说清「为什么」和「怎么办」，否则用户只知道请求失败
                        keyState.hint?.let { hint ->
                            GlassPill(color = MaterialTheme.colorScheme.error) {
                                Text(
                                    "$hint。请重新输入一次即可覆盖保存。",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (hasUnboundKey) {
                            GlassPanel(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                tint = ActiveViolet.copy(alpha = 0.08f),
                                contentPadding = PaddingValues(12.dp),
                                elevation = 0.dp
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("发现一个未绑定服务商的旧 Key", fontWeight = FontWeight.Bold, color = ActiveViolet)
                                    Text(
                                        "为避免误发给其他服务商，它不会自动使用。确认当前地址属于原服务商后再绑定。",
                                        fontSize = 10.sp,
                                        color = secondaryText()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val bound = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        if (db.bindUnboundAiKey(baseUrl) == true) {
                                                            val migratedKey = db.getAiKey(baseUrl)
                                                            if (migratedKey.isNotBlank()) {
                                                                db.setAiKey(activeProfileId, migratedKey, baseUrl)
                                                                db.setAiKey("", baseUrl)
                                                            }
                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }.getOrDefault(false)
                                                }
                                                if (bound) {
                                                    hasUnboundKey = false
                                                    val boundState = withContext(Dispatchers.IO) {
                                                        db.aiKeyState(activeProfileId, baseUrl)
                                                    }
                                                    keyState = boundState
                                                    hasSavedKey = boundState == KeyState.OK
                                                    status = "旧 Key 已绑定到当前服务"
                                                } else {
                                                    status = "绑定失败：请先填写有效的服务地址"
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("确认绑定到当前服务") }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许局域网 HTTP", fontWeight = FontWeight.Medium)
                                Text("支持回环、10.x、172.16–31.x、192.168.x、链路本地与 .local；拒绝公网 HTTP 和重定向", fontSize = 10.sp, color = secondaryText())
                            }
                            Switch(checked = allowPrivateHttp, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    allowPrivateHttp = false
                                } else {
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("允许局域网明文 HTTP？")
                                        .setMessage("HTTP 流量可能被同一网络中的设备监听。页枢仅允许回环或私有局域网地址，并会拒绝公网 HTTP 与重定向；请只连接你信任的本地服务。")
                                        .setPositiveButton("我了解，启用") { _, _ -> allowPrivateHttp = true }
                                        .setNegativeButton("取消", null)
                                        .show()
                                }
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = saveDraft()
                                        status = if (result.isSuccess) "“$profileName”已保存并设为当前配置"
                                        else "保存失败：${profileErrorMessage(result.exceptionOrNull()!!)}"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                            ) { Text("保存") }
                            OutlinedButton(
                                enabled = !testing,
                                onClick = {
                                    testing = true
                                    status = "正在从接口读取模型…"
                                    scope.launch {
                                        val key = if (keyInput.isNotBlank()) keyInput else withContext(Dispatchers.IO) {
                                            db.getAiKey(activeProfileId, baseUrl)
                                        }
                                        val fetch = withContext(Dispatchers.IO) {
                                            runCatching { AiClient.discoverModels(currentAiConfig(
                                                baseUrl, key, textModel, visionModel, allowPrivateHttp,
                                                chatPath, modelsPath, authHeader, authPrefix
                                            )) }
                                        }
                                        testing = false
                                        fetch.onSuccess { models ->
                                            // 读取结果按配置持久化：换配置或重进设置页都不必重新联网
                                            val refreshed = withContext(Dispatchers.IO) {
                                                AiProfileStore.saveModels(db, activeProfileId, models)
                                                AiProfileStore.list(db)
                                            }
                                            profiles = refreshed
                                            // 以落库后的规范值作为草稿，避免与已保存配置产生「假差异」
                                            discoveredModels = refreshed.firstOrNull { it.id == activeProfileId }?.models
                                                ?: models
                                            status = if (models.isEmpty()) {
                                                "模型列表接口已关闭，请手动填写模型"
                                            } else {
                                                modelDraft = textModel
                                                modelPickerTarget = "text"
                                                "已读取并保存 ${models.size} 个模型，请选择本次调用模型"
                                            }
                                        }.onFailure {
                                            status = "读取失败：${AiClient.userFacingError(it)}；仍可手动填写模型"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp)
                            ) { Text(if (testing) "读取中" else "从接口读取模型") }
                        }
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                testing = true
                                status = "正在测试当前模型…"
                                scope.launch {
                                    val key = if (keyInput.isNotBlank()) keyInput else withContext(Dispatchers.IO) {
                                        db.getAiKey(activeProfileId, baseUrl)
                                    }
                                    val cfg = currentAiConfig(
                                        baseUrl, key, textModel, visionModel, allowPrivateHttp,
                                        chatPath, modelsPath, authHeader, authPrefix
                                    )
                                    // probe 会一并返回模型名、状态码、耗时与 token 用量，
                                    // 比只判断「请求没抛异常」有用得多。
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val probe = AiClient.probe(cfg)
                                            val echo = probe.echo.trim().take(16)
                                            probe.describe() + if (echo.isNotEmpty()) " · 回显「$echo」" else ""
                                        }.getOrElse { "连接失败：${AiClient.userFacingError(it)}" }
                                    }
                                    testing = false
                                    status = result
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("测试当前模型") }
                        status?.let {
                            val failed = it.startsWith("连接失败") || it.startsWith("读取失败") ||
                                it.startsWith("保存失败") || it.startsWith("保存模型失败") ||
                                it.startsWith("切换失败") || it.startsWith("新建失败") ||
                                it.startsWith("复制失败") || it.startsWith("删除失败")
                            GlassPill(color = if (failed) MaterialTheme.colorScheme.error else LuminousCyan) {
                                Text(it, fontSize = 11.sp, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (hasSavedKey || keyState != KeyState.NONE) {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.setAiKey(activeProfileId, "", baseUrl) }
                                    hasSavedKey = false
                                    keyState = KeyState.NONE
                                    keyLoading = false
                                    status = "当前配置的 API Key 已移除"
                                }
                            }) {
                                Text(if (hasSavedKey) "移除已保存的 Key" else "清除失效的 Key 记录")
                            }
                        }
                    }
                }
            }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(18.dp),
                    elevation = 11.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsSectionTitle("备份与迁移", "↗", ActiveViolet)
                        Text("完整备份包含原文件、封面、笔记、AI 结果与设置；永不包含 API Key。导出的 ZIP 未加密，请妥善保管。", fontSize = 11.sp, color = secondaryText())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = activity::exportBackup,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ActiveViolet)
                            ) { Text("导出完整备份") }
                            OutlinedButton(onClick = activity::importBackup, shape = RoundedCornerShape(18.dp)) { Text("导入恢复") }
                        }
                    }
                }
            }
            item {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(18.dp),
                    elevation = 9.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(46)
                        Spacer(Modifier.width(13.dp))
                        Column {
                            Text("关于页枢", fontWeight = FontWeight.Black)
                            Text("本地书架与 AI 文档助手", color = ElectricBlue, fontSize = 12.sp)
                            Text("Android 8.0+  ·  数据默认只留在设备上", fontSize = 10.sp, color = secondaryText())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLoadingCard() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassPanel(
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(24.dp),
            elevation = 10.dp
        ) {
            Text("正在读取本地设置…", color = secondaryText(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) {
    Column {
        Text(eyebrow, color = ElectricBlue, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(3.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(subtitle, color = secondaryText(), fontSize = 12.sp)
    }
}

@Composable
private fun SettingsSectionTitle(title: String, glyph: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Text(glyph, color = accent, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GlassTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = glassTextFieldColors()
    )
}

@Composable
private fun ModelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    availableCount: Int,
    onPick: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = onPick) {
                Text(if (availableCount > 0) "选择 $availableCount" else "选择")
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = glassTextFieldColors()
    )
}

private fun currentAiConfig(
    baseUrl: String,
    key: String,
    textModel: String,
    visionModel: String,
    allowPrivateHttp: Boolean,
    chatPath: String,
    modelsPath: String,
    authHeader: String,
    authPrefix: String
) = AiClient.Config(
    baseUrl = baseUrl,
    key = key,
    model = textModel,
    visionModel = visionModel,
    allowPrivateHttp = allowPrivateHttp,
    chatPath = chatPath,
    modelsPath = modelsPath,
    authHeader = authHeader,
    authPrefix = authPrefix
)

/** 配置表单校验失败直接显示原文；其余异常交给 AiClient 做脱敏映射。 */
private fun profileErrorMessage(error: Throwable): String =
    (error as? AiProfileStore.ProfileValidationException)?.message?.takeIf(String::isNotBlank)
        ?: AiClient.userFacingError(error)

@Composable
private fun glassTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
    focusedBorderColor = ElectricBlue.copy(alpha = 0.82f),
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
)

@Composable
private fun secondaryText(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)

/** 中文相对时间：系统 DateUtils 会跟随系统语言，英文设备上会显示 "3 days ago"，这里显式格式化。 */
private fun relativeTime(time: Long): String {
    if (time <= 0) return "未打开"
    val diff = System.currentTimeMillis() - time
    if (diff < 60_000L) return "刚刚"
    val minutes = diff / 60_000L
    if (minutes < 60) return "${minutes} 分钟前"
    val hours = minutes / 60
    if (hours < 24) return "${hours} 小时前"
    val days = hours / 24
    if (days < 30) return "${days} 天前"
    val months = days / 30
    if (months < 12) return "${months} 个月前"
    return "${days / 365} 年前"
}
