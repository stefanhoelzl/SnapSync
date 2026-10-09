package app.snapsync.contracts

import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.ProcessingAnswer
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
    /** The platform's result for [ProcessingAnswer], as the host answers it. */
    val resultOf: (ProcessingAnswer) -> Int,
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
            var answer = ProcessingAnswer.PROCESSING
            subject.host.listen(
                ExtensionHandlers(
                    onProcess = {
                        processed++
                        answer
                    },
                    onTerminate = { ended++ },
                ),
            )
            // Every answer reaches the platform as its own result — the adapter passes it through, deciding nothing.
            ProcessingAnswer.entries.forEachIndexed { i, each ->
                answer = each
                assertEquals(subject.resultOf(each), subject.invoke(), "the handler's answer $each is the platform's")
                assertEquals(i + 1, processed, "one invocation, one handler call")
            }
            val results = ProcessingAnswer.entries.map(subject.resultOf).toSet()
            assertEquals(ProcessingAnswer.entries.size, results.size, "each answer is a distinct result")
            subject.end()
            assertEquals(1, ended, "the end of the invocation reaches the handler")
        }
    }
}
