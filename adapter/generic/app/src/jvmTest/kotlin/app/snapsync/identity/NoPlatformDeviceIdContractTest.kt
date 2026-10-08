package app.snapsync.identity

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PlatformDeviceIdContract
import app.snapsync.contracts.PlatformDeviceIdState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.PlatformDeviceId
import kotlin.test.Test

/** iOS and the JVM offer no stable id this app may use: the identity service then mints a random one. */
class NoPlatformDeviceIdContractTest {

    private val binding = object : Binding<PlatformDeviceIdState, PlatformDeviceId> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(PlatformDeviceIdState.SILENT)
        override fun create(state: PlatformDeviceIdState, clauseId: String, log: CallLog): Entered<PlatformDeviceId> =
            if (state == PlatformDeviceIdState.SILENT) {
                Entered.Ready(NoPlatformDeviceId().recorded(log))
            } else {
                Entered.Unreachable("iOS and the JVM offer no stable id this app may use")
            }
    }

    @Test
    fun `it satisfies the PlatformDeviceId contract as a platform that offers no id`() = verify(
        PlatformDeviceIdContract,
        binding,
    )
}
