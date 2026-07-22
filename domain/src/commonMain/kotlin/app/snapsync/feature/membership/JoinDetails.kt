package app.snapsync.feature.membership

import app.snapsync.model.JoinLoad
import app.snapsync.ports.EventDetails

/**
 * Adapt the `EventDirectory` port's [EventDetails] to the join gate's [JoinLoad]. Lives here — not in
 * the untested app shell — because mapping a sealed outcome is a decision, and the shell holds none
 * (spec `module-architecture`, "Shells are wiring only"); seated in `feature/membership` (migration
 * step 9) because the details fetch belongs to the join use-case, and the presentation gate forbids
 * `:ui:presentation` naming the `ports/` outcome this maps from. The shell's `loadJoinDetails` lambda
 * is a fetch composed with this mapping.
 */
fun EventDetails.toJoinLoad(): JoinLoad = when (this) {
    is EventDetails.Found -> JoinLoad.Found(name, startsAt, endsAt)
    EventDetails.NotFound -> JoinLoad.NotFound
    EventDetails.Failed -> JoinLoad.Failed
}
