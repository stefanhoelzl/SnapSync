package app.snapsync.scheduling

import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * State whose changes start work (`docs/patterns.md`, "Observation"): a grant that changed, a selection the member
 * edited, a network that came back. Each emission is handed to an entry point, as the platform hands a delivery — so
 * it is labelled, contained and timed like one — and the next emission waits for that run to end.
 */
class Observation(private val runtime: Runtime) {
    /** Follows [flow] for the life of the process, handing each emission to [entry]. */
    @CompositionOnly
    fun <T> follow(flow: Flow<T>, entry: Background<T>) {
        runtime.scope.launch {
            flow.collect { value ->
                val done = Done()
                entry.handle(value, done)
                done.ended.await()
            }
        }
    }

    private class Done : Completion {
        val ended = CompletableDeferred<Unit>()

        override fun complete() {
            ended.complete(Unit)
        }
    }
}
