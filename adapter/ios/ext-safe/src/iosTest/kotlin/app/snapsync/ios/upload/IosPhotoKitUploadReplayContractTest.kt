package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.OsUploadQueueContract
import app.snapsync.contracts.OsUploadQueueState
import app.snapsync.contracts.QueueUnderTest
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.keychain.contract.RECORDINGS
import kotlin.test.Test

/**
 * The upload extension's PhotoKit job queue, REPLAYED (`docs/architecture.md`): the CURRENT
 * [IosPhotoKitUploadPlatform] runs against what iOS answered — and what the upload receiver said landed — when
 * `test/contracts/recordings/OsUploadQueue@IOS_DEVICE_PHOTOKIT_EXT.rec` was recorded inside the extension on a
 * device, and the current clauses judge.
 *
 * Each clause's state was prepared across operating-system calls on the device — a job the extension creates is
 * uploaded only after the call returns — and its preparation calls head its block; the replay makes them again, in
 * order, before the clause. An adapter that asks iOS something else reads `Diverged`: re-record inside
 * the extension (the `rig-channel` runbook). A recorded answer that violates a clause reads `Failed`.
 */
class IosPhotoKitUploadReplayContractTest {

    private val name = recordingName(OsUploadQueueContract.name, Host.IOS_DEVICE_PHOTOKIT_EXT, null)

    private val binding = object : Binding<OsUploadQueueState, QueueUnderTest> {
        override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
        override val kind = BindingKind.Replay
        override val reaches = setOf(
            OsUploadQueueState.IDLE,
            OsUploadQueueState.AT_CAP,
            OsUploadQueueState.PRESENTED_SUCCEEDED,
            OsUploadQueueState.PRESENTED_REFUSED_ONCE,
            OsUploadQueueState.PRESENTED_RETRY_SPENT,
        )

        override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
            if (state !in reaches) return Entered.Unreachable(NOT_RECORDED_IN_THE_EXTENSION)
            return replayerFor(RECORDINGS, name, clauseId, recordedWhere = "inside the extension") { replayer ->
                // The preparation the device made across operating-system calls, made again here in one go, in order.
                for (call in 1 until callsFor(state)) prepareCall(
                    state,
                    clauseId,
                    call,
                    ReplayingUploadJobApi(replayer),
                    ReplayPhoto,
                )
                photoKitUploadInState(
                    state = state,
                    api = ReplayingUploadJobApi(replayer),
                    objects = replayingFixtureObjects(replayer),
                    photo = ReplayPhoto,
                    log = log,
                    afterDispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded extension job queue satisfies the OsUploadQueue contract`() =
        verify(OsUploadQueueContract, binding)
}
