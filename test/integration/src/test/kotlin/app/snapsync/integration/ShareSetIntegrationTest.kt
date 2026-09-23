package app.snapsync.integration

import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.Layer
import app.snapsync.presentation.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The ledger is the current membership's share set**, over the real stack driven through the control protocol
 * (capabilities `sync-ledger`, `upload-state-reconciliation`, `leave-event`): a join loads it from the device's
 * stored-file listing, a leave clears it, and a switch does both — without re-uploading anything the backend already
 * holds, and without a failed listing ever blocking a join.
 *
 * The ledger itself is the app's own bookkeeping, so each test reads its observable twin: the upload jobs the
 * operating system was asked for, the objects the backend holds, and the joined screen's health.
 */
class ShareSetIntegrationTest {

    @Test
    fun leave_then_rejoin_the_same_event_re_uploads_nothing() = rigTest {
        val event = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        val jobsBefore = jobs().created

        leave()

        // Rejoin through the event's link, as a member re-scanning the QR does.
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready }
        join()
        // The join loaded the stored photo back as done: the screen reads settled with the photo still in the library.
        refresh()
        awaitInSync()
        cycle()

        assertEquals(jobsBefore, jobs().created, "nothing the backend already holds is uploaded again")
    }

    @Test
    fun a_switch_replaces_the_share_set_and_re_uploads_nothing_already_stored() = rigTest {
        val next = registerEvent(name = "Next") // E2, to switch to
        createAndJoin() // E1
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        // Work the first membership recorded but never finished: it belongs to E1's share set only.
        addPhoto("B")
        cycle()
        val jobsBefore = jobs().created

        // A switch: a different event's link while joined, confirmed, then the regular join.
        openLink(inviteLink(next))
        awaitState { it.joined?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }
        user("confirmSwitch")
        awaitState { !it.ready.configResolved }
        join()
        assertEquals(next, state().ready.eventId)

        cycle()
        // E1's unfinished B is not carried over — the new share set starts it afresh with one new job — and the
        // stored A is not uploaded again under the new event.
        val after = jobs()
        assertEquals(jobsBefore + 1, after.created, "exactly one new job — B's, not A's: $after")
        assertTrue(primaryKey("A") !in after.live, "the stored photo is not uploaded again under the new event: $after")
    }

    /**
     * RESHAPED (design D8): the old test compared the ledger's rows around a re-provision of the joined event. The
     * gesture that re-provisions is a member re-scanning their own event's link, and what a reset ledger would do is
     * observable: the in-flight work would be requested again.
     */
    @Test
    fun re_scanning_your_own_events_link_creates_no_new_upload_job_and_re_uploads_nothing() = rigTest {
        val event = createAndJoin()
        addPhoto("A")
        cycle() // A is requested: work in flight
        assertEquals(1, jobs().created)

        openLink(inviteLink(event))
        cycle()

        val after = jobs()
        assertEquals(1, after.created, "a re-scan loads nothing, so the in-flight work is not requested again: $after")
        assertEquals(listOf(primaryKey("A")), after.live)
        assertTrue(objects().isEmpty(), "nothing re-uploaded")
        assertEquals(event, state().ready.eventId)
    }

    @Test
    fun a_join_whose_listing_fails_still_joins_and_uploads() = rigTest {
        createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        leave()
        val jobsBefore = jobs().created

        device("backend/fail-listing", "on" to "true")
        val next = createAndJoin(name = "Next")
        device("backend/fail-listing", "on" to "false")

        assertEquals(next, state().ready.eventId, "the failed listing blocked nothing")
        cycle()
        // The ledger started empty rather than stale — and the cost of the failed load: the photo is uploaded
        // again, idempotently.
        assertTrue(primaryKey("A") in jobs().live, "the photo is requested again")
        assertEquals(jobsBefore + 1, jobs().created)
    }
}
