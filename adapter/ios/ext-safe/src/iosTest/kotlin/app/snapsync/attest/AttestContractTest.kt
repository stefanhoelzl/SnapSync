package app.snapsync.attest

import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.keychain.IosSecureStore
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.AttestStore
import app.snapsync.services.identity.AttestState
import kotlin.test.Test

/**
 * App Attest and the attestation store (`AttestState` over the Keychain), LIVE in the simulator's Kotlin/Native test executable (capability
 * `docs/architecture.md`), each built with its production defaults.
 *
 * This host presents exactly one state of each: `DCAppAttestService.isSupported` is false on a simulator, and
 * `securityd` refuses the unentitled executable every `SecItem*` call with `-25291`. Both are the states a
 * device lands in too — the upload extension has no App Attest, and a background wake before first unlock
 * cannot read the Keychain — so the refusal paths run against the real APIs on every build. The supported
 * ceremony and the readable store are recorded on a device and replayed (`AttestReplayContractTest`).
 */
class AttestContractTest {

    private val key = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DeviceIntegrityState.UNAVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String): Entered<DeviceIntegrity> =
            if (state == DeviceIntegrityState.UNAVAILABLE) {
                Entered.Ready(IosDeviceIntegrity())
            } else {
                Entered.Unreachable("a simulator has no App Attest: DCAppAttestService.isSupported is false")
            }
    }

    @Test
    fun `App Attest satisfies the DeviceIntegrity contract on this host`() = verify(DeviceIntegrityContract, key)

    private val store = object : Binding<AttestStoreState, AttestStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(AttestStoreState.INACCESSIBLE)

        override fun create(state: AttestStoreState, clauseId: String): Entered<AttestStore> =
            if (state == AttestStoreState.INACCESSIBLE) {
                Entered.Ready(AttestState(IosSecureStore()))
            } else {
                Entered.Unreachable("unentitled test executable: securityd refuses every Keychain call (-25291)")
            }
    }

    @Test
    fun `the Keychain satisfies the AttestStore contract on this host`() = verify(AttestStoreContract, store)
}
