package app.snapsync.scheduling

/**
 * Work several triggers may ask for at once, run **single-flight** by [key] (`docs/patterns.md`, "Sequences"): at
 * most one [pass] runs per key in the process. A request while one runs **joins**: the sequence makes exactly one more
 * pass once the running one ends — however many joined — and every joiner gets that pass's result. A sequence orders
 * work; what each step does is the code it calls.
 *
 * [yieldsToJoin]: a join cancels the running pass and the next one starts at once, answering the running pass's
 * waiters too. For a sequence whose pass can wait on something that may never report — a photo-library import — so
 * one stalled wait never holds back the work a new request brings. Its steps must make that safe: what a cancelled
 * pass leaves is what the next pass picks up.
 */
abstract class Sequence<R>(val key: String, val yieldsToJoin: Boolean = false) {
    abstract suspend fun pass(): R
}

/** What a waiter of a [Sequence] gets: the result of the pass that covered its request, or [Cut]. */
sealed interface Pass<out R> {
    /** The pass ran to its end. */
    class Completed<R>(val result: R) : Pass<R>

    /** The pass ended without a result — the process is going away, or the waiter's own time was up first. */
    data object Cut : Pass<Nothing>
}
