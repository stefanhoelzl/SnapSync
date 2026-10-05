package app.snapsync.presentation

import app.snapsync.model.Layer
import app.snapsync.model.Overlays
import app.snapsync.model.offersMenu

/**
 * The overlays that can actually be shown over [layer].
 *
 * The leave confirmation and the rename sheet both belong to a membership — there is nothing to leave or
 * rename without one — so on any other layer they are not shown, whatever the cell holds. The app menu is
 * not shown where the layer does not offer it ([offersMenu]): a create or a join committing under an open
 * drawer closes it. The diagnostic sheet and the report's notice are reachable from every layer and never masked.
 *
 * This is a display rule, not a reset: the cell is cleared where the membership actually ends (the leave
 * and the switch), and this makes a flag that outlived its layer by any other route unrenderable rather
 * than merely unlikely.
 */
internal fun Overlays.maskedFor(layer: Layer): Overlays {
    val membership = if (layer is Layer.Joined) this else copy(confirmingLeave = false, renaming = false)
    return if (layer.offersMenu) membership else membership.copy(menuOpen = false)
}
