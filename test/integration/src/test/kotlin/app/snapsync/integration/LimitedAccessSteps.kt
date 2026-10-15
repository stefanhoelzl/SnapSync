package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer

// The shared named steps of a member under limited photo access (`Steps.kt` holds the rest): a partial grant, and the
// selection the person picks.

/** A member who granted a partial selection, and joined; nothing selected is read yet. */
suspend fun Given.limitedMember() {
    step("a partial grant", act = { library.permission(PhotoGrant.LIMITED) }, until = {})
    createdEvent()
    step("joined", act = { confirmJoin() }, until = { screen<Layer.Joined> { it.canChoosePhotos } })
}

/** The person's selection is exactly [ids], read by the app — which hands each one to an upload. */
suspend fun Given.selected(vararg ids: String) = step(
    "selected ${ids.joinToString()}",
    act = { library.changeSelection(ids.toList()) },
    until = {
        ids.forEach { id -> event<WorldEvent.SessionTransferCreated> { it.key == primaryKey(id) } }
        settled { true }
    },
)
