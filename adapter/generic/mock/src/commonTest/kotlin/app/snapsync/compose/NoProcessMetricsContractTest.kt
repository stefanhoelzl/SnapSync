package app.snapsync.compose

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ProcessMetricsContract
import app.snapsync.contracts.ProcessMetricsState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.ProcessMetrics
import kotlin.test.Test

/**
 * The process metrics Android, the upload extension and the JVM root compose — [NoProcessMetrics], a production
 * implementation, not a double: those processes have no report provider — against the contract.
 */
class NoProcessMetricsContractTest {

    private val binding = object : Binding<ProcessMetricsState, ProcessMetrics> {
        override val host = currentHost
        override val kind = BindingKind.Live
        override val reaches = setOf(ProcessMetricsState.NO_PROVIDER)
        override fun create(state: ProcessMetricsState, clauseId: String): Entered<ProcessMetrics> =
            if (state in reaches) Entered.Ready(NoProcessMetrics) else Entered.Unreachable("no provider holds a report")
    }

    @Test
    fun `the no-provider metrics satisfy the ProcessMetrics contract`() = verify(ProcessMetricsContract, binding)
}
