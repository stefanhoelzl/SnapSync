package app.snapsync.contracts

import kotlinx.coroutines.test.TestScope

/**
 * One obligation of a port, conditioned on the state of the system behind it.
 *
 * [id] is stable: recordings are keyed by it, and every address a clause touches is derived from it, so
 * a replay sees the same calls a device made. [body] asserts with ordinary `kotlin.test` assertions and
 * runs inside its own `runTest`, so it may suspend, collect, and use `backgroundScope`/`runCurrent`.
 *
 * A body has no way to decline: whether the clause runs at all is decided by the binding's [Binding.create]
 * BEFORE the body executes. That is deliberate — a skip operation inside a body is how an earlier attempt
 * came to report an unexercised clause as `Passed`.
 */
class Clause<K : Enum<K>, T>(
    val id: String,
    val state: K,
    val body: suspend TestScope.(subject: T) -> Unit,
)

/**
 * A port's contract: an explicit list of [Clause]s (capability `port-contracts`). The list IS the
 * specification of the port's obligations — no spec restates it — and the same list feeds every runner.
 *
 * [K] is the port's hand-written state vocabulary, which lives beside the contract and never in
 * production code.
 */
abstract class Contract<K : Enum<K>, T>(val name: String) {

    abstract val clauses: List<Clause<K, T>>

    protected fun clause(id: String, state: K, body: suspend TestScope.(subject: T) -> Unit) =
        Clause(id, state, body)
}

/**
 * One implementation on one host (capability `port-contracts`, "Clauses are conditioned on states that
 * bindings enter at construction").
 *
 * [reaches] MUST be written as a literal `setOf(...)` of state constants: the contract-coverage gate reads
 * it from source, and fails on any other form rather than skipping it. The runner checks [create] agrees
 * with it on every run.
 */
interface Binding<K : Enum<K>, T> {
    val host: Host
    val kind: BindingKind
    val reaches: Set<K>

    /**
     * A FRESH [T] already in [state], or [Entered.Unreachable] naming why this host cannot produce it.
     * [clauseId] is the clause about to run: addresses and seeded values derive from it, and a recording
     * binding opens that clause's block with it.
     */
    fun create(state: K, clauseId: String): Entered<T>
}

sealed interface Entered<out T> {
    /** [dispose] runs after the clause, pass or fail. */
    class Ready<T>(val subject: T, val dispose: () -> Unit = {}) : Entered<T>

    class Unreachable(val reason: String) : Entered<Nothing>
}
