package app.snapsync.contracts

import app.snapsync.model.CycleResult
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import kotlin.test.assertEquals

/** Whether the upload extension can be invoked. */
enum class ExtensionHostState {
    /** An extension host the binding can invoke and end through the platform's own entries ([ExtensionHostUnderTest]). */
    INVOKABLE,
}

/** The port, and the operating system's invocation and its end, as the binding plays them. */
class ExtensionHostUnderTest(
    val host: ExtensionHost,
    /** One invocation; answers the platform's result for the cycle the handler reported. */
    val invoke: () -> Int,
    /** The end of an invocation. */
    val end: () -> Unit,
    /** The platform's result for [CycleResult], as the host answers it. */
    val resultOf: (CycleResult) -> Int,
)

/**
 * What the extension's entry promises (`docs/architecture.md`, "entry ports"): an invocation runs the handler's cycle
 * and answers the platform what it reported, and the end of an invocation reaches the handler. What a cycle does is the
 * composition's.
 */
object ExtensionHostContract : Contract<ExtensionHostState, ExtensionHostUnderTest>("ExtensionHost") {

    override val clauses = clauses {

        clause(
            "INVOKABLE_AN_INVOCATION_RUNS_THE_CYCLE_AND_ITS_END_ARRIVES",
            ExtensionHostState.INVOKABLE,
            covers = cells {
                on<ExtensionHost> {
                    answers(ExtensionHost::listen).returns()
                    calls(ExtensionHandlers::onProcess)
                    calls(ExtensionHandlers::onTerminate)
                }
            },
        ) { subject ->
            var processed = 0
            var ended = 0
            subject.host.listen(
                ExtensionHandlers(
                    onProcess = {
                        processed++
                        CycleResult.PROCESSING
                    },
                    onTerminate = { ended++ },
                ),
            )
            assertEquals(subject.resultOf(CycleResult.PROCESSING), subject.invoke(), "the cycle's report is the answer")
            assertEquals(1, processed, "one invocation, one cycle")
            subject.end()
            assertEquals(1, ended, "the end of the invocation reaches the handler")
        }
    }
}
