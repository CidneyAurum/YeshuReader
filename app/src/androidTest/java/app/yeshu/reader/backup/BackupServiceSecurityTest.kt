package app.yeshu.reader.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupServiceSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun restore_rejectsParentTraversalEntry() {
        val backup = zip("unsafe") {
            entry("../outside.txt", "escape")
        }

        val result = BackupService.restore(context, Uri.fromFile(backup))

        assertTrue(result, result.contains("不安全路径"))
        backup.delete()
    }

    @Test
    fun restore_rejectsDuplicateZipEntries() {
        val backup = zip("duplicate") {
            entry("ignored.txt", "first")
            entry("./ignored.txt", "second")
        }

        val result = BackupService.restore(context, Uri.fromFile(backup))

        assertTrue(result, result.contains("重复条目"))
        backup.delete()
    }

    private fun zip(prefix: String, body: ZipFixture.() -> Unit): File {
        val file = File.createTempFile("yeshu-$prefix-", ".zip", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { output -> ZipFixture(output).body() }
        return file
    }

    private class ZipFixture(private val output: ZipOutputStream) {
        fun entry(name: String, value: String) {
            output.putNextEntry(ZipEntry(name))
            output.write(value.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
}
