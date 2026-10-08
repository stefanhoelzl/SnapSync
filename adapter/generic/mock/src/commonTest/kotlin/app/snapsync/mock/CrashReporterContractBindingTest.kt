package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.CrashObservation
import app.snapsync.contracts.CrashReporterContract
import app.snapsync.contracts.CrashReporterState
import app.snapsync.contracts.CrashReporterSubject
import app.snapsync.contracts.DeliveredEvent
import app.snapsync.contracts.Entered
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The honest [app.snapsync.ports.CrashReporter], held to the contract the real reporting adapter satisfies — up to
 * `STARTED`. It observes through the same cells the world reads (`diagnosticsStarted`, `diagnosticsSent`), which is
 * exactly what the contract licenses it for.
 */
class CrashReporterContractBindingTest {

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(CrashReporterState.NOT_STARTED, CrashReporterState.STARTED)

        override fun create(state: CrashReporterState, clauseId: String, log: CallLog): Entered<CrashReporterSubject> {
            if (state == CrashReporterState.ON_THE_WIRE || state == CrashReporterState.ACROSS_A_RESTART) {
                return Entered.Unreachable(
                    "the fake transmits nothing: what leaves a device is the SDK's serialization and the adapter's hooks",
                )
            }
            val started = MutableStateFlow(false)
            val dumps = MutableStateFlow<List<CrashEvent>>(emptyList())
            val observe = object : CrashObservation {
                override fun channelRunning() = started.value

                // The fake delivers synchronously: whatever will ever be delivered already has been.
                override fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent> {
                    val events = dumps.value.map { dump ->
                        DeliveredEvent(
                            message = dump.message,
                            breadcrumbs = emptyList(),
                            installId = null,
                            tags = dump.tags,
                            contexts = dump.contexts,
                            hasException = false,
                        )
                    }
                    if (!until(events)) throw WaitExpired(0)
                    return events
                }
            }
            return Entered.Ready(
                CrashReporterSubject(
                    inMemoryCrashReporter(started, dumps).recorded(log),
                    CrashOptions("in-memory"),
                    observe,
                ),
            )
        }
    }

    @Test
    fun `the in-memory reporter satisfies the CrashReporter contract`() =
        verify(CrashReporterContract, binding)
}
