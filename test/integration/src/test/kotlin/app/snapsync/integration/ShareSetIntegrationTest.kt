package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The ledger is the current membership's share set**, over the real stack driven through the control protocol:
 * a join loads it from the device's stored-file listing, a leave clears it, and a switch does both — without
 * re-uploading anything the backend already holds, and without a failed listing ever blocking a join.
 *
 * The ledger itself is the app's own bookkeeping, so each test reads its observable twin: the upload jobs the
 * operating system was asked for, the objects the backend holds, and the joined screen's health.
 */
class ShareSetIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "Rejoining shares without re-uploading",
    )
    fun leave_then_rejoin_the_same_event_re_uploads_nothing() = rigTest {
        extensionUploadsOnly()
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

    /** A switch to [eventId]'s link while joined, confirmed, then the regular join. */
    private suspend fun Rig.switchTo(eventId: String) {
        openLink(inviteLink(eventId))
        awaitState { it.joined?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }
        user("confirmSwitch")
        awaitState { !it.ready.configResolved }
        join()
        assertEquals(eventId, state().ready.eventId)
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "A photo in two events is shared to each",
    )
    fun a_switch_starts_the_new_events_share_set_afresh_and_uploads_for_it() = rigTest {
        extensionUploadsOnly()
        val next = registerEvent(name = "Next") // E2, to switch to
        val first = createAndJoin() // E1
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())
        // Work the first membership recorded but never finished: it belongs to E1's share set only.
        addPhoto("B")
        cycle()
        val jobsBefore = jobs().created

        switchTo(next)
        cycle()
        // Each event holds its own bytes (change `per-event-storage-layout`): the new share set starts empty, so A —
        // stored for E1 — and E1's unfinished B are both uploaded for E2, one new job each.
        val after = jobs()
        assertEquals(jobsBefore + 2, after.created, "one new job for each of A and B: $after")
        assertTrue(primaryKey("A") in objects(event = first), "E1 still holds what was stored for it")
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "A photo already in an event is not uploaded again for it")
    fun switching_away_and_back_re_uploads_nothing_the_event_already_holds() = rigTest {
        extensionUploadsOnly()
        val next = registerEvent(name = "Next")
        val first = createAndJoin()
        addPhoto("A")
        uploadAll()
        assertTrue(primaryKey("A") in objects())

        switchTo(next)
        uploadAll()
        val jobsAway = jobs().created
        switchTo(first)
        cycle()

        assertEquals(jobsAway, jobs().created, "A is already in E1: nothing is uploaded again on the way back")
    }

    /**
     * RESHAPED (design D8): the old test compared the ledger's rows around a re-provision of the joined event. The
     * gesture that re-provisions is a member re-scanning their own event's link, and what a reset ledger would do is
     * observable: the in-flight work would be requested again.
     */
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Leaving or switching stops every transfer for the old event",
        scenario = "Rescanning the joined event changes nothing",
    )
    @Verifies(spec = "invite-link", requirement = "Reopening the current event's invite changes nothing")
    fun re_scanning_your_own_events_link_creates_no_new_upload_job_and_re_uploads_nothing() = rigTest {
        extensionUploadsOnly()
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
    @Verifies(
        spec = "photo-sharing",
        requirement = "A photo already in an event is not uploaded again for it",
        scenario = "An offline join may upload again, never duplicate",
    )
    fun a_join_whose_listing_fails_still_joins_and_uploads() = rigTest {
        extensionUploadsOnly()
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
