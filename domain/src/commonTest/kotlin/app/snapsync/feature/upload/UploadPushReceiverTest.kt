package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [UploadPushReceiver] — the active-event guard for the upload arm, mirroring `DownloadPushReceiver`.
 *
 * The guard answers "is this push for my current event". It is **orthogonal** to the direction gate in
 * `UploadCycle` ("should this device ever upload here"), and the last test here pins that split: a push to a
 * download-only membership passes this guard and still uploads nothing. The cross-arm fan-out is tested with
 * the silent-push flow that now owns it (`flow/SilentPushTest`).
 */
class UploadPushReceiverTest {

    private class FakeScheduler : BackgroundScheduler {
        var scheduled = 0
        override fun scheduleNext() { scheduled++ }
        override fun cancel() {}
    }

    private class Fixture(result: CycleResult = CycleResult.COMPLETED) {
        var cycles = 0
        val scheduler = FakeScheduler()
        val pump = BackgroundUploadPump(
            runCycle = { cycles++; result },
            scheduler = scheduler,
        )
        fun receiver(active: String?) = UploadPushReceiver(activeEventId = { active }, pump = pump)
    }

    @Test
    fun a_push_for_the_active_event_pumps_a_cycle() = runTest {
        val f = Fixture()

        f.receiver(active = "E").onSilentPush("E")

        assertEquals(1, f.cycles)
        assertEquals(1, f.scheduler.scheduled, "and re-arms — a push is the reliable wake")
    }

    @Test
    fun a_push_for_another_event_pumps_nothing() = runTest {
        val f = Fixture()

        f.receiver(active = "E").onSilentPush("OTHER")

        assertEquals(0, f.cycles)
        assertEquals(0, f.scheduler.scheduled)
    }

    @Test
    fun a_push_for_a_locally_left_event_pumps_nothing() = runTest {
        // Leave is local-only (capability `leave-event`): the backend membership persists, so it keeps
        // pushing this device forever. With the config cleared there is no active event, and the guard is
        // what stops a left event from quietly resuming uploads.
        val f = Fixture()

        f.receiver(active = null).onSilentPush("E")

        assertEquals(0, f.cycles)
        assertEquals(0, f.scheduler.scheduled)
    }

    /**
     * The orthogonality, pinned. The active-event guard PASSES (this really is our event) and the cycle
     * still declines, because the direction gate is a different question answered in a different place. This
     * receiver never learns about direction — if it ever did, there would be two places to get it wrong.
     */
    @Test
    fun a_push_to_a_non_contributing_membership_passes_the_guard_and_still_uploads_nothing() = runTest {
        val f = Fixture(result = CycleResult.SKIPPED) // the cycle's own gate: Contribution.None

        f.receiver(active = "E").onSilentPush("E")

        assertEquals(1, f.cycles, "the guard passes — the push IS for our event")
        assertEquals(0, f.scheduler.scheduled, "but nothing is armed: this device will never upload here")
    }
}
