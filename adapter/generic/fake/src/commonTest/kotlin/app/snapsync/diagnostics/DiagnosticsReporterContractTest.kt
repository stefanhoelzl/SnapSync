package app.snapsync.diagnostics

import app.snapsync.fake.inMemoryDiagnosticsReporter
import app.snapsync.model.ProcessMetricReport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The `DiagnosticsReporter` contract, exercised over the honest fake (capability `crash-reporting`).
 *
 * This is the shape the module laws want for wiring-adjacent behaviour: the wiring graph itself is
 * "smoke-tested end to end by the world harness and integration tests over fake ports" and never
 * unit-tested — so the guarantee is stated on the PORT and asserted through a fake, rather than by
 * checking that one particular caller happens to call things in the right order.
 *
 * Why the guarantee exists: the process-metric subscriber is armed at process start, so a cold
 * background wake is covered, while the composition starts the reporter only when its deferred graph
 * is forced. That path can therefore reach the channel first — and did. The first real attribution
 * event (`SNAPSYNC-38`) went out carrying **no `process` tag**, because the tag is set by `start()`
 * and `start()` had not run.
 */
class DiagnosticsReporterContractTest {

    @Test
    fun `describing the process starts the channel - whoever gets there first`() {
        val started = MutableStateFlow(false)
        val reporter = inMemoryDiagnosticsReporter(
            started = started,
            sent = MutableStateFlow(emptyList()),
        )
        assertFalse(started.value, "precondition: nothing has started the channel yet")

        reporter.describeProcess(ProcessMetricReport(mapOf("appVersion" to "0.4")))

        assertTrue(
            started.value,
            "describeProcess must start the channel itself — a caller that outran the composition " +
                "would otherwise reach the scope before the process tag is on it",
        )
    }

    @Test
    fun `an unconfigured build still treats describing as inert`() {
        // The no-op rule survives the guarantee above: a build with no reporting configuration records
        // nothing and transmits nothing, even though start() is now reached on this path.
        val described = MutableStateFlow<ProcessMetricReport?>(null)
        val reporter = inMemoryDiagnosticsReporter(
            started = MutableStateFlow(false),
            sent = MutableStateFlow(emptyList()),
            isConfigured = false,
            described = described,
        )
        reporter.describeProcess(ProcessMetricReport(mapOf("appVersion" to "0.4")))
        assertNull(described.value, "an unconfigured build records no report")
    }
}
