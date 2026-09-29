package app.snapsync.android.storage

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files

/** The device-test APK's own context: its `filesDir`, its Keystore, its preferences. */
internal val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** A fresh directory of this test's own, under the APK's cache. */
internal fun newTempDirectory(): File = Files.createTempDirectory(context.cacheDir.toPath(), "contract").toFile()

/** Make [file] this process's to read and write again, so its directory can be removed. */
internal fun restoreOwnerAccess(file: File) {
    file.setReadable(true, true)
    file.setWritable(true, true)
    file.setExecutable(true, true)
}

/** Take every permission from [file], owner included — a present file this process may not open. */
internal fun revokeAllAccess(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
}
