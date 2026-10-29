package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.BackendCall
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.step
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun first_join_loads_details_then_enrolls_and_joins() = behaviour {
        val event = given { registeredEvent(name = "Anna's Wedding") } // exists, but not yet joined

        // The event and the phase the gate reached — not the whole state: a loaded phase also carries the range the
        // reduction resolved, and restating that here would assert the resolution rules a second time.
        // `RangeResolutionTest` owns those.
        lateinit var gate: Seen<Layer.JoiningEvent>
        whenever { openLink(inviteLink(event)) }
        then { gate = screen<Layer.JoiningEvent> { it.readyGate() != null } }
        assertEquals(event, gate.value.eventId)
        assertEquals("Anna's Wedding", (gate.value.phase as JoinPhase.Detailed).eventName)

        // Outcomes: the membership is this event, and a register-only EMPTY manifest was deposited (membership).
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            event<WorldEvent.MemberJoined> { it.event == event }
            event<WorldEvent.ManifestPublished> { it.event == event && it.added.isEmpty() } // enrollment writes it empty
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An event that does not exist cannot be joined",
    )
    fun a_missing_event_blocks_the_join() = behaviour {
        // A well-formed id no backend ever minted.
        val event = "33333333-3333-4333-8333-333333333333"

        whenever { openLink(inviteLink(event)) }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.NotFound }
            neverScreen<Layer.Joined>()
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An unreachable server can be retried",
    )
    fun a_load_failure_is_retryable() = behaviour {
        val event = given {
            registeredEvent(name = "Anna's Wedding").also {
                step("the backend unreachable", act = { backend.offline(true) }, until = {})
            }
        }

        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed } }

        whenever {
            backend.offline(false)
            tap(UiIntent.RetryLoad)
        }
        then { screen<Layer.JoiningEvent> { it.readyGate() != null } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A failed join is retryable and leaves nothing behind",
    )
    fun a_failed_enrollment_does_not_join() = behaviour {
        given {
            gateReady(registeredEvent(name = "Anna's Wedding"))
            step("the backend unreachable", act = { backend.offline(true) }, until = {}) // enrollment PUT now fails
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.CommitFailed }
            neverScreen<Layer.Joined>() // not joined
            never<WorldEvent.MemberJoined>()
            settled { true }
        }
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
    fun a_switch_leaves_the_current_event_then_joins_the_new_one_as_configured() = behaviour {
        val (next, current) = given { switchOffered() }

        // Confirm = the leave alone. E is gone and the SAME pending join is now the full-screen
        // surface for F, where the choices are made.
        whenever { tap(UiIntent.ConfirmSwitch) }
        then {
            screen<Layer.JoiningEvent> { it.eventId == next }
            event<WorldEvent.MemberLeft> { it.event == current } // and departed the old
        }

        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin(Choices(direction = Direction.DownloadOnly, saveToAlbum = true)) }
        then {
            joined = screen<Layer.Joined> { it.membership.eventId == next } // switched
            event<WorldEvent.MemberJoined> { it.event == next } // enrolled in the new event
        }
        val membership = joined.value.membership
        assertEquals(false to true, membership.shares to membership.receives) // the member's direction, not Both
        assertEquals(true, membership.savesToAlbum) // the member's album opt-in
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "Opening another event's invite asks before switching")
    fun a_switch_does_not_block_on_the_departed_events_delete() = behaviour {
        val (next, current) = given {
            // The backend holds E's DELETE open, so it never completes while the switch runs.
            step("the leave held", act = { backend.hold(BackendCall.LEAVE) }, until = {})
            switchOffered()
        }

        // The join surface appears even though E's DELETE is still pending: the leave's local teardown
        // never waits on the departed event's fire-and-forget backend notify.
        whenever { tap(UiIntent.ConfirmSwitch) }
        then {
            screen<Layer.JoiningEvent> { it.eventId == next }
            event<WorldEvent.BackendHeld> { it.call == BackendCall.LEAVE }
        }

        // …and the new event's join completes with E's DELETE still hanging.
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> { it.membership.eventId == next }
            event<WorldEvent.MemberJoined> { it.event == next } // enrolled in the new event
            never<WorldEvent.MemberLeft>() // E's DELETE never gated either step
            settled { true }
        }

        whenever { backend.release(BackendCall.LEAVE) }
        then { event<WorldEvent.MemberLeft> { it.event == current } }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "An invite opens the app on its join screen")
    fun the_same_link_delivered_twice_enrolls_once() = behaviour {
        // The platform delivers one opened link MORE THAN ONCE: measured on
        // build 687, the scene delegate's connection and SwiftUI's `.onOpenURL` both fired for the same
        // URL — ~130 ms apart on an iOS 18.7.9 cold launch, and 8 ms apart on iOS 26.6 while running.
        // Both hooks stay live because neither is reliable on every OS, so "exactly once" is enforced by
        // the gate rather than by the hook arrangement.
        val link = given {
            val event = registeredEvent(name = "Anna's Wedding")
            val link = step("its invite", act = { inviteLink(event) }, until = {})
            step(
                "its gate opened twice — the duplicate delivery while the gate is open",
                act = {
                    openLink(link)
                    openLink(link)
                },
                until = { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } },
            )
            step("joined through it", act = { confirmJoin() }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
            link
        }

        whenever { openLink(link) } // and once more, joined
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
    fun an_untouched_gate_commits_the_album() = behaviour {
        // The seed is only a claim until it crosses `JoinEvent`. Every other join case here sets the album
        // explicitly, so without this one nothing covers the default actually reaching the persisted membership.
        given { gateReady(registeredEvent(name = "Anna's Wedding")) }

        // Touch nothing — confirm the surface exactly as it loaded.
        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin() }
        then { joined = screen<Layer.Joined>() }
        assertEquals(true, joined.value.membership.savesToAlbum)
    }

    @Test
    @Verifies(spec = "join-event", requirement = "The shared capture range is always bounded and chosen by the member")
    fun join_persists_the_capture_date_cutoff_through_the_provision_path() = behaviour {
        // Regression: the chosen cutoff must survive the confirm → the join → the stored membership. A wiring that
        // drops it (as an early iOS build did) leaves the extension whole-library.
        //
        // The cutoff here is ABOVE the event's start (and inside its window), so the floor binds nothing and it
        // lands verbatim.
        val event = given { registeredEvent(name = "Anna's Wedding").also { gateReady(it) } }

        val cutoff = "2026-06-10T00:00:00Z"

        lateinit var joined: Seen<Layer.Joined>
        whenever { confirmJoin(Choices(cutoff = cutoff)) }
        then { joined = screen<Layer.Joined> { it.membership.eventId == event } }

        // The calendar chooses a day, in the device's zone: the chosen day is what must reach the membership.
        assertEquals(
            "2026-06-10",
            joined.value.membership.sharesFrom.toString().take(10),
            "the cutoff must be persisted in config",
        )
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
    private fun Layer.JoiningEvent.readyGate(): JoinPhase.Detailed? =
        (phase as? JoinPhase.Detailed)?.takeIf { it.step == JoinPhase.Detailed.Step.Ready }

    /** [event]'s invite opened, its join gate ready to join. */
    private suspend fun Given.gateReady(event: String) = step("its join gate ready", act = {
        openLink(inviteLink(event))
    }, until = { screen<Layer.JoiningEvent> { it.eventId == event && it.readyGate() != null } })

    /**
     * Joined to E ("Summer Trip") with F ("Anna's Wedding") existing, and F's invite opened: the switch offered, ready.
     * Answers F and E.
     */
    private suspend fun Given.switchOffered(): Pair<String, String> {
        val next = registeredEvent(name = "Anna's Wedding") // F exists to switch to
        val current = createdEvent(name = "Summer Trip") // already joined to E
        step("joined to it", act = { confirmJoin() }, until = {
            screen<Layer.Joined>()
            settled { true }
        })
        step("F's invite opened", act = { openLink(inviteLink(next)) }, until = {
            screen<Layer.Joined> { it.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }
        })
        return next to current
    }

    private companion object {
        /** Past the retention deadline of every event these tests create. */
        const val PAST_EVERY_DEADLINE = "2027-01-01T00:00:00Z"

        /** What a re-provision sends (`api/openapi.yaml`'s operationIds): an enrolment, and the manifest republished. */
        val REPROVISION = setOf("joinEvent", "publishManifest")
    }
}
