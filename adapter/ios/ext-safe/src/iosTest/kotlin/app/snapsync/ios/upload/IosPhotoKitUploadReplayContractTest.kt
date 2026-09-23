package app.snapsync.ios.upload

import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.BackgroundTransferState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.verify
import app.snapsync.keychain.contract.RECORDINGS
import kotlin.test.Test

/**
 * The upload extension's PhotoKit job queue, REPLAYED (capability `port-contracts`): the CURRENT
 * [IosPhotoKitUploadPlatform] runs against what iOS answered — and what the upload receiver said landed — when
 * `test/contracts/recordings/BackgroundTransfer@IOS_DEVICE_PHOTOKIT_EXT.rec` was recorded inside the extension on a
 * device, and the current clauses judge.
 *
 * The clauses poll; the replay stops each poll where the device stopped, because every input the poll reads is
 * answered from the recording in order. An adapter that asks iOS something else reads `Diverged`: re-record inside
 * the extension (the `rig-channel` runbook). A recorded answer that violates a clause reads `Failed`.
 */
class IosPhotoKitUploadReplayContractTest {

    private val name = recordingName(BackgroundTransferContract.name, Host.IOS_DEVICE_PHOTOKIT_EXT, null)
    private val recording = RECORDINGS[name]?.let(Recording::parse)

    private val binding = object : Binding<BackgroundTransferState, TransferUnderTest> {
        override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
        override val kind = BindingKind.Replay
        override val reaches = setOf(BackgroundTransferState.IDLE, BackgroundTransferState.SINGLE_FREE_RETRY)

        override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
            if (state !in reaches) return Entered.Unreachable(EXTENSION_UNREACHABLE_AT_CAP)
            val tape = recording ?: return Entered.Unreachable("no recording $name.rec — record it inside the extension over the rig")
            val block = tape.blocks[clauseId] ?: return Entered.Unreachable("$name.rec holds no block for $clauseId — re-record")
            val replayer = Replayer(clauseId, block)
            return photoKitTransferInState(
                state = state,
                clauseId = clauseId,
                api = ReplayingUploadJobApi(replayer),
                objects = replayingFixtureObjects(replayer),
                photo = { ReplayPhoto },
                afterDispose = replayer::assertExhausted,
            )
        }
    }

    @Test
    fun `the recorded extension job queue satisfies the BackgroundTransfer contract`() =
        verify(BackgroundTransferContract, binding)
}
