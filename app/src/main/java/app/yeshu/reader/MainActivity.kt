package app.yeshu.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.yeshu.reader.backup.BackupService
import app.yeshu.reader.data.LibraryImportWorker
import app.yeshu.reader.data.LibraryImporter
import app.yeshu.reader.preferences.UserPreferences
import app.yeshu.reader.ui.YeshuApp
import app.yeshu.reader.ui.YeshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Destination {
    data object Workbench : Destination
    data object Shelf : Destination
    data object Notes : Destination
    data object Settings : Destination
    data object Stats : Destination
    /** [anchor] 非空时进入阅读器后按该锚点定位一次（来自笔记/成果里的引用 chip）。 */
    data class Reader(val bookId: Long, val anchor: String = "") : Destination
    data class BookNotes(val bookId: Long) : Destination
    data class Chat(val bookId: Long, val chapterContext: String) : Destination
}

class MainActivity : ComponentActivity() {
    var destination by mutableStateOf<Destination>(Destination.Workbench)
        private set
    var libraryRevision by mutableIntStateOf(0)
        private set

    private var shelf: ShelfView? = null
    private var currentLegacy: View? = null
    val userPreferences by lazy { UserPreferences(applicationContext) }
    // 导入批次结果：进程可能在 WorkManager 完成前被回收，用轻量偏好持久化后下次启动补提示
    private val importPrefs by lazy { getSharedPreferences("yeshu_import", Context.MODE_PRIVATE) }

