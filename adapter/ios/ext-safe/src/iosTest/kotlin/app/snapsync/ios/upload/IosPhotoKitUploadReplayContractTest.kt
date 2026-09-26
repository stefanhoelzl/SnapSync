package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.verify
import app.snapsync.keychain.contract.RECORDINGS
import kotlin.test.Test

/**
 * The upload extension's PhotoKit job queue, REPLAYED (`docs/architecture.md`): the CURRENT
 * [IosPhotoKitUploadPlatform] runs against what iOS answered — and what the upload receiver said landed — when
 * `test/contracts/recordings/Upload@IOS_DEVICE_PHOTOKIT_EXT.rec` was recorded inside the extension on a
 * device, and the current clauses judge.
 *
 * Each clause's state was prepared across operating-system calls on the device — a job the extension creates is
 * uploaded only after the call returns — and its preparation calls head its block; the replay makes them again, in
 * order, before the clause. An adapter that asks iOS something else reads `Diverged`: re-record inside
 * the extension (the `rig-channel` runbook). A recorded answer that violates a clause reads `Failed`.
 */
class IosPhotoKitUploadReplayContractTest {

    private val name = recordingName(UploadContract.name, Host.IOS_DEVICE_PHOTOKIT_EXT, null)
    private val recording = RECORDINGS[name]?.let(Recording::parse)

    private val binding = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
        override val kind = BindingKind.Replay
        override val reaches = setOf(UploadState.PRESENTED_SUCCEEDED, UploadState.PRESENTED_REFUSED_ONCE, UploadState.PRESENTED_RETRY_SPENT)

        override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
            if (state !in reaches) return Entered.Unreachable(EXTENSION_ONLY_PRESENTED)
            val tape = recording ?: return Entered.Unreachable("no recording $name.rec — record it inside the extension over the rig")
            val block = tape.blocks[clauseId] ?: return Entered.Unreachable("$name.rec holds no block for $clauseId — re-record")
            val replayer = Replayer(clauseId, block)
            // The preparation the device made across operating-system calls, made again here in one go, in order.
            for (call in 1 until callsFor(state)) prepareCall(state, clauseId, call, ReplayingUploadJobApi(replayer), ReplayPhoto)
            return photoKitUploadInState(
                state = state,
                api = ReplayingUploadJobApi(replayer),
                objects = replayingFixtureObjects(replayer),
                photo = ReplayPhoto,
                afterDispose = replayer::assertExhausted,
            )
        }
    }

    @Test
    fun `the recorded extension job queue satisfies the Upload contract`() =
        verify(UploadContract, binding)
}
