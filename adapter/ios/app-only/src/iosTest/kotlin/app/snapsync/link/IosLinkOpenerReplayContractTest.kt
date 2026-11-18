package app.snapsync.link

import app.snapsync.contract.RECORDINGS
import app.snapsync.contract.ReplayingUrlOpenerApi
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.ports.SystemUi
import app.snapsync.systemui.IosSystemUi
import kotlin.test.Test

/**
 * The device's `UIApplication.openURL`, REPLAYED (`docs/architecture.md`): the CURRENT [IosSystemUi] runs
 * against what iOS answered when `test/contracts/recordings/LinkOpener@IOS_DEVICE_APP.rec` (browser allowed) and
 * `….BROWSER_RESTRICTED.rec` (Safari withheld by Screen Time) were recorded, and the current clauses judge. One binding
 * per recording, because the restriction is a precondition a person sets between the two runs.
 *
 * This is what keeps the store button working: an adapter that stops asking iOS through
 * `openURL:options:completionHandler:` — the deprecated one-argument form it used until 2026-09-23 answered
 * `false` and opened nothing — reads `Diverged`. Re-record on the device (the `rig-channel` runbook).
 */
class IosLinkOpenerReplayContractTest {

    private val binding = object : Binding<LinkOpenerState, SystemUi> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val adapter = IosSystemUi::class
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)

        override fun create(state: LinkOpenerState, clauseId: String, log: CallLog): Entered<SystemUi> {
            if (state !in reaches) return Entered.Unreachable("recorded with the browser allowed")
            return replayerFor(RECORDINGS, RECORDING, clauseId) { replayer ->
                Entered.Ready(
                    IosSystemUi(ReplayingUrlOpenerApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    /**
     * Recorded with the browser withheld (Screen Time → Allowed Apps → Safari off, SE2): the refusal of the field's
     * Bugsink SNAPSYNC-71, and what iOS says of itself after it.
     */
    private val restricted = object : Binding<LinkOpenerState, SystemUi> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val adapter = IosSystemUi::class
        override val precondition = "BROWSER_RESTRICTED"
        override val reaches = setOf(LinkOpenerState.RESTRICTED)

        override fun create(state: LinkOpenerState, clauseId: String, log: CallLog): Entered<SystemUi> {
            if (state !in reaches) return Entered.Unreachable("recorded with the browser withheld")
            val name = recordingName(LinkOpenerContract.name, host, null, precondition)
            return replayerFor(RECORDINGS, name, clauseId) { replayer ->
                Entered.Ready(
                    IosSystemUi(ReplayingUrlOpenerApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded device satisfies the LinkOpener contract`() = verify(LinkOpenerContract, binding)

    @Test
    fun `the recorded device with its browser withheld satisfies the LinkOpener contract`() =
        verify(LinkOpenerContract, restricted)

    private companion object {
        const val RECORDING = "LinkOpener@IOS_DEVICE_APP"
    }
}
