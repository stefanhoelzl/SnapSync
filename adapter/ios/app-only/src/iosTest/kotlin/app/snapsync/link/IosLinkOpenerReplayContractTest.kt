package app.snapsync.link

import app.snapsync.contract.RECORDINGS
import app.snapsync.contract.ReplayingUrlOpenerApi
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.verify
import app.snapsync.ports.LinkOpener
import kotlin.test.Test

/**
 * The device's `UIApplication.openURL`, REPLAYED (`docs/architecture.md`): the CURRENT [IosLinkOpener] runs
 * against what iOS answered when `test/contracts/recordings/LinkOpener@IOS_DEVICE_APP.rec` was recorded, and the
 * current clauses judge.
 *
 * This is what keeps the store button working: an adapter that stops asking iOS through
 * `openURL:options:completionHandler:` — the deprecated one-argument form it used until 2026-09-23 answered
 * `false` and opened nothing — reads `Diverged`. Re-record on the device (the `rig-channel` runbook).
 */
class IosLinkOpenerReplayContractTest {

    private val recording = RECORDINGS[RECORDING]?.let(Recording::parse)

    private val binding = object : Binding<LinkOpenerState, LinkOpener> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)

        override fun create(state: LinkOpenerState, clauseId: String): Entered<LinkOpener> {
            val tape = recording
                ?: return Entered.Unreachable("no recording $RECORDING.rec — record it on a device over the rig")
            val block = tape.blocks[clauseId]
                ?: return Entered.Unreachable("$RECORDING.rec holds no block for $clauseId — re-record")
            val replayer = Replayer(clauseId, block)
            return Entered.Ready(IosLinkOpener(ReplayingUrlOpenerApi(replayer)), dispose = replayer::assertExhausted)
        }
    }

    @Test
    fun `the recorded device satisfies the LinkOpener contract`() = verify(LinkOpenerContract, binding)

    private companion object {
        const val RECORDING = "LinkOpener@IOS_DEVICE_APP"
    }
}
