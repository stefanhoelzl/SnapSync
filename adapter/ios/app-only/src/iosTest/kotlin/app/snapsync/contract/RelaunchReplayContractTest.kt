package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.link.SystemUrlOpenerApi
import app.snapsync.ports.SystemUi
import app.snapsync.systemui.IosSystemUi
import kotlin.test.Test

/**
 * What a phone the operating system relaunched in the background delivered, REPLAYED (`docs/architecture.md`): the
 * CURRENT [IosUrlSessionUploadPlatform] against the session's calls and events when `Upload@IOS_DEVICE_APP.rec` was
 * recorded across the relaunch, and the CURRENT [IosSystemUi] against the share sheet's answer in that same windowless
 * launch (`SharePresenter@IOS_DEVICE_APP.rec`). A `Diverged` means the adapter now asks iOS something else, or in
 * another order: re-record (the `rig-channel` runbook).
 */
class RelaunchReplayContractTest {

    private val upload = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(UploadState.RELAUNCHED_WITH_EVENTS)

        override fun create(state: UploadState, clauseId: String, log: CallLog): Entered<UploadUnderTest> {
            if (state !in reaches) return Entered.Unreachable("this recording holds a relaunch; $state runs live")
            return replayerFor(RECORDINGS, recordingName(UploadContract.name, host, null), clauseId) { replayer ->
                val api = ReplayingUploadSessionApi(replayer)
                relaunchedUpload(
                    api,
                    handler = {},
                    log = log,
                    // The system's relaunch is the block's first event: the replay hands it over where the device did.
                    beforeDeliver = {
                        val due = replayer.takeEvents()
                        if (due != listOf("handleEvents(id=$CONTRACT_SESSION)")) {
                            throw Divergence("the recording does not open with the relaunch of $CONTRACT_SESSION: $due")
                        }
                    },
                    afterDispose = replayer::assertExhausted,
                )
            }
        }
    }

    private val share = object : Binding<SharePresenterState, SystemUi> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(SharePresenterState.NO_WINDOW)

        override fun create(state: SharePresenterState, clauseId: String, log: CallLog): Entered<SystemUi> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "this recording holds a windowless launch; $state runs live",
                )
            }
            return replayerFor(
                RECORDINGS,
                recordingName(SharePresenterContract.name, host, null),
                clauseId,
            ) { replayer ->
                Entered.Ready(
                    IosSystemUi(SystemUrlOpenerApi, ReplayingShareSheetApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded relaunch satisfies the Upload contract`() = verify(UploadContract, upload)

    @Test
    fun `the recorded windowless launch satisfies the SharePresenter contract`() = verify(SharePresenterContract, share)
}
