package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The emulator's Wi-Fi as a RESTRICTED network (capability `mobile-data`), the way a person's "metered" switch on a
 * Wi-Fi network makes it one: the network policy service's per-SSID override, entered and lifted from the shell. The
 * override reconnects the Wi-Fi as a new network, so each switch waits until the platform reports the result.
 *
 * Measured on the API 36 emulator, 2026-10-03: `NET_CAPABILITY_NOT_METERED` leaves the Wi-Fi with the override; the
 * service answers 255 either way and applies it. Lifting it reconnects the Wi-Fi too, as a new network, with cellular
 * the default in between (measured on CI, run 37210658344, and locally 2026-10-04) — so a lift's wait holds until the
 * reconnected Wi-Fi is the default again, since the cellular one reads metered.
 */
internal object MeteredWifi {
    /** The SSID of the emulator's simulated Wi-Fi. */
    private const val EMULATOR_WIFI = "AndroidWifi"
    private const val SETTLE_MILLIS = 30_000L
    private const val POLL_MILLIS = 100L

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)

    fun enter() {
        shell("cmd netpolicy set metered-network $EMULATOR_WIFI true")
        awaitUnmetered(false, "a metered Wi-Fi")
    }

    fun lift() {
        shell("cmd netpolicy set metered-network $EMULATOR_WIFI undefined")
        awaitUnmetered(true, "an unmetered Wi-Fi")
    }

    private fun awaitUnmetered(unmetered: Boolean, entering: String) {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != unmetered
        ) {
            check(System.currentTimeMillis() < deadline) { "the emulator did not reach $entering within ${SETTLE_MILLIS}ms" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }
    }
}
