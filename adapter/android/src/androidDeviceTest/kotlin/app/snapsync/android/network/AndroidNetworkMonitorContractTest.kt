package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.NetworkMonitorContract
import app.snapsync.contracts.NetworkState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.control.Verifies
import app.snapsync.ports.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NetworkMonitorContract] against the real [AndroidNetworkMonitor] on the emulator, each state entered through the
 * platform's own shell commands — the ones a person's switches stand behind:
 *  - OFFLINE: airplane mode, which takes the emulator's Wi-Fi and cellular down together;
 *  - RESTRICTED: the emulator's Wi-Fi marked metered by the network policy service — what a person's "metered" switch
 *    on a Wi-Fi network sets, and what the emulated cellular network always is (capability `mobile-data`);
 *  - BLOCKED: the connectivity service's `OEM_DENY_3` firewall chain with this package denied — a block of THIS app's
 *    network while the device stays online, as a vendor's per-app network switch sets it.
 *
 * Every entry waits until the platform has applied it — every network gone, the default withheld, or back — so a
 * clause never races the switch it was entered by; every disposal restores the online device, whatever the clause
 * did, and so does an entry that fails part-way.
 */
class AndroidNetworkMonitorContractTest {

    private val binding = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidNetworkMonitor::class
        override val reaches = setOf(
            NetworkState.ONLINE,
            NetworkState.RESTRICTED,
            NetworkState.OFFLINE,
            NetworkState.BLOCKED,
        )

