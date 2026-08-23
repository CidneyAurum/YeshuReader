package app.yeshu.reader.ui

import android.text.format.DateUtils
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yeshu.reader.AiClient
import app.yeshu.reader.ChatView
import app.yeshu.reader.Db
import app.yeshu.reader.Destination
import app.yeshu.reader.LibraryItem
import app.yeshu.reader.MainActivity
import app.yeshu.reader.NotesView
import app.yeshu.reader.R
import app.yeshu.reader.ReaderView
import app.yeshu.reader.SettingsView
import app.yeshu.reader.ShelfView
import app.yeshu.reader.StatsView
import app.yeshu.reader.ai.AiProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TopDestination(val label: String, val glyph: String, val destination: Destination)

private val topDestinations = listOf(
    TopDestination("工作台", "⌂", Destination.Workbench),
    TopDestination("书架", "▦", Destination.Shelf),
    TopDestination("笔记", "✎", Destination.Notes),
    TopDestination("设置", "⚙", Destination.Settings)
)

@Composable
fun YeshuApp(
    activity: MainActivity,
    destination: Destination,
    libraryRevision: Int,
    onNavigate: (Destination) -> Unit
) {
    val topLevel = destination in topDestinations.map { it.destination }
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
                    Box(Modifier.weight(1f)) {
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
        Destination.Shelf -> LegacyHost(activity) { ShelfView(activity) }
        Destination.Notes -> NotesHubScreen(activity, revision, onNavigate)
        Destination.Settings -> SettingsScreen(activity)
        Destination.Stats -> LegacyHost(activity) { StatsView(activity) }
        is Destination.Reader -> DocumentWorkbenchScreen(activity, destination.bookId, onNavigate)
        is Destination.BookNotes -> LegacyHost(activity) { NotesView(activity, destination.bookId) }
        is Destination.Chat -> LegacyHost(activity) { ChatView(activity, destination.bookId, destination.chapterContext) }
    }
}

@Composable
private fun DocumentWorkbenchScreen(
    activity: MainActivity,
    bookId: Long,
    onNavigate: (Destination) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 840.dp) {
            LegacyHost(activity) { ReaderView(activity, bookId, showDocumentTabs = true) }
        } else {
            val reader = remember(bookId) { ReaderView(activity, bookId, showDocumentTabs = false) }
            val book = remember(bookId) { Db(activity).getBook(bookId) }
            Row(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    factory = { reader.also(activity::registerLegacy) },
                    update = { activity.registerLegacy(it) }
                )
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
                            DocumentTool("目录", "章节或 PDF 页码", LuminousCyan) { reader.openTableOfContents() }
                            DocumentTool("AI", "理解包、问答与自测", ActiveViolet) { reader.openAiWorkbench() }
                            DocumentTool("笔记", "批注与 AI 结果", Color(0xFFFF8A65)) { onNavigate(Destination.BookNotes(bookId)) }
                            Spacer(Modifier.weight(1f))
                            Text("AI 只在你主动触发时发送所选范围。", color = secondaryText(), fontSize = 10.sp)
                        }
                    }
                }
            }
            DisposableEffect(reader) {
                onDispose { activity.registerLegacy(null) }
            }
        }
    }
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

