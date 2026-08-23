package app.yeshu.reader.data

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Durable SAF import. The persisted URI grant lets WorkManager finish after process restart. */
class LibraryImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawUri = inputData.getString(KEY_URI)
            ?: return@withContext Result.failure(errorData("缺少文件地址"))
        val imported = LibraryImporter.import(applicationContext, Uri.parse(rawUri))
        val output = workDataOf(
            KEY_ITEM_ID to imported.id,
            KEY_TITLE to imported.title,
            KEY_FORMAT to imported.format,
            KEY_ERROR to imported.error.orEmpty()
        )
        if (imported.id > 0) Result.success(output) else Result.failure(output)
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_URI = "source_uri"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_TITLE = "title"
        const val KEY_FORMAT = "format"
        const val KEY_ERROR = "error"
    }
}