        override fun create(state: NetworkState, clauseId: String, log: CallLog): Entered<NetworkMonitor> {
            enter(state)
            return Entered.Ready(AndroidNetworkMonitor(context).recorded(log), dispose = ::restoreOnline)
        }
    }

    /** RESTRICTED entered the second way Android has: Data Saver on, on the emulator's unmetered Wi-Fi. */
    private val dataSaverBinding = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidNetworkMonitor::class
        override val reaches = setOf(NetworkState.RESTRICTED)

        override fun create(state: NetworkState, clauseId: String, log: CallLog): Entered<NetworkMonitor> {
            if (state !in reaches) return Entered.Unreachable("this binding enters RESTRICTED through Data Saver only")
            enterDataSaverOnWifi()
            return Entered.Ready(AndroidNetworkMonitor(context).recorded(log), dispose = ::liftDataSaver)
        }
    }

    @Test
    fun `the default-network callback satisfies the NetworkMonitor contract`() = verify(NetworkMonitorContract, binding)

    @Test
    fun `the default-network callback satisfies the NetworkMonitor contract under Data Saver`() =
        verify(NetworkMonitorContract, dataSaverBinding)

    // Android's part of the scenario: Data Saver, which on an unmetered Wi-Fi changes no capability, reads restricted.
    // iPhone's Low Data Mode is `IosNetworkMonitorTest`'s.
    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "Low Data Mode counts as mobile data",
    )
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "Data Saver holds photos on an ordinary Wi-Fi",
    )
    fun `Data Saver on an unmetered Wi-Fi is restricted and switching it off is told at once`(): Unit = runBlocking {
        try {
            enterDataSaverOnWifi()
            val readings = Channel<NetworkAccess>(Channel.UNLIMITED)
            val monitor = AndroidNetworkMonitor(context)
            val collection = launch(Dispatchers.Default) { monitor.watch().collect(readings::send) }
            try {
                assertEquals(NetworkAccess.Online(restricted = true), next(readings), "Data Saver holds the Wi-Fi")
                DataSaver.lift()
                // Heard on a running collection, with no activity in this process: the registered receiver.
                assertEquals(NetworkAccess.Online(restricted = false), next(readings), "Data Saver off")
            } finally {
                collection.cancel()
            }
        } finally {
            liftDataSaver()
        }
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "SnapSync allowed unrestricted data",
    )
    fun `Data Saver holds nothing once SnapSync is allowed unrestricted data`(): Unit = runBlocking {
        try {
            restoreOnline()
            awaitWifiDefault()
            DataSaver.enterExempted()
            assertEquals(NetworkAccess.Online(restricted = false), firstReading())
        } finally {
            liftDataSaver()
        }
    }

    /**
     * A carrier network the carrier currently does not charge for is still mobile data: Android schedules every held
     * transfer by `NOT_METERED` alone ([AndroidNetworkMonitor]'s KDoc), so the monitor does too. No emulator network can
     * be made temporarily unmetered, and an app cannot build capabilities, so the mapping is read over a lookup.
     */
    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "Mobile data the carrier does not charge for",
    )
    fun `a temporarily unmetered carrier network is metered`() {
        val temporarilyFree = setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
        )
        assertTrue(meteredBy(temporarilyFree::contains), "temporarily unmetered is metered")
        val unmetered = setOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        assertFalse(meteredBy(unmetered::contains), "an unmetered network is not")
    }

    private fun enterDataSaverOnWifi() {
        restoreOnline()
        awaitWifiDefault()
        DataSaver.enter()
    }

    private fun liftDataSaver() {
        DataSaver.lift()
        restoreOnline()
    }

    private suspend fun firstReading(): NetworkAccess =
        withTimeout(READING_MILLIS) { AndroidNetworkMonitor(context).watch().first() }

    private suspend fun next(readings: Channel<NetworkAccess>): NetworkAccess =
        withTimeout(READING_MILLIS) { readings.receive() }

    /**
     * Waits until the emulator's Wi-Fi is the default network. Lifting a metered override reconnects the Wi-Fi as a new
     * network, with cellular the default for a few seconds in between (measured 2026-11-07).
     */
    private fun awaitWifiDefault() {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true
        ) {
            check(System.currentTimeMillis() < deadline) {
                "the emulator's Wi-Fi was not the default network within ${SETTLE_MILLIS}ms (${EmulatorNetwork.describe()})"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * The platform facts [AndroidNetworkMonitor] reads absence by, pinned outright. The contract catches an adapter that
     * reads `activeNetwork` instead only when the callback happens to arrive after the read; this fails every time the
     * platform stops telling a block from an absence this way.
     */
    @Test
    @Suppress("DEPRECATION")
    fun `a blocked network hides from activeNetwork but its info reads BLOCKED while an absent one has no info`() {
        try {
            enter(NetworkState.BLOCKED)
            assertNull(connectivity.activeNetwork, "a blocked app's activeNetwork")
            assertEquals(NetworkInfo.DetailedState.BLOCKED, connectivity.activeNetworkInfo?.detailedState)
            restoreOnline()
            enter(NetworkState.OFFLINE)
            assertNull(connectivity.activeNetworkInfo, "the active network's info in airplane mode")
        } finally {
            restoreOnline()
        }
    }

    /**
     * A phone's personal hotspot tells the phones joining it that it is metered, and Android marks that Wi-Fi metered,
     * as it marks a Wi-Fi a person switched to metered. This adapter's part is the mapping: a metered Wi-Fi is mobile
     * data. That a hotspot reads metered is the platform's.
     */
    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi",
        scenario = "A hotspot counts as mobile data",
    )
    fun `a metered Wi-Fi — what a phone’s hotspot reads as — is restricted`(): Unit = runBlocking {
        try {
            enter(NetworkState.RESTRICTED)
            awaitWifiDefault()
            assertEquals(NetworkAccess.Online(restricted = true), firstReading())
        } finally {
            restoreOnline()
        }
    }

    /** The adapter's part: an unmetered Wi-Fi reads unrestricted. That the screen then shows no notice is the core's. */
    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "Mobile data off but on Wi-Fi",
    )
    fun `an unmetered Wi-Fi is online and unrestricted`(): Unit = runBlocking {
        try {
            enter(NetworkState.ONLINE)
            awaitWifiDefault()
            assertEquals(NetworkAccess.Online(restricted = false), firstReading())
        } finally {
            restoreOnline()
        }
    }

    /**
     * Puts the emulator in [state]. An entry that fails part-way restores the online device before it throws: a device
     * left in airplane mode would fail every later device test of the run that needs the network.
     */
    private fun enter(state: NetworkState) {
        var entered = false
        try {
            when (state) {
                NetworkState.ONLINE -> restoreOnline()
                NetworkState.RESTRICTED -> {
                    restoreOnline()
                    MeteredWifi.enter()
                }
                NetworkState.OFFLINE -> {
                    shell("cmd connectivity airplane-mode enable")
                    awaitNoNetwork()
                }
                NetworkState.BLOCKED -> {
                    restoreOnline()
                    shell("cmd connectivity set-chain3-enabled true")
                    shell("cmd connectivity set-package-networking-enabled false ${context.packageName}")
                    awaitDefaultNetwork(present = false, "the package's firewall deny")
                }
            }
            entered = true
        } finally {
            if (!entered) restoreOnline()
        }
    }

    private fun restoreOnline() {
        shell("cmd connectivity set-package-networking-enabled true ${context.packageName}")
        shell("cmd connectivity set-chain3-enabled false")
        shell("cmd connectivity airplane-mode disable")
        awaitDefaultNetwork(present = true, "an online device")
        MeteredWifi.lift()
    }

    private companion object {
        val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
        const val SETTLE_MILLIS = 30_000L
        const val READING_MILLIS = 10_000L
        const val POLL_MILLIS = 100L

        fun shell(command: String): String {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes().decodeToString() }
        }

        /**
         * Waits until this app's default network is [present] — or gone, which a block and an absence both read as —
         * failing the entry, never the clause, when the platform does not get there.
         */
        fun awaitDefaultNetwork(present: Boolean, entering: String) {
            val deadline = System.currentTimeMillis() + SETTLE_MILLIS
            while ((connectivity.activeNetwork != null) != present) {
                check(
                    System.currentTimeMillis() < deadline,
                ) { "the emulator did not reach $entering within ${SETTLE_MILLIS}ms" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        /**
         * Waits until airplane mode has taken EVERY network down, not just the default one. The radios go one at a
         * time: on a loaded emulator Wi-Fi can go while cellular still lives, so the default reads `null` for a moment,
         * then cellular takes over, then it goes too (measured on CI, run 37210658344). A monitor started in that gap
         * reads the dying cellular network or registers after it is gone, and its first reading is `Online` or never
         * comes — a race of the entry, not a fact about the adapter.
         */
        @Suppress("DEPRECATION")
        fun awaitNoNetwork() {
            val deadline = System.currentTimeMillis() + SETTLE_MILLIS
            while (connectivity.activeNetworkInfo != null || connectivity.allNetworks.any(::carriesInternet)) {
                check(
                    System.currentTimeMillis() < deadline,
                ) { "the emulator did not reach airplane mode within ${SETTLE_MILLIS}ms" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        private fun carriesInternet(network: Network): Boolean =
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
