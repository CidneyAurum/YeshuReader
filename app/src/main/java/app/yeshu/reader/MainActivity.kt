package app.yeshu.reader

import android.app.Activity
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
    data class Reader(val bookId: Long) : Destination
    data class BookNotes(val bookId: Long) : Destination
    data class Chat(val bookId: Long, val chapterContext: String) : Destination
}

class MainActivity : ComponentActivity() {
    var destination by mutableStateOf<Destination>(Destination.Workbench)
        private set
    var libraryRevision by mutableIntStateOf(0)
        private set

    private var shelf: ShelfView? = null
    private var legacySettings: SettingsView? = null
    private var currentLegacy: View? = null
    val userPreferences by lazy { UserPreferences(applicationContext) }

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
        Toast.makeText(this, "${requests.size} 项已加入导入队列", Toast.LENGTH_SHORT).show()
        val live = manager.getWorkInfosByTagLiveData(batchTag)
        val observer = object : Observer<List<WorkInfo>> {
            override fun onChanged(infos: List<WorkInfo>) {
                if (infos.size != requests.size || infos.any { !it.state.isFinished }) return
                live.removeObserver(this)
                libraryRevision++
                destination = Destination.Shelf
                val success = infos.count { it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(LibraryImportWorker.KEY_ERROR).isNullOrBlank() }
                val duplicate = infos.count { it.state == WorkInfo.State.SUCCEEDED && !it.outputData.getString(LibraryImportWorker.KEY_ERROR).isNullOrBlank() }
                val failed = infos.size - success - duplicate
                Toast.makeText(
                    this@MainActivity,
                    buildString {
                        append("已导入 $success 项")
                        if (duplicate > 0) append("，已有 $duplicate 项")
                        if (failed > 0) append("，失败 $failed 项")
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        live.observe(this, observer)
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
        val requestedBook = intent.getStringExtra("bookId")?.toLongOrNull() ?: -1L
        destination = if (requestedBook > 0) Destination.Reader(requestedBook) else Destination.Workbench

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

    fun openReader(id: Long) {
        Db(this).markOpened(id)
        libraryRevision++
        navigate(Destination.Reader(id))
    }

    fun showSettings() = navigate(Destination.Settings)
    fun showNotes(bookId: Long) = navigate(Destination.BookNotes(bookId))
    fun showChat(bookId: Long, chapterContext: String) = navigate(Destination.Chat(bookId, chapterContext))
    fun showStats() = navigate(Destination.Stats)
    fun backToShelf() = showShelf()

    fun registerLegacy(view: View?) {
        currentLegacy = view
        shelf = view as? ShelfView
        legacySettings = view as? SettingsView
    }

    fun importDocuments() = pickDocuments.launch(LibraryImporter.mimeTypes)

    fun exportBackup() = createBackup.launch("yeshu_backup_${System.currentTimeMillis()}.yeshu.zip")
    fun importBackup() = openBackup.launch(arrayOf("application/zip", "application/json", "*/*"))

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        (currentLegacy as? ReaderView)?.let { if (it.handleVolumeKey(event)) return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        (currentLegacy as? ReaderView)?.let { if (it.handleVolumeKey(event)) return true }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Legacy Views still use startActivityForResult during the migration window")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Glass.REQ_SETTINGS) {
            shelf?.refresh()
            return
        }
        if (requestCode == SettingsView.REQ_RESTORE) {
            if (resultCode == Activity.RESULT_OK) legacySettings?.handleRestoreResult(data?.data ?: return)
            return
        }
        if (requestCode == SettingsView.REQ_EXPORT) {
            if (resultCode == Activity.RESULT_OK) legacySettings?.handleExportResult(data?.data ?: return)
            return
        }
        shelf?.handleResult(requestCode, data)
    }
}
