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
 * The honest [app.snapsync.ports.CrashReporter], held to the contract the real reporting adapter satisfies — through
 * `RECEIVED`: what the world reads of what the reporter's backend received is exactly what those clauses license. It
 * observes through the cells the world reads; dumps and automatic events share one here, so the clauses' sentinels see
 * them in the order they left.
 */
class CrashReporterContractBindingTest {

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryCrashReporter::class
        override val reaches = setOf(
            CrashReporterState.NOT_STARTED,
            CrashReporterState.STARTED,
            CrashReporterState.RECEIVED,
        )

        override fun create(state: CrashReporterState, clauseId: String, log: CallLog): Entered<CrashReporterSubject> {
            if (state == CrashReporterState.ON_THE_WIRE || state == CrashReporterState.ACROSS_A_RESTART ||
                state == CrashReporterState.AFTER_A_PROCESS_DEATH
            ) {
                return Entered.Unreachable(
                    "the fake does not model the SDK: breadcrumbs riding the next event, the install id, the build " +
                        "facts, the size caps, a relaunch's cache and a process's death are its serialization and the adapter's hooks",
                )
            }
            val started = MutableStateFlow(false)
            // One record for dumps and automatic events alike: what left, in the order it left.
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
                            hasException = dump.throwable != null,
                            release = dump.release,
                            dist = dump.dist,
                        )
                    }
                    if (!until(events)) throw WaitExpired(0)
                    return events
                }
            }
            return Entered.Ready(
                CrashReporterSubject(
                    inMemoryCrashReporter(started, dumps, events = dumps).recorded(log),
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
