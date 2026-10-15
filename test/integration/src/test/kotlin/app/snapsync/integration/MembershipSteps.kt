package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent

// The membership's own steps: leaving, and a join gate open on an event.

/** This device leaves its event. */
suspend fun Given.left() = step("left", act = { tap(UiIntent.LeaveEvent) }, until = { settled { true } })

/** The join gate open on [event], through its invite, ready to join. */
suspend fun Given.gateOpenOn(event: String) {
    val link = inviteFor(event)
    step(
        "its join gate open",
        act = { openLink(link) },
        until = { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } },
    )
}
