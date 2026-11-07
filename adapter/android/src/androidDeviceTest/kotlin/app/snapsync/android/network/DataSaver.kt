package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Android's Data Saver for this test APK, switched from the shell the way the member's
 * switch in Settings sets it: the network policy service's global "restrict background", and its per-app
 * "unrestricted data" exemption. Each switch waits until the platform answers it for this app
 * (`restrictBackgroundStatus`), so a test never races its own entry; [lift] undoes both, whatever a test did.
 */
internal object DataSaver {
    private const val SETTLE_MILLIS = 10_000L
    private const val POLL_MILLIS = 50L

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    private val uid get() = context.applicationInfo.uid

    /** Data Saver on, holding this app. */
    fun enter() {
        shell("cmd netpolicy set restrict-background true")
        await(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, "Data Saver on")
    }

    /** Data Saver on, with this app allowed unrestricted data in it. */
    fun enterExempted() {
        shell("cmd netpolicy set restrict-background true")
        shell("cmd netpolicy add restrict-background-whitelist $uid")
        await(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED, "Data Saver on, this app exempted")
    }

    /** Data Saver off, and no exemption left behind. */
    fun lift() {
        shell("cmd netpolicy remove restrict-background-whitelist $uid")
        shell("cmd netpolicy set restrict-background false")
        await(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED, "Data Saver off")
    }

    private fun await(status: Int, entering: String) {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (connectivity.restrictBackgroundStatus != status) {
            check(System.currentTimeMillis() < deadline) {
                "the emulator did not reach $entering within ${SETTLE_MILLIS}ms " +
                    "(status ${connectivity.restrictBackgroundStatus})"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().decodeToString() }
}
