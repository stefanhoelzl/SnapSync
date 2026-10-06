package app.snapsync.ui

import app.snapsync.model.JoinPhase
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.event_closed_body
import app.snapsync.ui.resources.event_closed_title
import app.snapsync.ui.resources.event_not_found_body
import app.snapsync.ui.resources.event_not_found_title
import app.snapsync.ui.resources.link_incomplete_body
import app.snapsync.ui.resources.link_incomplete_title
import org.jetbrains.compose.resources.StringResource

/** What a wall says: a phase offering only Cancel, because no retry could move it. */
internal class WallCopy(val title: StringResource, val body: StringResource)

/**
 * The copy of the three walls (capability `join-event`): an invite to no event, a closed event, and an incomplete
 * invite of an encrypted one — the last opened only by the whole invite, which no retry of this one can be.
 */
internal fun wallCopy(phase: JoinPhase): WallCopy = when (phase) {
    JoinPhase.Closed -> WallCopy(Res.string.event_closed_title, Res.string.event_closed_body)
    JoinPhase.WrongLink -> WallCopy(Res.string.link_incomplete_title, Res.string.link_incomplete_body)
    else -> WallCopy(Res.string.event_not_found_title, Res.string.event_not_found_body)
}
