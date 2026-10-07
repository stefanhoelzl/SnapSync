package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The networks the emulator itself brought up — the precondition a device test that needs one states, so a boot that
 * brought none fails naming the boot, not the clause that happened to need it first.
 *
 * Measured in CI (2026-10-02 … 10-05, emulator 37.2.12): about one managed boot in ten came up with a Wi-Fi access point
 * that never answered an authentication, for the whole run, and with the cellular network only ~40 s after the first
 * test. Without these checks that read as every metered-Wi-Fi clause's settle timeout and, when the Keystore test came
 * first, a key pair the Keystore "failed to generate" (it provisions attestation keys over the network).
 */
internal object EmulatorNetwork {
    private const val WIFI_MILLIS = 60_000L
    private const val INTERNET_MILLIS = 90_000L
    private const val POLL_MILLIS = 250L

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)

    /** Set once the emulator was found without a Wi-Fi network: a dead Wi-Fi stays dead for the run, so every later need fails at once. */
    @Volatile
    private var missingWifi: String? = null

    /** Waits until the emulator has a Wi-Fi network, connected or not yet validated; fails naming every network it has. */
    fun requireWifi() {
        missingWifi?.let { error(it) }
        if (!awaitAny(WIFI_MILLIS) { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }) {
            val reason = "the emulator has no Wi-Fi network at all after ${WIFI_MILLIS}ms — a boot whose Wi-Fi never " +
                "came up, not this test (networks: ${describe()})"
            missingWifi = reason
            error(reason)
        }
    }

    /** Waits until the emulator has a validated network with internet over any transport — what [need] needs. */
    fun requireInternet(need: String) {
        val online = awaitAny(INTERNET_MILLIS) {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        check(
            online,
        ) { "the emulator has no validated network after ${INTERNET_MILLIS}ms, and $need (networks: ${describe()})" }
    }

    /** Every network the emulator has, with the transport and capabilities the device tests turn on. */
    @Suppress("DEPRECATION") // allNetworks: a diagnostic snapshot of every network, which no callback gives in one read
    fun describe(): String = connectivity.allNetworks.joinToString(prefix = "[", postfix = "]") { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        val traits = listOfNotNull(
            "WIFI".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
            "CELLULAR".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true },
            "VALIDATED".takeIf { caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true },
            "NOT_METERED".takeIf { caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true },
        )
        "$network$traits"
    }

    @Suppress("DEPRECATION") // allNetworks: as in describe
    private fun awaitAny(millis: Long, matches: (NetworkCapabilities) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (connectivity.allNetworks.none { network -> connectivity.getNetworkCapabilities(network)?.let(matches) == true }) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }
}
