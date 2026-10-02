package app.snapsync.compose

import app.snapsync.ports.EntryContext
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics

// The inert bindings a composition states when the platform offers nothing to bind. They are compositions' choices,
// not ports' content (`ports/` holds interfaces only), so they live with the compositions that choose them.

/** No entry-point context: the world, the harnesses and tests — any process without device logging. */
object NoEntryContext : EntryContext {
    override fun enter(name: String): Boolean = false
    override fun exit(owned: Boolean) = Unit
    override fun current(): String? = null
}

/** Delivers nothing, ever: the process-metrics binding for a process with no provider. */
object NoProcessMetrics : ProcessMetrics {
    override fun listen(handlers: MetricHandlers) = Unit
}