@Composable
private fun LegacyHost(activity: MainActivity, factory: () -> View) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { factory().also(activity::registerLegacy) },
        update = { activity.registerLegacy(it) }
    )
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
    val books = remember(revision) { db.listBooks() }
    val reading = books.firstOrNull { it.progress in 0.006f..0.989f } ?: books.firstOrNull()
    val recent = books.take(8)
    val minutes = db.totalAllReadMs() / 60_000

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 1180.dp) 1180.dp else maxWidth
        val horizontal = if (maxWidth >= 700.dp) 28.dp else 16.dp
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter).statusBarsPadding(),
            contentPadding = PaddingValues(horizontal, 18.dp, horizontal, 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { WorkbenchHeader() }
            item { HeroCard(reading, activity, showIllustrations) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("${books.size}", "藏书与资料", ElectricBlue, Modifier.weight(1f))
                    MetricCard("${db.noteCount()}", "笔记", ActiveViolet, Modifier.weight(1f))
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
            }
            if (showIllustrations) item { CharacterRibbon() }
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
                        Image(
                            painter = painterResource(R.drawable.yeshu_art_festival),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
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
private fun CharacterRibbon() {
    val portraits = listOf(
        Triple(R.drawable.yeshu_art_yellow, Color(0xFFFFC857), -0.40f),
        Triple(R.drawable.yeshu_art_blue, Color(0xFF5E81D1), -0.40f),
        Triple(R.drawable.yeshu_art_pink, Color(0xFFFF8FB8), -0.42f),
        Triple(R.drawable.yeshu_art_red, Color(0xFFE95D64), -0.48f)
    )
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(15.dp),
        elevation = 12.dp
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 620.dp
            if (roomy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(220.dp)) {
                        GlassPill(color = ActiveViolet) { Text("书架心情", color = ActiveViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(9.dp))
                        Text("给阅读留一点颜色", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("人物只作点缀，内容永远是主角。", fontSize = 11.sp, color = secondaryText())
                    }
                    Spacer(Modifier.width(18.dp))
                    PortraitRow(portraits, Modifier.weight(1f))
                }
            } else {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("给阅读留一点颜色", fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text("人物装饰与你的内容分开呈现", fontSize = 10.sp, color = secondaryText())
                        }
                        GlassPill(color = ActiveViolet) { Text("书架心情", color = ActiveViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(12.dp))
                    PortraitRow(portraits, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PortraitRow(portraits: List<Triple<Int, Color, Float>>, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        portraits.forEach { (resource, accent, verticalBias) ->
            GlassPanel(
                modifier = Modifier.width(82.dp).height(96.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = 5.dp
            ) {
                Image(
                    painter = painterResource(resource),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, verticalBias)
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.20f)))))
            }
        }
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

@Composable
private fun NotesHubScreen(activity: MainActivity, revision: Int, onNavigate: (Destination) -> Unit) {
    val db = remember { Db(activity) }
    val notes = remember(revision) { db.recentNotes(80) }
    val books = remember(revision) { db.listBooks().associateBy { it.id } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 900.dp) 900.dp else maxWidth
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter).statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 20.dp, 18.dp, 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenHeader("NOTES", "笔记中枢", "阅读摘记、AI 摘要和问答都汇在这里") }
            if (notes.isEmpty()) {
                item {
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
                            Text("还没有笔记", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("打开一本书或资料开始标记", color = secondaryText(), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(notes, key = { it.id }) { note ->
                    val book = books[note.bookId]
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(17.dp),
                        elevation = 8.dp,
                        onClick = { if (book != null) onNavigate(Destination.BookNotes(book.id)) }
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    book?.title ?: "已移除的内容",
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricBlue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                GlassPill(color = ActiveViolet) {
                                    Text(note.kind.uppercase(), fontSize = 9.sp, color = ActiveViolet, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Text(
                                note.content.removePrefix("U:").removePrefix("A:"),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: MainActivity) {
    val db = remember { Db(activity) }
    var baseUrl by remember { mutableStateOf(db.getSetting("ai_base_url").orEmpty()) }
    var textModel by remember { mutableStateOf(db.getSetting("ai_model").orEmpty()) }
    var visionModel by remember { mutableStateOf(db.getSetting("ai_vision_model").orEmpty()) }
    var keyInput by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(db.getAiKey(baseUrl).isNotBlank()) }
    var allowPrivateHttp by remember { mutableStateOf(db.getSetting("ai_allow_private_http") == "1") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val themeMode by activity.userPreferences.themeMode.collectAsStateWithLifecycle(initialValue = "system")
    val showIllustrations by activity.userPreferences.showIllustrations.collectAsStateWithLifecycle(initialValue = true)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = if (maxWidth > 900.dp) 900.dp else maxWidth
        LazyColumn(
            modifier = Modifier.width(pageWidth).fillMaxHeight().align(Alignment.TopCenter).statusBarsPadding(),
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
                    elevation = 14.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSectionTitle("AI 服务", "◇", ElectricBlue)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AiProviders.presets.forEach { preset ->
                                AssistChip(
                                    onClick = {
                                        baseUrl = preset.baseUrl
                                        textModel = preset.textModelHint
                                        visionModel = preset.visionModelHint
                                        allowPrivateHttp = false
                                        hasSavedKey = db.getAiKey(preset.baseUrl).isNotBlank()
                                        if (preset.id == "ollama") status = "本地 Ollama 使用 HTTP 时，请手动开启并确认局域网 HTTP"
                                    },
                                    label = { Text(preset.name) },
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                        GlassTextField(baseUrl, {
                            baseUrl = it
                            hasSavedKey = db.getAiKey(it).isNotBlank()
                        }, "Base URL")
                        GlassTextField(textModel, { textModel = it }, "文本模型")
                        GlassTextField(visionModel, { visionModel = it }, "视觉模型（可留空）")
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (hasSavedKey) "API Key（已安全保存，留空不修改）" else "API Key") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            colors = glassTextFieldColors()
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许局域网 HTTP", fontWeight = FontWeight.Medium)
                                Text("仅本机、严格私网 IP 与 .local 地址；重定向会被拒绝", fontSize = 10.sp, color = secondaryText())
                            }
                            Switch(checked = allowPrivateHttp, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    allowPrivateHttp = false
                                } else {
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("允许局域网明文 HTTP？")
                                        .setMessage("HTTP 流量可能被同一网络中的设备监听。页枢只允许回环或私网地址，并会拒绝重定向；请仅连接你信任的本地服务。")
                                        .setPositiveButton("我了解，启用") { _, _ -> allowPrivateHttp = true }
                                        .setNegativeButton("取消", null)
                                        .show()
                                }
                            })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    db.setSetting("ai_base_url", baseUrl.trim())
                                    db.setSetting("ai_model", textModel.trim())
                                    db.setSetting("ai_vision_model", visionModel.trim())
                                    db.setSetting("ai_allow_private_http", if (allowPrivateHttp) "1" else "0")
                                    var saveError: String? = null
                                    if (keyInput.isNotBlank()) {
                                        val saved = runCatching { db.setAiKey(keyInput, baseUrl) }
                                        if (saved.isSuccess) keyInput = ""
                                        else saveError = "保存失败：请先填写有效的服务地址"
                                    }
                                    hasSavedKey = db.getAiKey(baseUrl).isNotBlank()
                                    status = saveError ?: "设置已保存"
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                            ) { Text("保存") }
                            OutlinedButton(
                                enabled = !testing,
                                onClick = {
                                    testing = true
                                    status = "正在探测模型…"
                                    scope.launch {
                                        val key = if (keyInput.isNotBlank()) keyInput else db.getAiKey(baseUrl)
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val cfg = AiClient.Config(baseUrl, key, textModel, visionModel, allowPrivateHttp)
                                                val models = AiClient.listModels(cfg)
                                                if (models.isEmpty()) "连接成功，服务未返回模型列表" else "连接成功，可用模型 ${models.size} 个"
                                            }.getOrElse { "连接失败：${it.message}" }
                                        }
                                        testing = false
                                        status = result
                                    }
                                },
                                shape = RoundedCornerShape(18.dp)
                            ) { Text(if (testing) "测试中" else "测试连接") }
                        }
                        status?.let {
                            val failed = it.startsWith("连接失败")
                            GlassPill(color = if (failed) MaterialTheme.colorScheme.error else LuminousCyan) {
                                Text(it, fontSize = 11.sp, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (hasSavedKey) {
                            TextButton(onClick = {
                                db.setAiKey("", baseUrl)
                                hasSavedKey = false
                                status = "API Key 已移除"
                            }) { Text("移除已保存的 Key") }
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
private fun glassTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
    focusedBorderColor = ElectricBlue.copy(alpha = 0.82f),
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
)

@Composable
private fun secondaryText(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)

private fun relativeTime(time: Long): String =
    if (time <= 0) "未打开" else DateUtils.getRelativeTimeSpanString(time).toString()
