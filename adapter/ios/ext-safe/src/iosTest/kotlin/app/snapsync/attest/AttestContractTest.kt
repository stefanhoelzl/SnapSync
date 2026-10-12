package app.snapsync.attest

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.DeviceIntegrity
import kotlin.test.Test

/**
 * App Attest, LIVE in the simulator's Kotlin/Native test executable (`docs/architecture.md`), built with its
 * production defaults.
 *
 * This host presents exactly one state: `DCAppAttestService.isSupported` is false on a simulator, so every call is
 * refused as unsupported — the refusal path runs against the real API on every build. The supported ceremony is
 * recorded on a device and replayed (`AttestReplayContractTest`).
 */
class AttestContractTest {

    private val key = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DeviceIntegrityState.UNAVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String, log: CallLog): Entered<DeviceIntegrity> =
            if (state == DeviceIntegrityState.UNAVAILABLE) {
                Entered.Ready(IosDeviceIntegrity().recorded(log))
            } else {
                Entered.Unreachable("a simulator has no App Attest: DCAppAttestService.isSupported is false")
            }
    }

    @Test
    fun `App Attest satisfies the DeviceIntegrity contract on this host`() = verify(DeviceIntegrityContract, key)
}
