package app.snapsync.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * An operator's hold: while held, every [await] waits; one [release] answers every caller it held. **Holding again
 * while held changes nothing** — a second gate would strand the callers already waiting on the first, since a release
 * answers only the gate it finds. The operator holds and releases from its own thread while the app awaits on another,
 * so the gate is one cell, swapped atomically.
 */
internal class OperatorHold {
    private val gate = MutableStateFlow<CompletableDeferred<Unit>?>(null)

    val held: Boolean get() = gate.value != null

    fun hold() {
        gate.compareAndSet(null, CompletableDeferred())
    }

    fun release() {
        gate.getAndUpdate { null }?.complete(Unit)
    }

    suspend fun await() {
        gate.value?.await()
    }
}
