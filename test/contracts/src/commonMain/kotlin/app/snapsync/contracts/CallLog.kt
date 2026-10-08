package app.snapsync.contracts

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The port-grid cells that actually occurred while one clause ran (`docs/testing.md`, "A declared cell must occur").
 *
 * The runner makes one per clause and hands it to [Binding.create]; the binding wraps its adapter in the port's
 * recording proxy ([app.snapsync.contracts.proxy]) over it, and every answer, handler call and callback the proxy sees
 * is recorded here as the grid's own text. After the clause the runner compares the clause's declared cells with
 * [cells].
 *
 * Only the clause's WINDOW counts: from just before its body to the end of the binding's `dispose`. What the binding
 * does in `create` — seeding writes, `listen` — is entering the state, not exercising the port, and must not satisfy a
 * claim; a handler the adapter calls late, during the body or the disposal, still counts. Anything after the window is
 * dropped, so a stale adapter calling back into a finished clause's proxy changes nothing.
 *
 * Atomic, because an adapter answers from its own threads and dispatchers while the clause runs.
 */
@OptIn(ExperimentalAtomicApi::class)
class CallLog {
    private val entries = AtomicReference<List<String>>(emptyList())
    private val window = AtomicInt(BEFORE)

    /** Records [cell] if the clause's window is open; a no-op before it opens and after it closes. */
    fun record(cell: String) {
        if (window.load() != OPEN) return
        while (true) {
            val now = entries.load()
            if (entries.compareAndSet(now, now + cell)) return
        }
    }

    /** Every cell recorded inside the window. */
    val cells: Set<String> get() = entries.load().toSet()

    /** Opens the window — the runner's act, just before a clause's body; public for the gates that drive proxies alone. */
    fun open() = window.store(OPEN)

    /** Closes the window — the runner's act, once the binding's `dispose` returned. */
    fun close() = window.store(CLOSED)

    private companion object {
        const val BEFORE = 0
        const val OPEN = 1
        const val CLOSED = 2
    }
}
