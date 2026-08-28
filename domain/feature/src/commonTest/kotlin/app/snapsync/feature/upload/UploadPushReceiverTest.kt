package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import app.snapsync.model.PermissionStatus
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
        fun receiver(active: String?, permission: PermissionStatus = PermissionStatus.GRANTED) =
            UploadPushReceiver(activeEventId = { active }, pump = pump, permission = { permission })
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

    // ---- the read discipline, moved here from the silent-push fan-out ---------------------------------

    /**
     * `limited-photo-access` ("No autonomous library reads under a limited grant") names *"the upload half
     * of the silent-push fan-out"* among exactly three triggers that must skip their `PHAsset` work under a
     * partial grant, and fixes reads at two moments — a cold-foreground baseline and a selection-change
     * emission — which a push is neither of.
     *
     * The guard used to sit in the fan-out that composed the receivers. That made it an invoker-gate, whose
     * soundness depended on that fan-out enumerating everyone who might read; it lives here now, beside the
     * active-event guard, where the component that would do the reading decides.
     */
    @Test
    fun a_push_under_a_partial_grant_pumps_nothing() = runTest {
        val f = Fixture()

        f.receiver(active = "E", permission = PermissionStatus.LIMITED).onSilentPush("E")

        assertEquals(0, f.cycles, "a partial grant is exactly what this guard refuses")
        assertEquals(0, f.scheduler.scheduled)
    }

    @Test
    fun a_push_without_usable_access_pumps_nothing() = runTest {
        for (p in listOf(PermissionStatus.NOT_DETERMINED, PermissionStatus.DENIED)) {
            val f = Fixture()

            f.receiver(active = "E", permission = p).onSilentPush("E")

            assertEquals(0, f.cycles, "permission=$p")
            assertEquals(0, f.scheduler.scheduled, "permission=$p")
        }
    }

    @Test
    fun the_guard_is_granted_exactly_not_merely_usable_access() = runTest {
        // `grantsPhotoAccess` is true for LIMITED, and using it here would silently re-admit the case the
        // read discipline exists to refuse. Stated as its own test because the two readings differ by one
        // enum member and a reviewer cannot see which one a call site meant.
        val granted = Fixture()
        granted.receiver(active = "E", permission = PermissionStatus.GRANTED).onSilentPush("E")
        assertEquals(1, granted.cycles)

        val limited = Fixture()
        limited.receiver(active = "E", permission = PermissionStatus.LIMITED).onSilentPush("E")
        assertEquals(0, limited.cycles)
    }
}
