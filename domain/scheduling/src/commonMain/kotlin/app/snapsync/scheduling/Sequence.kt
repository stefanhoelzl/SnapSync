package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState

/**
 * Work several triggers may ask for at once, run **single-flight** by [key] (`docs/patterns.md`, "Sequences"): at
 * most one pass runs per key in the process. A request while one runs **joins**: the sequence makes exactly one more
 * pass once the running one ends — however many joined — and every joiner gets that pass's result. A sequence orders
 * work; what each step does is the code it calls.
 *
 * [yieldsToJoin]: a join cancels the running pass and the next one starts at once, answering the running pass's
 * waiters too. For a sequence whose pass can wait on something that may never report — a photo-library import — so
 * one stalled wait never holds back the work a new request brings. Its steps must make that safe: what a cancelled
 * pass leaves is what the next pass picks up.
 *
 * A [Sequence] is asked for with nothing; an [AskedSequence] with a value each request names.
 */
sealed class SingleFlighted<R>(val key: String, val yieldsToJoin: Boolean) {
    /** One pass, [on] the [Run] the runtime makes for it, for what the first request it covers [asked]. */
    internal abstract suspend fun passFor(asked: Any?, on: Run): R
}

/** Single-flight work every request asks for alike. */
abstract class Sequence<R>(key: String, yieldsToJoin: Boolean = false) : SingleFlighted<R>(key, yieldsToJoin) {
    /** One pass, [on] the [Run] the runtime makes for it — labelled with every trigger that joined it. */
    abstract suspend fun pass(on: Run): R

    final override suspend fun passFor(asked: Any?, on: Run): R = pass(on)
}

/**
 * Single-flight work each request asks for with a [Q] — why it asks, say, which the pass's work tells someone. A pass
 * is for what the FIRST request it covers asked: a pass a running one yielded to, the yielded pass's first.
 */
abstract class AskedSequence<Q : Any, R>(key: String, yieldsToJoin: Boolean = false) :
    SingleFlighted<R>(key, yieldsToJoin) {
    /** One pass for what the first request it covers [asked], [on] the [Run] the runtime makes for it. */
    abstract suspend fun pass(asked: Q, on: Run): R

    @Suppress("UNCHECKED_CAST") // every ask of this key came through [Steps.run] typed as a [Q]
    final override suspend fun passFor(asked: Any?, on: Run): R {
        val ask = asked ?: throw InvalidProgramState("every request of an asked sequence asks")
        return pass(ask as Q, on)
    }
}

/** What a waiter of a [Sequence] gets: the result of the pass that covered its request, or [Cut]. */
sealed interface Pass<out R> {
    /** The pass ran to its end. */
    class Completed<R>(val result: R) : Pass<R>

    /** The pass ended without a result — the process is going away, or the waiter's own time was up first. */
    data object Cut : Pass<Nothing>
}

/** The pass's result, or [cut] for a pass that ended without one — what a waiter reads a cut pass as. */
fun <R> Pass<R>.orIfCut(cut: R): R = when (this) {
    is Pass.Completed -> result
    Pass.Cut -> cut
}