    private val pickDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val manager = WorkManager.getInstance(this)
        val batchTag = "yeshu-import-${System.currentTimeMillis()}"
        val requests = uris.map { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            OneTimeWorkRequestBuilder<LibraryImportWorker>()
                .setInputData(workDataOf(LibraryImportWorker.KEY_URI to uri.toString()))
                .addTag(batchTag)
                .build()
        }
        manager.enqueue(requests)
        // 记住批次：进程在导入完成前被回收时，下次启动仍能补上结果提示。
        rememberImportBatch(batchTag, requests.size)
        Toast.makeText(this, "${requests.size} 项已加入导入队列", Toast.LENGTH_SHORT).show()
        observeImportBatch(batchTag, requests.size, navigateOnFinish = true)
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackupService.export(this@MainActivity, uri) }
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        // 恢复是不可逆的批量写入：先读清单让用户看到会导入什么，再执行
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { BackupService.inspect(this@MainActivity, uri) }
            val message = summary?.describe()
                ?: "无法读取备份清单（文件可能已损坏）。仍要尝试恢复吗？"
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(if (summary == null) "备份清单不可读" else "确认恢复这份备份？")
                .setMessage(message)
                .setPositiveButton("开始恢复") { _, _ -> runRestore(uri) }
                .setNegativeButton("取消", null)
                .show()
                .also { Glass.styleDialog(it, resources.displayMetrics.density) }
        }
    }

    private fun runRestore(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackupService.restore(this@MainActivity, uri) }
            libraryRevision++
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Room migration and the one-time plaintext -> Keystore migration must not delay the
        // first Compose frame and keep the Android 12 splash screen visible.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Db(applicationContext).getAiKey() }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 旋转/深色切换等配置变更后恢复导航状态；仅全新启动时回到工作台
        destination = savedInstanceState?.let(::restoreDestination) ?: Destination.Workbench
        libraryRevision = savedInstanceState?.getInt(KEY_REVISION) ?: 0

        // 导入批次可能在进程被回收后才完成（WorkManager 没有前台通知），
        // 启动时补挂一次观察，把结果提示交给用户，避免导入静默结束。
        val pendingBatchTag = importPrefs.getString(KEY_IMPORT_BATCH_TAG, null)
        val pendingBatchSize = importPrefs.getInt(KEY_IMPORT_BATCH_SIZE, 0)
        if (pendingBatchTag != null && pendingBatchSize > 0) {
            observeImportBatch(pendingBatchTag, pendingBatchSize, navigateOnFinish = false)
        }

        // 导入中途进程被杀会留下 .import_*.tmp 全尺寸副本，启动时清掉（带时限，不影响正在进行的导入）。
        lifecycleScope.launch(Dispatchers.IO) { runCatching { LibraryImporter.sweepStaleTemporaryFiles(applicationContext) } }

        onBackPressedDispatcher.addCallback(this) {
            destination = when (val current = destination) {
                is Destination.Reader -> Destination.Shelf
                is Destination.BookNotes -> Destination.Reader(current.bookId)
                is Destination.Chat -> Destination.Reader(current.bookId)
                Destination.Stats -> Destination.Workbench
                Destination.Workbench -> {
                    finish()
                    return@addCallback
                }
                else -> Destination.Workbench
            }
        }

        setContent {
            val themeMode by userPreferences.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            YeshuTheme(themeMode) {
                YeshuApp(
                    activity = this,
                    destination = destination,
                    libraryRevision = libraryRevision,
                    onNavigate = ::navigate
                )
            }
        }
    }

    fun navigate(target: Destination) {
        destination = target
    }

    fun showShelf() = navigate(Destination.Shelf)

    fun openReader(id: Long, anchor: String = "") {
        Db(this).markOpened(id)
        libraryRevision++
        navigate(Destination.Reader(id, anchor))
    }

    fun showSettings() = navigate(Destination.Settings)
    fun showNotes(bookId: Long) = navigate(Destination.BookNotes(bookId))
    fun showChat(bookId: Long, chapterContext: String) = navigate(Destination.Chat(bookId, chapterContext))
    fun showStats() = navigate(Destination.Stats)
    fun backToShelf() = showShelf()

    fun registerLegacy(view: View?) {
        currentLegacy = view
        shelf = view as? ShelfView
    }

    fun importDocuments() = pickDocuments.launch(LibraryImporter.mimeTypes)

    /** 记录当前导入批次，供进程被杀后的下一次启动补齐结果提示。 */
    private fun rememberImportBatch(tag: String, size: Int) {
        importPrefs.edit()
            .putString(KEY_IMPORT_BATCH_TAG, tag)
            .putInt(KEY_IMPORT_BATCH_SIZE, size)
            .apply()
    }

    private fun forgetImportBatch() {
        importPrefs.edit()
            .remove(KEY_IMPORT_BATCH_TAG)
            .remove(KEY_IMPORT_BATCH_SIZE)
            .apply()
    }

    /**
     * 观察一个导入批次直到全部结束，然后汇总并提示结果。
     * navigateOnFinish 仅用于应用内发起的导入（完成后跳到书架）；
     * 启动时补挂的观察不改变当前页面。
     */
    private fun observeImportBatch(tag: String, expected: Int, navigateOnFinish: Boolean) {
        val live = WorkManager.getInstance(this).getWorkInfosByTagLiveData(tag)
        val observer = object : Observer<List<WorkInfo>> {
            override fun onChanged(infos: List<WorkInfo>) {
                if (infos.size < expected || infos.any { !it.state.isFinished }) return
                live.removeObserver(this)
                forgetImportBatch()
                libraryRevision++
                if (navigateOnFinish) destination = Destination.Shelf
                Toast.makeText(this@MainActivity, summarizeImportBatch(infos, expected), Toast.LENGTH_LONG).show()
            }
        }
        live.observe(this, observer)
    }

    private fun summarizeImportBatch(infos: List<WorkInfo>, expected: Int): String {
        val success = infos.count {
            it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(LibraryImportWorker.KEY_ERROR).isNullOrBlank()
        }
        val duplicate = infos.count {
            it.state == WorkInfo.State.SUCCEEDED && !it.outputData.getString(LibraryImportWorker.KEY_ERROR).isNullOrBlank()
        }
        val failed = (expected - success - duplicate).coerceAtLeast(0)
        return buildString {
            append("已导入 $success 项")
            if (duplicate > 0) append("，已有 $duplicate 项")
            if (failed > 0) append("，失败 $failed 项")
        }
    }

    fun exportBackup() = createBackup.launch("yeshu_backup_${System.currentTimeMillis()}.yeshu.zip")
    fun importBackup() = openBackup.launch(arrayOf("application/zip", "application/json", "*/*"))

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        (currentLegacy as? ReaderView)?.let { if (it.handleVolumeKey(event)) return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // ReaderView 会同时消费 DOWN 与配对的 UP，这里只需转发，不必自己记按键。
        (currentLegacy as? ReaderView)?.let { if (it.handleVolumeKey(event)) return true }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // 阅读时长只在视图 attached 期间累计，回到前台要显式恢复，
        // 否则按 Home 后时间会一直往 read_log 里涨。
        (currentLegacy as? ReaderView)?.resumeReadSession()
    }

    override fun onStop() {
        (currentLegacy as? ReaderView)?.pauseReadSession()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_REVISION, libraryRevision)
        outState.putString(KEY_DESTINATION, destinationKey(destination))
        when (val current = destination) {
            is Destination.Reader -> outState.putLong(KEY_DESTINATION_BOOK, current.bookId)
            is Destination.BookNotes -> outState.putLong(KEY_DESTINATION_BOOK, current.bookId)
            is Destination.Chat -> {
                outState.putLong(KEY_DESTINATION_BOOK, current.bookId)
                outState.putString(KEY_DESTINATION_CONTEXT, current.chapterContext)
            }
            else -> Unit
        }
    }

    private fun destinationKey(destination: Destination): String = when (destination) {
        Destination.Workbench -> "workbench"
        Destination.Shelf -> "shelf"
        Destination.Notes -> "notes"
        Destination.Settings -> "settings"
        Destination.Stats -> "stats"
        is Destination.Reader -> "reader"
        is Destination.BookNotes -> "booknotes"
        is Destination.Chat -> "chat"
    }

    private fun restoreDestination(state: Bundle): Destination {
        val bookId = state.getLong(KEY_DESTINATION_BOOK, -1L)
        return when (state.getString(KEY_DESTINATION)) {
            "shelf" -> Destination.Shelf
            "notes" -> Destination.Notes
            "settings" -> Destination.Settings
            "stats" -> Destination.Stats
            "reader" -> if (bookId > 0) Destination.Reader(bookId) else Destination.Shelf
            "booknotes" -> if (bookId > 0) Destination.BookNotes(bookId) else Destination.Shelf
            "chat" -> if (bookId > 0) {
                Destination.Chat(bookId, state.getString(KEY_DESTINATION_CONTEXT).orEmpty())
            } else {
                Destination.Shelf
            }
            else -> Destination.Workbench
        }
    }

    @Deprecated("Legacy Views still use startActivityForResult during the migration window")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        shelf?.handleResult(requestCode, data)
    }

    private companion object {
        const val KEY_REVISION = "yeshu_library_revision"
        const val KEY_DESTINATION = "yeshu_destination"
        const val KEY_DESTINATION_BOOK = "yeshu_destination_book"
        const val KEY_DESTINATION_CONTEXT = "yeshu_destination_context"
        const val KEY_IMPORT_BATCH_TAG = "yeshu_import_batch_tag"
        const val KEY_IMPORT_BATCH_SIZE = "yeshu_import_batch_size"
    }
}
