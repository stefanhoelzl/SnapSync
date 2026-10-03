package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.NetworkMonitorContract
import app.snapsync.contracts.NetworkState
import app.snapsync.contracts.verify
import app.snapsync.ports.NetworkMonitor
import kotlin.test.Test

/**
 * [NetworkMonitorContract] against the real [AndroidNetworkMonitor] on the emulator, each state entered through the
 * platform's own shell commands — the ones a person's switches stand behind:
 *  - OFFLINE: airplane mode, which takes the emulator's Wi-Fi and cellular down together;
 *  - BLOCKED: the connectivity service's `OEM_DENY_3` firewall chain with this package denied — a block of THIS app's
 *    network while the device stays online, as a vendor's per-app network switch sets it.
 *
 * Every entry waits until the platform has applied it — the default network gone, or back — so a clause never races
 * the switch it was entered by; every disposal restores the online device, whatever the clause did.
 */
class AndroidNetworkMonitorContractTest {

    private val binding = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(NetworkState.ONLINE, NetworkState.OFFLINE, NetworkState.BLOCKED)

        override fun create(state: NetworkState, clauseId: String): Entered<NetworkMonitor> {
            when (state) {
                NetworkState.ONLINE -> restoreOnline()
                NetworkState.OFFLINE -> {
                    shell("cmd connectivity airplane-mode enable")
                    awaitDefaultNetwork(present = false, "airplane mode")
                }
                NetworkState.BLOCKED -> {
                    restoreOnline()
                    shell("cmd connectivity set-chain3-enabled true")
                    shell("cmd connectivity set-package-networking-enabled false ${context.packageName}")
                    awaitDefaultNetwork(present = false, "the package's firewall deny")
                }
            }
            return Entered.Ready(AndroidNetworkMonitor(context), dispose = ::restoreOnline)
        }
    }

    @Test
    fun `the default-network callback satisfies the NetworkMonitor contract`() = verify(NetworkMonitorContract, binding)

    private fun restoreOnline() {
        shell("cmd connectivity set-package-networking-enabled true ${context.packageName}")
        shell("cmd connectivity set-chain3-enabled false")
        shell("cmd connectivity airplane-mode disable")
        awaitDefaultNetwork(present = true, "an online device")
    }

    private companion object {
        val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
        const val SETTLE_MILLIS = 30_000L
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
                check(System.currentTimeMillis() < deadline) { "the emulator did not reach $entering within ${SETTLE_MILLIS}ms" }
                Thread.sleep(POLL_MILLIS)
            }
        }
    }
}
