package app.snapsync.presentation

import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.offersMenu

/** The member's own rename, answered: the event is over. */
private val EVENT_OVER = RenameState.Failed(ScreenMessage.EVENT_OVER)

object OverlayMask {
    /**
     * The overlays that can actually be shown over [layer].
     *
     * The leave confirmation and the rename sheet both belong to a membership — there is nothing to leave or
     * rename without one — so on any other layer they are not shown, whatever the cell holds. The invite's QR
     * code and the rename sheet belong to an event that still exists: a deleted one offers neither. A QR code already
     * shown stays while the key cannot be read: it was captured whole, and an event's key never changes. The app menu
     * is
     * not shown where the layer does not offer it ([offersMenu]): a create or a join committing under an open
     * drawer closes it. The diagnostic sheet and the report's notice are reachable from every layer and never masked.
     *
     * This is a display rule, not a reset: the cell is cleared where the membership actually ends (the leave
     * and the switch), and this makes a flag that outlived its layer by any other route unrenderable rather
     * than merely unlikely.
     */
    internal fun Overlays.maskedFor(layer: Layer): Overlays {
        val membership = when {
            layer !is Layer.Joined -> copy(confirmingLeave = false, renaming = false, shownQr = null)
            layer.deleted -> copy(renaming = renaming && saysEventOver(layer), shownQr = null)
            else -> this
        }
        return if (layer.offersMenu) membership else membership.copy(menuOpen = false)
    }

    /**
     * A deleted event offers no rename: its sheet closes — unless it is saying why the member's own rename did not
     * land,
     * that the event is over.
     */
    private fun saysEventOver(layer: Layer.Joined): Boolean = layer.renameState == EVENT_OVER
}
