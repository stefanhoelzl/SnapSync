package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeliveredEvent
import app.snapsync.contracts.DiagnosticsObservation
import app.snapsync.contracts.DiagnosticsReporterContract
import app.snapsync.contracts.DiagnosticsReporterState
import app.snapsync.contracts.DiagnosticsReporterSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.DiagnosticDump
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The honest [app.snapsync.ports.DiagnosticsReporter], held to the contract the real reporting adapter satisfies —
 * up to `CONFIGURED`. It observes through the same cells the world reads (`diagnosticsStarted`,
 * `diagnosticsSent`), which is exactly what the contract licenses it for.
 */
class DiagnosticsReporterContractBindingTest {

    private val binding = object : Binding<DiagnosticsReporterState, DiagnosticsReporterSubject> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DiagnosticsReporterState.UNCONFIGURED, DiagnosticsReporterState.CONFIGURED)

        override fun create(state: DiagnosticsReporterState, clauseId: String): Entered<DiagnosticsReporterSubject> {
            if (state == DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) {
                return Entered.Unreachable(
                    "the fake transmits nothing: what leaves a device is the SDK's serialization and the adapter's scrub",
                )
            }
            val started = MutableStateFlow(false)
            val sent = MutableStateFlow<List<DiagnosticDump>>(emptyList())
            val reporter = inMemoryDiagnosticsReporter(
                started = started,
                sent = sent,
                isConfigured = state == DiagnosticsReporterState.CONFIGURED,
            )
            val observe = object : DiagnosticsObservation {
                override fun channelRunning() = started.value

                // The fake delivers synchronously: whatever will ever be delivered already has been.
                override fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent> {
                    val events = sent.value.map { dump ->
                        DeliveredEvent(
                            message = dump.note,
                            breadcrumbs = emptyList(),
                            installId = null,
                            processAccount = null,
                            isDump = true,
                        )
                    }
                    if (!until(events)) throw WaitExpired(0)
                    return events
                }
            }
            return Entered.Ready(DiagnosticsReporterSubject(reporter, observe))
        }
    }

    @Test
    fun `the in-memory reporter satisfies the DiagnosticsReporter contract`() =
        verify(DiagnosticsReporterContract, binding)
}
