package app.snapsync.android.download

import android.app.DownloadManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.storage.context

internal val downloadManager: DownloadManager get() = context.getSystemService(DownloadManager::class.java)

/** Remove every row this app holds, so no test is handed another's leftovers. */
internal fun removeAllDownloads() {
    downloadManager.query(DownloadManager.Query())?.use { cursor ->
        val ids = buildList {
            while (cursor.moveToNext()) add(
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
            )
        }
        ids.forEach { downloadManager.remove(it) }
    }
}

/**
 * Wait until the system has delivered every queued broadcast. A managed emulator starts the tests at
 * `sys.boot_completed`, while `BOOT_COMPLETED` is still on its way to the apps — and DownloadManager's own handler
 * reschedules every row it holds, which races a running download against a second thread for it
 * ([AndroidDownloadRescheduleTest] states that race; the other tests must not meet it by accident).
 */
internal fun awaitBroadcastsIdle() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand("am wait-for-broadcast-idle"),
    ).use { it.readBytes() }
}
