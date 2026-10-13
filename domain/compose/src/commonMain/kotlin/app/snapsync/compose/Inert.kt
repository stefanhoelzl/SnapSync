package app.snapsync.compose

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics

// The inert bindings a composition states when the platform offers nothing to bind. They are compositions' choices,
// not ports' content (`ports/` holds interfaces only), so they live with the compositions that choose them.

/** Delivers nothing, ever: the process-metrics binding for a process with no provider. */
object NoProcessMetrics : ProcessMetrics {
    @CompositionOnly
    override fun inbound(handlers: MetricHandlers) = Unit
}
