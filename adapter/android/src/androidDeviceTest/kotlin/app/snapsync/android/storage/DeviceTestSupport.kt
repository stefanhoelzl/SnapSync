package app.snapsync.android.storage

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files

/** The device-test APK's own context: its `filesDir`, its Keystore, its preferences. */
internal val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Runs [command] as the device's shell — the operating system's own levers — and answers what it printed. */
internal fun deviceShell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().decodeToString() }

/** Airplane mode, which takes every network the emulator has down — and back. */
internal object Airplane {
    private val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)

    fun enter() {
        deviceShell("cmd connectivity airplane-mode enable")
        awaitNetwork(present = false)
    }

    fun leave() {
        deviceShell("cmd connectivity airplane-mode disable")
        awaitNetwork(present = true)
    }

    private fun awaitNetwork(present: Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while ((connectivity.activeNetwork != null) != present) {
            check(
                System.currentTimeMillis() < deadline,
            ) { "the network did not ${if (present) "return" else "go"} in time" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private const val SETTLE_MILLIS = 30_000L
    private const val POLL_MILLIS = 100L
}

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
