package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendCall
import app.snapsync.levers.RigState
import app.snapsync.levers.WorldEvent
import app.snapsync.model.Direction
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam ↔ UI-state integration for the join gate over the real
 * `engine → status → presentation` stack, driven through the control protocol against the backend mock: the deeplink
 * decode, `GET /event/:id` details gate, register-only enrollment PUT, and the switch composition — asserting both
 * `UiState` (the membership the joined layer carries) and backend outcomes (the device manifest, the departure).
 */
class JoinGateIntegrationTest {

    @Test
    @Verifies(spec = "join-event", requirement = "The join screen verifies the event before offering to join")
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "Confirming joins",
    )
    fun first_join_loads_details_then_enrolls_and_joins() = rigTest {
        val event = registerEvent(name = "Anna's Wedding") // exists, but not yet joined

        openLink(inviteLink(event))
        // The event and the phase the gate reached — not the whole state: a loaded phase also carries the range the
        // reduction resolved, and restating that here would assert the resolution rules a second time.
        // `RangeResolutionTest` owns those.
        val gate = awaitState { it.readyGate() != null }.ui.layer as Layer.JoiningEvent
        assertEquals(event, gate.eventId)
        assertEquals("Anna's Wedding", (gate.phase as JoinPhase.Detailed).eventName)

        join() // config flipped present → joined layer

        // Outcomes: the membership is this event, and a register-only EMPTY manifest was deposited (membership).
        assertEquals(event, state().joined!!.membership.eventId)
        val manifest = manifest(event)
        assertTrue(manifest != null && manifest.isEmpty(), "enrollment writes an empty manifest: $manifest")
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An event that does not exist cannot be joined",
    )
    fun a_missing_event_blocks_the_join() = rigTest {
        // A well-formed id no backend ever minted.
        val event = "33333333-3333-4333-8333-333333333333"

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.NotFound }

        assertFalse(state().ready.configResolved)
        assertNull(manifest(event))
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An unreachable server can be retried",
    )
    fun a_load_failure_is_retryable() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        backend.offline(true)
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.LoadFailed }

        backend.offline(false)
        tap(UiIntent.RetryLoad)
        awaitReady()
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A failed join is retryable and leaves nothing behind",
    )
    fun a_failed_enrollment_does_not_join() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event))
        awaitReady()

        backend.offline(true) // enrollment PUT now fails
        confirmJoin()
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
    @Verifies(
        spec = "invite-link",
        requirement = "Opening another event's invite asks before switching",
        scenario = "Confirming leaves, then configures the new event",
    )
    fun a_switch_leaves_the_current_event_then_joins_the_new_one_as_configured() = rigTest {
        val next = registerEvent(name = "Anna's Wedding") // F exists to switch to
        val current = createAndJoin(name = "Summer Trip") // already joined to E

        openLink(inviteLink(next))
        awaitSwitchReady()

        // Confirm = the leave alone. E is gone and the SAME pending join is now the full-screen
        // surface for F, where the choices are made.
        tap(UiIntent.ConfirmSwitch)
        awaitState { it.ui.layer is Layer.JoiningEvent && !it.ready.configResolved }

        confirmJoin(Choices(direction = Direction.DownloadOnly, saveToAlbum = true))
        val membership = awaitState { it.ready.eventId == next }.joined!!.membership

        assertEquals(next, membership.eventId) // switched
        assertEquals(false to true, membership.shares to membership.receives) // the member's direction, not Both
        assertEquals(true, membership.savesToAlbum) // the member's album opt-in
        assertNotNull(manifest(next), "enrolled in the new event")
        eventually<Boolean>(read = { departed(current) }) { it } // and departed the old
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "Opening another event's invite asks before switching")
    fun a_switch_does_not_block_on_the_departed_events_delete() = rigTest {
        val next = registerEvent(name = "Anna's Wedding") // F exists to switch to
        val current = createAndJoin(name = "Summer Trip") // already joined to E

        // The backend holds E's DELETE open, so it never completes while the switch runs.
        backend.hold(BackendCall.LEAVE)

        openLink(inviteLink(next))
        awaitSwitchReady()

        tap(UiIntent.ConfirmSwitch)
        // The join surface appears even though E's DELETE is still pending: the leave's local teardown
        // never waits on the departed event's fire-and-forget backend notify.
        awaitState { it.ui.layer is Layer.JoiningEvent && !it.ready.configResolved }

        confirmJoin()
        // …and the new event's join completes with E's DELETE still hanging.
        awaitState { it.ready.eventId == next }
        assertNotNull(manifest(next), "enrolled in the new event")
        assertFalse(departed(current), "E's DELETE never gated either step")

        backend.release(BackendCall.LEAVE)
        eventually<Boolean>(read = { departed(current) }) { it }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An invite opens the app on its join screen")
    fun the_same_link_delivered_twice_enrolls_once() = behaviour {
        // The platform delivers one opened link MORE THAN ONCE: measured on
        // build 687, the scene delegate's connection and SwiftUI's `.onOpenURL` both fired for the same
        // URL — ~130 ms apart on an iOS 18.7.9 cold launch, and 8 ms apart on iOS 26.6 while running.
        // Both hooks stay live because neither is reliable on every OS, so "exactly once" is enforced by
        // the gate rather than by the hook arrangement.
        //
        // autoJoin is the sharpest case: it auto-confirms with no surface and no tap, so before the
        // duplicate rungs it was the one path a doubled delivery would double-PROVISION.
        val link = given {
            val event = registeredEvent(name = "Anna's Wedding")
            val link = step("the auto-joining invite", act = { inviteLink(event, autoJoin = true) }, until = {})
            step("joined through it", act = { openLink(link) }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
            link
        }

        whenever { openLink(link) } // the duplicate delivery
        // Joined once: a second provision would enrol again and republish the manifest — empty.
        then {
            never<WorldEvent.BackendReceived> { it.operation in REPROVISION }
            neverScreen<Layer.JoiningEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "Reopening the current event's invite changes nothing")
    fun re_scanning_the_joined_event_does_not_clobber_the_manifest() = behaviour {
        val link = given {
            val event = joinedEvent()
            inFront()
            inviteFor(event)
        }

        whenever { openLink(link) } // same event → no-op, no enrolment and no manifest
        then {
            never<WorldEvent.BackendReceived> { it.operation in REPROVISION }
            neverScreen<Layer.JoiningEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(spec = "event-album", requirement = "The album is offered on by default and declinable in one tap")
    fun an_untouched_gate_commits_the_album() = rigTest {
        // The seed is only a claim until it crosses `JoinEvent`. Every other join case here sets the album
        // explicitly, so without this one nothing covers the default actually reaching the persisted membership.
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event))
        awaitReady()

        // Touch nothing — confirm the surface exactly as it loaded.
        join()

        assertEquals(true, state().joined!!.membership.savesToAlbum)
    }

    @Test
    fun autoJoin_auto_confirms_without_a_confirmation() = rigTest {
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event, autoJoin = true))
        val membership = awaitState { it.ready.eventId == event }.joined!!.membership // straight to joined, no tap

        assertEquals(event, membership.eventId)
        assertNotNull(manifest(event))
        // The headless path does NOT inherit the surface's seeds wholesale: the cutoff and direction mirror them,
        // but the album stays off unless the link says otherwise. A default-on here
        // would have every dev/test launch write an album.
        assertEquals(false, membership.savesToAlbum)
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
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A range that breaks the rules is refused",
    )
    fun a_hostile_autoJoin_deeplink_cannot_widen_the_membership_below_the_event_start() = rigTest {
        // THE attack the old floor closed, proven end-to-end through the real stack.
        //
        // `minPhotoDate` is decoded from ANY event link — it is documented as a dev/test key, but nothing stops an
        // attacker putting it in a QR. Unchecked, a QR carrying `autoJoin=true` plus a distant-past cutoff would
        // auto-confirm a join at near-whole-library scope WITHOUT A TAP.
        //
        // The share-range rule lives in `JoinEvent`, which every entry path funnels through — including this headless
        // one. A member's range may now reach before the event (decision record `changes/flexible-share-range`), but
        // never further than an event may last, so a 2001 cutoff is REFUSED: nothing is joined. (A production build no
        // longer honours `autoJoin` at all — `InviteLinkHints`, proven by `InviteLinkHintsWorldTest`; this rig-composed
        // host does, and the rule still defends it.)
        val event = registerEvent(name = "Anna's Wedding")

        openLink(inviteLink(event, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z"))

        neverWithin(what = "a 2001 cutoff joins nothing") { it.ready.eventId == event }
    }

    // ── The membership self-leave ─────────────────────────────────────────────────────────────────────────────
    //
    // The one path that destroys user state without a tap. It runs over the REAL composition — the same
    // `Foreground` flow and `MembershipRefresh` rule the iOS shell wires — so these prove the WIRING, not
    // just the rule (`MembershipRefreshTest` covers the verdict matrix in isolation).

    @Test
    @Verifies(spec = "event-lifetime", requirement = "Deletion is never announced and never touches anyone's library")
    fun a_swept_event_keeps_the_device_in_it_on_the_next_foreground() = behaviour {
        given {
            val event = joinedEvent()
            // The nightly sweep deletes the event out from under a still-active member, and time moves past the
            // deadline the membership persisted at join. BOTH witnesses now hold.
            step("the event swept", act = { backend.sweep(event) }, until = {})
            pastEveryDeadline()
        }
        // The app never leaves an event on its own: the member stays, and is shown the event is over.
        whenever { os.foreground() }
        then {
            settled { true }
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined }
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "A server error never ends a membership",
    )
    fun a_transient_details_failure_never_tears_the_membership_down() = behaviour {
        given {
            joinedEvent()
            // The event is very much alive; the backend just cannot be reached. Past the deadline too, so ONLY the
            // confirmed-absence witness is missing — exactly the systemic-fault shape.
            step("the backend unreachable", act = { backend.offline(true) }, until = {})
            pastEveryDeadline()
        }
        // A fetch that could not tell is never destructive: the membership is untouched.
        whenever { os.foreground() }
        then {
            settled { true }
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined }
        }
    }

    /** The clock past the retention deadline of every event these tests create. */
    private suspend fun Given.pastEveryDeadline() = step(
        "past every deadline",
        act = { advanceTo(PAST_EVERY_DEADLINE) },
        until = { settled { true } },
    )

    // ---- helpers --------------------------------------------------------------------------------

    /** The open join gate's loaded phase, once it offers the confirm. */
    private fun RigState.readyGate(): JoinPhase.Detailed? =
        ((ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.takeIf { it.step == JoinPhase.Detailed.Step.Ready }

    private suspend fun Rig.awaitReady(): JoinPhase.Detailed = awaitState { it.readyGate() != null }.readyGate()!!

    private suspend fun Rig.awaitSwitchReady() {
        awaitState { it.joined?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }
    }

    private suspend fun Rig.departed(event: String): Boolean =
        backend.membership(event).departed

    private companion object {
        /** Past the retention deadline of every event these tests create. */
        const val PAST_EVERY_DEADLINE = "2027-01-01T00:00:00Z"

        /** What a re-provision sends (`api/openapi.yaml`'s operationIds): an enrolment, and the manifest republished. */
        val REPROVISION = setOf("joinEvent", "publishManifest")
    }
}
