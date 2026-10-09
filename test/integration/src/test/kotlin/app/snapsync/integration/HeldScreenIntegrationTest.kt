package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The screens the app shows only while it waits (`docs/testing.md`, "Every reviewed state is reachable"): each is
 * reached by holding what it waits on, and each moves on when that is released. The mocks answer at once, so without
 * a hold these screens exist for a moment no reviewer — and no screenshot — can catch.
 */
class HeldScreenIntegrationTest {

    @Test
    @Verifies(spec = "join-event", requirement = "The join screen verifies the event before offering to join")
    fun a_held_details_load_keeps_the_join_gate_loading_until_released() = rigTest {
        val event = registerEvent()
        hold("event")

        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.Loading }
        neverWithin(what = "the gate left Loading while the details were held") {
            (it.ui.layer as? Layer.JoiningEvent)?.phase != JoinPhase.Loading
        }

        release("event")
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
    }

    @Test
    @Verifies(spec = "join-event", requirement = "Joining happens only on confirmation and needs a connection")
    fun a_held_enrolment_keeps_the_join_gate_committing_until_released() = rigTest {
        val event = registerEvent()
        openLink(inviteLink(event))
        awaitState { it.step() == JoinPhase.Detailed.Step.Ready }
        hold("join")

        user("confirmJoin")
        awaitState { it.step() == JoinPhase.Detailed.Step.Committing }
        neverWithin(what = "the join committed while the enrolment was held") { it.ready.configResolved }

        release("join")
        assertEquals(event, awaitState { it.ready.configResolved }.ready.eventId)
    }

    @Test
    @Verifies(spec = "create-event", requirement = "The creator joins through the same join screen as every guest")
    fun a_held_create_keeps_the_create_in_flight_until_released() = rigTest {
        hold("create")

        user("create", "name" to Rig.EVENT_NAME, "startsAt" to Rig.WINDOW_START, "endsAt" to Rig.WINDOW_END)
        awaitState { it.ui.layer == Layer.CreatingEvent }
        neverWithin(what = "the create resolved while it was held") { it.ui.layer != Layer.CreatingEvent }

        release("create")
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "\"Up to date\" is never claimed before the app has looked")
    fun a_held_enumeration_keeps_the_joined_screen_loading_until_released() = rigTest {
        device("gallery/hold-enumeration", "on" to "true")

        createAndJoin()
        awaitHealth { it == SyncHealth.Loading }
        foreground(awaited = false)
        neverWithin(what = "the status counted a library whose walk was held") { it.health != SyncHealth.Loading }

        device("gallery/hold-enumeration", "on" to "false")
        foreground()
        awaitInSync()
    }

    private suspend fun Rig.hold(call: String) {
        device("backend/hold", "call" to call, "on" to "true")
    }

    private suspend fun Rig.release(call: String) {
        device("backend/hold", "call" to call, "on" to "false")
    }

    private fun app.snapsync.rig.RigState.step(): JoinPhase.Detailed.Step? =
        ((ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.step
}
