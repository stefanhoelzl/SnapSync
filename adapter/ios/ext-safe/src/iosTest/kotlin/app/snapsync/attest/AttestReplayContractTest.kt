package app.snapsync.attest

import app.snapsync.attest.contract.ReplayingAppAttestApi
import app.snapsync.attest.contract.integrityInState
import app.snapsync.attest.contract.attestStoreInState
import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.keychain.contract.RECORDINGS
import app.snapsync.keychain.contract.ReplayingKeychainApi
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.AttestStore
import kotlin.test.Test

/**
 * App Attest and the attestation store on an entitled device, REPLAYED (`docs/architecture.md`): the
 * CURRENT [IosDeviceIntegrity] and `AttestState` over [app.snapsync.keychain.IosSecureStore] run against what iOS answered when
 * `test/contracts/recordings/DeviceIntegrity@IOS_DEVICE_APP.rec` and `AttestStore@IOS_DEVICE_APP.rec` were recorded,
 * and the current clauses judge.
 *
 * What the App Attest replay proves is the adapter's half: the calls it makes, in order, and the
 * `clientDataHash` it computes from each challenge — the value the edge's verifier recomputes. The recorded
 * attestation and assertion bytes are masked (they are a device's credential, and the repository is public),
 * so whether Apple's bytes verify is not proven here; `api/test/attest.test.ts` proves it against a real
 * Apple fixture. A change that asks App Attest or the Keychain something else reads `Diverged`: re-record on
 * the device (the `rig-channel` runbook).
 */
class AttestReplayContractTest {

    private val key = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(DeviceIntegrityState.AVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String): Entered<DeviceIntegrity> {
            if (state !in reaches) return Entered.Unreachable("the app process on a device has App Attest")
            return replayerFor(RECORDINGS, KEY, clauseId) { replayer ->
                integrityInState(ReplayingAppAttestApi(replayer), state, afterDispose = replayer::assertExhausted)
            }
        }
    }

    @Test
    fun `the recorded device App Attest satisfies the DeviceIntegrity contract`() = verify(DeviceIntegrityContract, key)

    private val store = object : Binding<AttestStoreState, AttestStore> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(AttestStoreState.EMPTY, AttestStoreState.HOLDING)

        override fun create(state: AttestStoreState, clauseId: String): Entered<AttestStore> {
            if (state !in reaches) return Entered.Unreachable("the entitled app runs unlocked")
            return replayerFor(RECORDINGS, STORE, clauseId) { replayer ->
                attestStoreInState(ReplayingKeychainApi(replayer), state, clauseId, afterDispose = replayer::assertExhausted)
            }
        }
    }

    @Test
    fun `the recorded device Keychain satisfies the AttestStore contract`() = verify(AttestStoreContract, store)

    private companion object {
        const val KEY = "DeviceIntegrity@IOS_DEVICE_APP"
        const val STORE = "AttestStore@IOS_DEVICE_APP"
    }
}
