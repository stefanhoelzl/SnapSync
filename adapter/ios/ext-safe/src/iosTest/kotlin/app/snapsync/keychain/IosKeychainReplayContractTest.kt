package app.snapsync.keychain

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.verify
import app.snapsync.keychain.contract.DEVICE_UNREACHABLE_INACCESSIBLE
import app.snapsync.keychain.contract.RECORDINGS
import app.snapsync.keychain.contract.ReplayingKeychainApi
import app.snapsync.keychain.contract.keychainInState
import app.snapsync.ports.SecureStore
import kotlin.test.Test

/**
 * The entitled device's Keychain, REPLAYED (`docs/architecture.md`): the CURRENT [IosKeychain] runs
 * against what iOS answered when `test/contracts/recordings/SecureStore@IOS_DEVICE_APP.rec` was recorded,
 * and the current clauses judge.
 *
 * A change to the adapter that leaves its `SecItem*` calls identical replays green. One that asks iOS
 * something else — a different attribute, another order, a call more or fewer — reads `Diverged`: re-record
 * on the device (the `rig-channel` runbook). A recorded answer that violates a clause reads `Failed`, which
 * re-recording does not fix.
 */
class IosKeychainReplayContractTest {

    private val recording = RECORDINGS[RECORDING]?.let(Recording::parse)

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(
            SecureStoreState.EMPTY,
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            SecureStoreState.HOLDING_RESTRICTED,
        )

        override fun create(state: SecureStoreState, clauseId: String): Entered<SecureStore> {
            if (state !in reaches) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE)
            val tape = recording
                ?: return Entered.Unreachable("no recording $RECORDING.rec — record it on a device over the rig")
            val block = tape.blocks[clauseId]
                ?: return Entered.Unreachable("$RECORDING.rec holds no block for $clauseId — re-record")
            val replayer = Replayer(clauseId, block)
            return keychainInState(ReplayingKeychainApi(replayer), state, clauseId, afterDispose = replayer::assertExhausted)
        }
    }

    @Test
    fun `the recorded device Keychain satisfies the SecureStore contract`() = verify(SecureStoreContract, binding)

    private companion object {
        const val RECORDING = "SecureStore@IOS_DEVICE_APP"
    }
}
