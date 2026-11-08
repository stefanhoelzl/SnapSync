package app.snapsync.query

import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.inbound.Trigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * **A read the screen asks once, outside any run** (`docs/patterns.md`, "Lookups"): the join form's shareable count, an
 * invite's event. Asked from presentation's own thread, it reads on [lane] — the composition's, never the thread that
 * asked — labelled [name], so its lines say what asked them. A lookup asked inside an entry point's run is not one of
 * these: it is on the lane already, and labelled with the run's trigger.
 */
abstract class ScreenLookup<A, R>(private val name: String, private val lane: CoroutineDispatcher) {
    private val log = Log("ScreenLookup")

    /** The read itself. */
    protected abstract suspend fun read(arg: A): R

    /** What the answered line says of [arg] — an id, never a key. */
    protected open fun about(arg: A): String? = null

    /** [arg]'s answer, read on the lane. */
    suspend fun ask(arg: A): R = withContext(lane + Trigger.of(name)) {
        read(arg).also { log.i("answered", "lookup" of name, "about" of about(arg)) }
    }
}
