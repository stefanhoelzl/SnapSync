@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.assertTrue

/** What reports the platform holds for the process when a clause starts. */
enum class ProcessMetricsState {
    /** A process with no report provider (Android, the upload extension, every JVM composition): nothing is held. */
    NO_PROVIDER,

    /**
     * A process whose provider holds a report for it, handed to whoever listens — MetricKit on a device, about daily.
     * Recorded: a binding cannot make a report arrive, so the device run waits for one.
     */
    PROVIDER_DELIVERS,
}

/**
 * What the process's metric reports promise (`docs/architecture.md`): listening is safe at any time, a process
 * with no provider never reports — its handler is never called with an invented report — and a provider's report
 * reaches the handler, its fields read. A provider's delivery is the platform's to time (MetricKit, about daily), so it
 * is recorded on a device.
 */
object ProcessMetricsContract : Contract<ProcessMetricsState, ProcessMetrics>("ProcessMetrics") {

    override val clauses = clauses {

        clause(
            "NO_PROVIDER_LISTENING_REPORTS_NOTHING",
            ProcessMetricsState.NO_PROVIDER,
            covers = cells { on<ProcessMetrics>().answers(ProcessMetrics::listen).returns() },
        ) { metrics ->
            val reports = mutableListOf<ProcessMetricReport>()
            metrics.listen(MetricHandlers(onReport = { reports += it }))
            settle()
            assertTrue(reports.isEmpty(), "a process with no provider is handed no report")
        }

        clause(
            "A_PROVIDED_REPORT_REACHES_THE_HANDLER",
            ProcessMetricsState.PROVIDER_DELIVERS,
            covers = cells {
                on<ProcessMetrics> {
                    answers(ProcessMetrics::listen).returns()
                    calls(MetricHandlers::onReport, ProcessMetricReport::class)
                }
            },
        ) { metrics ->
            val reports = AtomicReference<List<ProcessMetricReport>>(emptyList())
            metrics.listen(MetricHandlers(onReport = { report -> reports.update { it + report } }))
            awaitWithin { reports.load().isNotEmpty() }
            assertTrue(
                reports.load().all { it.fields.isNotEmpty() },
                "every report the provider hands over arrives with its fields read",
            )
        }
    }
}
