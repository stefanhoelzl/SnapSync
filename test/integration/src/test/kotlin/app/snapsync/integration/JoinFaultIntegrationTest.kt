package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.step
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Loading and joining an event the service fails** (decision record `changes/tell-service-faults`): each way
 * SnapSync's service can fail is told on the join screen in its own words, Retry and Cancel stay, the device is in no
 * event, and only an update loads the details again by itself — over the real composed core, the failure played by
 * the backend mock's per-operation lever.
 */
class JoinFaultIntegrationTest {

    // ── loading the event ────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "A service in trouble can be retried",
    )
    fun a_load_the_service_fails_says_it_is_having_trouble_and_retry_loads_it() = behaviour {
        val invite = given {
            inFront()
            inviteFor(registeredEvent())
        }

        whenever {
            backend.fail(GET_EVENT, BackendFault.SERVER_ERROR)
            openLink(invite)
        }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.TROUBLE }
        }

        whenever { tap(UiIntent.RetryLoad) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An unreachable server can be retried",
    )
    fun a_load_that_gets_no_answer_says_so_and_retry_loads_it_once_the_service_answers() = behaviour {
        val invite = given {
            inFront()
            inviteFor(registeredEvent())
        }

        whenever {
            backend.fail(GET_EVENT, BackendFault.UNREACHABLE)
            openLink(invite)
        }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UNREACHABLE }
        }

        whenever { tap(UiIntent.RetryLoad) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An update in progress loads by itself",
    )
    fun a_load_during_an_update_loads_by_itself_once_the_service_suggests() = behaviour {
        val invite = given {
            inFront()
            inviteFor(registeredEvent())
        }

        whenever {
            backend.fail(GET_EVENT, BackendFault.MAINTENANCE)
            openLink(invite)
        }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UPDATING }
        }

        // Not before the service's suggested wait (the mock suggests 60 s) …
        whenever { advance(59.seconds) }
        then { neverScreen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }

        // … and by itself once it has passed: nothing tapped.
        whenever { advance(1.seconds) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An unexpected failure never strands the user",
    )
    fun a_load_answered_by_something_unreadable_offers_a_way_out() = behaviour {
        val invite = given {
            inFront()
            inviteFor(registeredEvent())
        }

        whenever {
            backend.fail(GET_EVENT, BackendFault.MALFORMED)
            openLink(invite)
        }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed && it.service == ServiceNotice.UNREACHABLE }
        }

        whenever { tap(UiIntent.CancelJoin) }
        then { screen<Layer.CreateEvent>() }
    }

    // ── confirming the join ──────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A join the service fails says which way",
    )
    fun a_join_the_service_fails_says_it_is_having_trouble_and_joins_nothing() =
        aJoinThatMeets(BackendFault.SERVER_ERROR, ServiceNotice.TROUBLE)

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "A join the service fails says which way",
    )
    fun a_join_during_an_update_says_so_and_joins_nothing() = aJoinThatMeets(
        BackendFault.MAINTENANCE,
        ServiceNotice.UPDATING,
    )

    private fun aJoinThatMeets(fault: BackendFault, told: ServiceNotice) = behaviour {
        given { createdEvent() }

        whenever {
            backend.fail(JOIN, fault)
            confirmJoin()
        }
        then {
            screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.service == told }
            neverScreen<Layer.Joined>()
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Joining happens only on confirmation and needs a connection",
        scenario = "An answer the app cannot read is not a join",
    )
    @Verifies(
        spec = "join-event",
        requirement = "The join screen verifies the event before offering to join",
        scenario = "An unexpected failure never strands the user",
    )
    fun a_join_answered_by_something_unreadable_is_not_a_join_and_a_retry_joins() = behaviour {
        given { createdEvent() }

        whenever {
            backend.fail(JOIN, BackendFault.MALFORMED)
            confirmJoin()
        }
        then {
            screen<Layer.JoiningEvent> {
                it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.service == ServiceNotice.UNREACHABLE
            }
            neverScreen<Layer.Joined>()
        }

        whenever { tap(UiIntent.RetryJoin) }
        then { screen<Layer.Joined>() }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The shared capture range is always bounded and chosen by the member",
        scenario = "A choice survives a failed join",
    )
    fun a_choice_survives_a_failed_join_and_the_retry_joins_with_it() = behaviour {
        given { createdEvent() }

        lateinit var chosen: Seen<Layer.JoiningEvent>
        whenever {
            backend.fail(JOIN, BackendFault.SERVER_ERROR)
            confirmJoin(Choices(cutoff = CUSTOM_FROM))
        }
        then {
            // The member's own range — not the event's dates — is what the failed join keeps.
            chosen = screen<Layer.JoiningEvent> {
                it.phase.step == JoinPhase.Detailed.Step.CommitFailed && it.range?.from != it.range?.windowStart
            }
        }

        whenever { tap(UiIntent.RetryJoin) }
        then {
            screen<Layer.Joined> { joined -> joined.membership.sharesFrom == chosen.value.range?.from }
        }
    }

    private companion object {
        const val GET_EVENT = "getEvent"
        const val JOIN = "joinEvent"

        /** A share range starting later than the event: a choice the default would not make. */
        const val CUSTOM_FROM = "2026-05-20T00:00:00Z"
    }
}
