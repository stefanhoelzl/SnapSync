package app.snapsync.presentation

import app.snapsync.model.Layer
import app.snapsync.model.Overlays

/**
 * The overlays that can actually be shown over [layer].
 *
 * The leave confirmation and the rename sheet both belong to a membership — there is nothing to leave or
 * rename without one — so on any other layer they are not shown, whatever the cell holds. The diagnostic
 * sheet is reachable from every layer and is never masked.
 *
 * This is a display rule, not a reset: the cell is cleared where the membership actually ends (the leave
 * and the switch), and this makes a flag that outlived its layer by any other route unrenderable rather
 * than merely unlikely.
 */
internal fun Overlays.maskedFor(layer: Layer): Overlays =
    if (layer is Layer.Joined) this else copy(confirmingLeave = false, renaming = false)
