package app.snapsync.mock

import kotlinx.coroutines.CompletableDeferred

/**
 * An operator's hold: while held, every [await] waits; one [release] answers every caller it held. **Holding again
 * while held changes nothing** — a second gate would strand the callers already waiting on the first, since a release
 * answers only the gate it finds.
 */
internal class OperatorHold {
    private var gate: CompletableDeferred<Unit>? = null

    val held: Boolean get() = gate != null

    fun hold() {
        if (gate == null) gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    suspend fun await() {
        gate?.await()
    }
}
