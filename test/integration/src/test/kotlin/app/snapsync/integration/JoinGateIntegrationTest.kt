package app.snapsync.integration

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.eventStart
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.Layer
import app.snapsync.presentation.step
import app.snapsync.rig.RigState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Seam ↔ UI-state integration for the join gate (capability `join-event`) over the real
 * `engine → status → presentation` stack, driven through the control protocol against the mini-edge: the deeplink
 * decode, `GET /event/:id` details gate, register-only enrollment PUT, and the switch composition — asserting both
 * `UiState` (the membership the joined layer carries) and backend outcomes (the device manifest, the departure).
 */
class JoinGateIntegrationTest {

    @Test
    fun the_join_gate_normalizes_a_legacy_events_millisecond_startsAt_and_commits_what_it_showed() = rigTest {
        // A LEGACY event — registered with no `startsAt`, as every marker written before start dates
        // existed. The mini-edge synthesizes one from `createdAt`, which (faithfully to the real backend's
        // `toISOString()`) carries MILLISECONDS. The loaded phase must therefore show a SECOND-PRECISION
        // value (the `photo-sharing` format invariant the iOS fetch predicate depends on), and
        // confirming must persist precisely what the surface displayed.
        val event = deviceJson("backend/legacy-event", "name" to "Anna's Wedding").getValue("event").jsonPrimitive.content

        openLink(inviteLink(event))
        val phase = awaitReady().event

        assertEquals(eventStart("2026-01-01T00:00:00Z"), phase.startsAt, "a synthesized millisecond startsAt is truncated")
        assertTrue(!phase.startsAt.at.iso.contains('.'), "a cutoff never carries fractional seconds")

        // Confirm with exactly what the surface showed — the round-trip through the real screen.
        join()
        val membership = state().joined!!.membership

        assertEquals(
            CaptureCutoff(phase.startsAt.at),
            membership.minPhotoDate,
            "the persisted cutoff is the one the join surface displayed",
        )
        assertEquals(phase.startsAt, membership.startsAt, "and the event's start is persisted alongside it, as the floor")
    }

    @Test
    fun first_join_loads_details_then_enrolls_and_joins() = rigTest {
        val event = registerEvent(name = "Anna's Wedding") // exists, but not yet joined

        openLink(inviteLink(event))
        // The event and the phase the gate reached — not the whole state: a loaded phase also carries the range the
        // reduction resolved, and restating that here would assert the resolution rules a second time.
        // `RangeResolutionTest` owns those.
        val gate = awaitState { it.readyGate() != null }.ui.layer as Layer.JoiningEvent
        assertEquals(event, gate.eventId)
        assertEquals("Anna's Wedding", (gate.phase as JoinPhase.Detailed).event.name)

        join() // config flipped present → joined layer

        // Outcomes: the membership is this event, and a register-only EMPTY manifest was deposited (membership).
        assertEquals(event, state().joined!!.membership.eventId)
        val manifest = manifest(event)
        assertTrue(manifest != null && manifest.isEmpty(), "enrollment writes an empty manifest: $manifest")
    }

    @Test
    fun a_missing_event_blocks_the_join() = rigTest {
        // A well-formed id no backend ever minted.
        val event = "33333333-3333-4333-8333-333333333333"

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.NotFound }

