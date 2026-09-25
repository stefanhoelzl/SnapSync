package app.snapsync.feature.membership

import app.snapsync.model.JoinLoad
import app.snapsync.model.EventLookup

/**
 * Adapt the `EventDirectory` port's [EventLookup] to the join gate's [JoinLoad]. Lives here — not in
 * the untested app shell — because mapping a sealed outcome is a decision, and the shell holds none
 * (`docs/architecture.md`, "Shells are wiring only"); seated in `feature/membership` (migration
 * step 9) because the details fetch belongs to the join use-case, and the presentation gate forbids
 * `:domain:presentation` naming the `ports/` outcome this maps from. The shell's `loadJoinDetails` lambda
 * is a fetch composed with this mapping.
 */
fun EventLookup.toJoinLoad(): JoinLoad = when (this) {
    is EventLookup.Found -> JoinLoad.Found(name, startsAt, endsAt, deletesAt)
    EventLookup.NotFound -> JoinLoad.NotFound
    EventLookup.Failed -> JoinLoad.Failed
}
