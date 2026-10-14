package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ProcessMetricsContract
import app.snapsync.contracts.ProcessMetricsState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.inboundOf
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.MetricReportKind
import app.snapsync.model.processMetricEmissions
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.ProcessMetrics
import app.snapsync.services.crash.report
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The platform's process-metric reports as a mock — held to the contract MetricKit's adapter passes — and the reports
 * its operator plays: each crosses on exactly what its kind says, under the rule the app reports by.
 */
@OptIn(CompositionOnly::class)
class ProcessMetricsMockTest {

    private val binding = object : Binding<ProcessMetricsState, ProcessMetrics> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = ProcessMetricsMock.Face::class
        override val reaches = setOf(ProcessMetricsState.NO_PROVIDER, ProcessMetricsState.PROVIDER_DELIVERS)

        override fun create(state: ProcessMetricsState, clauseId: String, log: CallLog): Entered<ProcessMetrics> {
            val mock = ProcessMetricsMock()
            val port = mock.port()
            // A provider holds a report for the process before it listens, as MetricKit holds a day's.
            if (state == ProcessMetricsState.PROVIDER_DELIVERS) {
                mock.hand(metricPayloadOf(metricReport(MetricReportKind.QUIET)))
            }
            return Entered.Ready(port.recorded(log))
        }
    }

    @Test
    fun `the process-metrics mock satisfies the ProcessMetrics contract`() = verify(ProcessMetricsContract, binding)

    @Test
    fun `each played report crosses on exactly what its kind says`() {
        assertEquals(emptyList(), reasons(MetricReportKind.QUIET))
        assertEquals(
            listOf("foregroundExitData.cumulativeMemoryResourceLimitExitCount"),
            reasons(MetricReportKind.ENDED_ABNORMALLY),
        )
        assertEquals(listOf("appHangAtOrAbove1000ms"), reasons(MetricReportKind.FROZE))
    }

    @Test
    fun `a report is handed over once, to the process listening or the next one that listens`() = runTest {
        val mock = ProcessMetricsMock()
        val first = mock.port()
        mock.operator.deliver(MetricReportKind.FROZE)
        val heard = mutableListOf<ProcessMetricPayload>()
        first.inbound(MetricHandlers(onReport = inboundOf { heard += it }))
        assertEquals(1, heard.size, "held until the process listened")

        mock.operator.deliver(MetricReportKind.QUIET)
        assertEquals(2, heard.size, "handed at once to a process listening")

        val relaunched = mutableListOf<ProcessMetricPayload>()
        mock.port().inbound(MetricHandlers(onReport = inboundOf { relaunched += it }))
        assertEquals(emptyList(), relaunched, "a report handed over is gone")
    }

    private fun reasons(kind: MetricReportKind): List<String> =
        processMetricEmissions(metricPayloadOf(metricReport(kind)).report()).mapNotNull { it.reason }
}