        assertFalse(state().ready.configResolved)
        assertNull(manifest(event))
    }

    @Test
    fun a_load_failure_is_retryable() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        device("backend/offline", "on" to "true")
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.LoadFailed }

        device("backend/offline", "on" to "false")
        user("retryLoad")
        awaitReady()
    }

    @Test
    fun a_failed_enrollment_does_not_join() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event))
        awaitReady()

        device("backend/offline", "on" to "true") // enrollment PUT now fails
        user("confirmJoin")
        awaitState {
            ((it.ui.layer as? Layer.JoiningEvent)?.phase)?.step == JoinPhase.Detailed.Step.CommitFailed
        }

        assertFalse(state().ready.configResolved) // not joined
    }

    /**
     * The switch's confirm leaves and **nothing else**; the join that follows is the regular surface, so
     * the member configures the new membership like any joiner. This is the regression the capability
     * exists for: the old compact dialog could only ever produce `Direction.Both` with the album off, and
     * re-scanning the joined event short-circuits as `AlreadyJoined`, so there was no route to any other
     * shape at all. Here the switch lands a **receive-only, album-on** membership.
     */
    @Test
    fun a_switch_leaves_the_current_event_then_joins_the_new_one_as_configured() = rigTest {
        val next = registerEvent(name = "Anna's Wedding") // F exists to switch to
        val current = createAndJoin(name = "Summer Trip") // already joined to E

        openLink(inviteLink(next))
        awaitSwitchReady()

        // Confirm = the leave alone. E is gone and the SAME pending join is now the full-screen
        // surface for F, where the choices are made.
        user("confirmSwitch")
        awaitState { it.ui.layer is Layer.JoiningEvent && !it.ready.configResolved }

        user("confirmJoin", "direction" to "download", "saveToAlbum" to "true")
        val membership = awaitState { it.ready.eventId == next }.joined!!.membership

        assertEquals(next, membership.eventId) // switched
        assertEquals(Direction.DownloadOnly, membership.direction) // the member's direction, not Both
        assertEquals(true, membership.saveToAlbum) // the member's album opt-in
        assertNotNull(manifest(next), "enrolled in the new event")
        eventually<Boolean>(read = { departed(current) }) { it } // and departed the old
    }

    @Test
    fun a_switch_does_not_block_on_the_departed_events_delete() = rigTest {
        val next = registerEvent(name = "Anna's Wedding") // F exists to switch to
        val current = createAndJoin(name = "Summer Trip") // already joined to E

        // The backend holds E's DELETE open, so it never completes while the switch runs.
        device("backend/hold-leave")

        openLink(inviteLink(next))
        awaitSwitchReady()

        user("confirmSwitch")
        // The join surface appears even though E's DELETE is still pending: the leave's local teardown
        // never waits on the departed event's fire-and-forget backend notify.
        awaitState { it.ui.layer is Layer.JoiningEvent && !it.ready.configResolved }

        user("confirmJoin")
        // …and the new event's join completes with E's DELETE still hanging.
        awaitState { it.ready.eventId == next }
        assertNotNull(manifest(next), "enrolled in the new event")
        assertFalse(departed(current), "E's DELETE never gated either step")

        device("backend/release-leave")
        eventually<Boolean>(read = { departed(current) }) { it }
    }

    @Test
    fun the_same_link_delivered_twice_enrolls_once() = rigTest {
        // The platform delivers one opened link MORE THAN ONCE (capability `join-event`): measured on
        // build 687, the scene delegate's connection and SwiftUI's `.onOpenURL` both fired for the same
        // URL — ~130 ms apart on an iOS 18.7.9 cold launch, and 8 ms apart on iOS 26.6 while running.
        // Both hooks stay live because neither is reliable on every OS, so "exactly once" is enforced by
        // the gate rather than by the hook arrangement.
        //
        // autoJoin is the sharpest case: it auto-confirms with no surface and no tap, so before the
        // duplicate rungs it was the one path a doubled delivery would double-PROVISION.
        val event = registerEvent(name = "Anna's Wedding")
        val link = inviteLink(event, autoJoin = true)

        openLink(link)
        awaitState { it.ready.eventId == event }
        // A real manifest, written by a real upload cycle.
        shareOnePhoto(event)

        openLink(link) // the duplicate delivery
        awaitState { it.ready.eventId == event }

        // Joined once, to that event — and the second delivery re-enrolled nothing: a second provision
        // republishes an EMPTY manifest, so a surviving non-empty one is the oracle.
        manifestHolds(event, "a duplicate delivery must not re-provision")
        assertEquals(event, state().ready.eventId)
    }

    @Test
    fun re_scanning_the_joined_event_does_not_clobber_the_manifest() = rigTest {
        val event = createAndJoin(name = "Anna's Wedding")
        // A real (non-empty) manifest already written by a prior upload cycle.
        shareOnePhoto(event)

        openLink(inviteLink(event)) // same event → no-op, no enrollment PUT

        // Still joined, and the real manifest was NOT clobbered to empty.
        manifestHolds(event, "the real manifest must survive a re-scan")
        assertEquals(event, state().ready.eventId)
    }

    @Test
    fun an_untouched_gate_commits_the_album() = rigTest {
        // The seed is only a claim until it crosses `JoinEvent`. Every other join case here sets the album
        // explicitly, so without this one nothing covers the default actually reaching the persisted membership
        // (capability `event-album`).
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event))
        awaitReady()

        // Touch nothing — confirm the surface exactly as it loaded.
        join()

        assertEquals(true, state().joined!!.membership.saveToAlbum)
    }

    @Test
    fun autoJoin_auto_confirms_without_a_confirmation() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event, autoJoin = true))
        val membership = awaitState { it.ready.eventId == event }.joined!!.membership // straight to joined, no tap

        assertEquals(event, membership.eventId)
        assertNotNull(manifest(event))
        // The headless path does NOT inherit the surface's seeds wholesale: the cutoff and direction mirror them,
        // but the album stays off unless the link says otherwise (capability `event-album`). A default-on here
        // would have every dev/test launch write an album.
        assertEquals(false, membership.saveToAlbum)
    }

    @Test
    fun join_persists_the_capture_date_cutoff_through_the_provision_path() = rigTest {
        // Regression: the chosen cutoff must survive decode → autoConfirm → commitJoin → join →
        // provision → config. A wiring that drops it (as an early iOS build did) leaves the extension
        // whole-library. An autoJoin deeplink carries the dev/test cutoff explicitly.
        //
        // The cutoff here is ABOVE the event's start (and inside its window), so the floor binds nothing and it
        // lands verbatim.
        val event = registerEvent(name = "Anna's Wedding")

        val cutoff = "2026-06-10T00:00:00Z"
        openLink(inviteLink(event, autoJoin = true, minPhotoDate = cutoff))
        val joined = awaitState { it.ready.eventId == event }

        assertEquals(cutoff, joined.ready.minPhotoDate, "the cutoff must be persisted in config")
    }

    @Test
    fun a_hostile_autoJoin_deeplink_cannot_widen_the_membership_below_the_event_start() = rigTest {
        // THE attack the floor closes, proven end-to-end through the real stack.
        //
        // `minPhotoDate` is decoded from ANY event link — it is documented as a dev/test key, but
        // nothing stops an attacker putting it in a QR. Before the floor, a QR carrying `autoJoin=true`
        // plus a distant-past cutoff auto-confirmed a join at near-whole-library scope WITHOUT A TAP:
        // every photo the guest had ever taken would upload into a stranger's event.
        //
        // The clamp lives in `JoinEvent`, which every entry path funnels through — including this headless
        // one, which has no surface on which a user could notice anything was wrong.
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z"))
        val membership = awaitState { it.ready.eventId == event }.joined!!.membership

        assertEquals(membership.startsAt.at, membership.minPhotoDate.at, "the 2001 cutoff must not survive the clamp")
        assertTrue(!membership.minPhotoDate.at.iso.startsWith("2001"))
        assertTrue(
            membership.minPhotoDate.at >= membership.startsAt.at,
            "the floor invariant holds for every reachable membership",
        )
    }

    // ── The membership self-leave (capability `manage-membership`) ────────────────────────────────────────
    //
    // The one path that destroys user state without a tap. It runs over the REAL composition — the same
    // `Foreground` flow and `MembershipRefresh` rule the iOS shell wires — so these prove the WIRING, not
    // just the rule (`MembershipRefreshTest` covers the verdict matrix in isolation).

    @Test
    fun a_swept_event_returns_the_device_to_unjoined_on_the_next_foreground() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")
        openLink(inviteLink(event))
        awaitReady()
        join()
        assertEquals(event, state().ready.eventId)

        // The nightly sweep deletes the event out from under a still-active member, and time moves
        // past the deadline the membership persisted at join. BOTH witnesses now hold.
        device("backend/sweep", "event" to event)
        device("clock/advance", "to" to PAST_EVERY_DEADLINE)

        os("app", "onForeground")
        awaitState { it.ui.layer is Layer.CreateEvent }
        assertFalse(state().ready.configResolved, "the membership is torn down and the device is back at the setup gate")
    }

    @Test
    fun a_transient_details_failure_never_tears_the_membership_down() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")
        openLink(inviteLink(event))
        awaitReady()
        join()
        val joined = state().joined!!.membership

        // The event is very much alive; the backend just cannot be reached. Past the deadline too, so
        // ONLY the confirmed-absence witness is missing — exactly the systemic-fault shape.
        device("backend/offline", "on" to "true")
        device("clock/advance", "to" to PAST_EVERY_DEADLINE)

        os("app", "onForeground")
        // A NEGATIVE assertion, so it needs a bounded wait rather than an await-until: give the flow's escaping
        // launch real time to do the wrong thing, and assert it never does.
        neverWithin(what = "a fetch that could not tell is never destructive") { !it.ready.configResolved }
        assertEquals(joined, state().joined?.membership, "the membership is untouched")
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The open join gate's loaded phase, once it offers the confirm. */
    private fun RigState.readyGate(): JoinPhase.Detailed? =
        ((ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.takeIf { it.step == JoinPhase.Detailed.Step.Ready }

    private suspend fun Rig.awaitReady(): JoinPhase.Detailed = awaitState { it.readyGate() != null }.readyGate()!!

    private suspend fun Rig.awaitSwitchReady() {
        awaitState { it.joined?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }
    }

    private suspend fun Rig.departed(event: String): Boolean =
        deviceJson("backend/departed", "event" to event).getValue("departed").jsonPrimitive.boolean

    /** Upload one own photo to completion, so the backend holds a NON-empty manifest for [event]. */
    private suspend fun Rig.shareOnePhoto(event: String) {
        addPhoto("A")
        uploadAll()
        eventually(read = { manifest(event) }) { it != null && "A" in it }
    }

    /**
     * Assert the backend's manifest for [event] keeps listing the shared photo for a bounded window — the negative
     * a re-provision would break by republishing an empty manifest, asynchronously.
     */
    private suspend fun Rig.manifestHolds(event: String, what: String, window: Duration = 500.milliseconds) {
        val deadline = TimeSource.Monotonic.markNow() + window
        while (deadline.hasNotPassedNow()) {
            val manifest = manifest(event)
            if (manifest == null || "A" !in manifest) fail("$what — but the manifest became $manifest")
            delay(50.milliseconds)
        }
    }

    private companion object {
        /** Past the retention deadline of every event these tests create. */
        const val PAST_EVERY_DEADLINE = "2027-01-01T00:00:00Z"
    }
}
