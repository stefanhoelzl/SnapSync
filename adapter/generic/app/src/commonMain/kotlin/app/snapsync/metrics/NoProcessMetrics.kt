package app.snapsync.metrics

import app.snapsync.annotations.Never
import app.snapsync.annotations.NeverAnswers
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics

/**
 * Delivers nothing, ever: the process-metrics binding for a process with no provider — Android, the upload extension
 * and the JVM host. Its root chooses it, as it chooses every adapter it hands its composition.
 */
@NeverAnswers(
    Never(handler = "onReport"),
)
object NoProcessMetrics : ProcessMetrics {
    @CompositionOnly
    override fun inbound(handlers: MetricHandlers) = Unit
}
