package app.snapsync.contracts

import app.snapsync.model.GalleryAccess
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

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
 *
 * [covers] is the port-grid cells the clause exercises, rendered ([cells]) — at least one: a clause that pins no answer
 * of a port is not a port clause (`docs/testing.md`, "Every clause declares the cells it covers").
 */
class Clause<K : Enum<K>, T>(
    val id: String,
    val state: K,
    covers: Covers,
    val body: suspend TestScope.(subject: T) -> Unit,
) {
    val covers: List<String> = covers.cells

    init {
        require(this.covers.isNotEmpty()) { "clause $id declares no cell it covers" }
    }
}

/**
 * A port's contract: an explicit list of [Clause]s (`docs/architecture.md`). The list IS the
 * specification of the port's obligations — no spec restates it — and the same list feeds every runner.
 *
 * [K] is the port's hand-written state vocabulary, which lives beside the contract and never in
 * production code.
 */
abstract class Contract<K : Enum<K>, T>(val name: String) {

    abstract val clauses: List<Clause<K, T>>

    protected fun clause(id: String, state: K, covers: Covers, body: suspend TestScope.(subject: T) -> Unit) =
        Clause(id, state, covers, body)

    /**
     * Builds the clause list as a sequence of `clause(...) { }` statements rather than one comma-separated
     * expression. Duplicate ids are refused here: recordings and addresses are keyed by them.
     */
    protected fun clauses(build: ClauseList<K, T>.() -> Unit): List<Clause<K, T>> =
        ClauseList<K, T>().apply(build).built.also { list ->
            val dup = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dup.isEmpty()) { "$name declares duplicate clause ids: $dup" }
        }
}

class ClauseList<K : Enum<K>, T> internal constructor() {
    internal val built = mutableListOf<Clause<K, T>>()

    fun clause(id: String, state: K, covers: Covers, body: suspend TestScope.(subject: T) -> Unit) {
        built += Clause(id, state, covers, body)
    }
}

/**
 * One implementation on one host (`docs/architecture.md`, "Clauses are conditioned on states that
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
     * The photo grant this binding runs under, or `null` where the port does not depend on one (capability
     * `docs/architecture.md`, "An authorization the process cannot give itself is a precondition of the run").
     *
     * A grant is not host identity, but it does decide which recording a recorded host's run belongs to: one
     * run holds one grant, so a host recorded under two grants keeps two files, named by [recordingName]. Where
     * declared, it MUST be written as `override val grant = GalleryAccess.X` — the contract-coverage gate
     * reads it from source to find the recording a replay binding counts through.
     */
    val grant: GalleryAccess? get() = null

    /**
     * A condition of the device, other than the photo grant, that this binding's run is taken under — one a person
     * sets on the phone and no binding can enter (the network contract's airplane mode), or `null` where there is none.
     *
     * It names the recording exactly as [grant] does, so a host recorded under two conditions keeps two files. Where
     * declared, it MUST be written as `override val precondition = "X"` — the contract-coverage gate reads it from
     * source. A binding declares a grant or a precondition, never both.
     */
    val precondition: String? get() = null

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

/**
 * Runs a binding's entry into — or exit from — a state whose setup SUSPENDS: filling a transfer tier's in-flight cap,
 * cancelling what a clause left open. [Binding.create] and [Entered.Ready.dispose] are not coroutines, and the runner
 * enters the state before the clause's own `runTest`, so the entry gets a `runTest` of its own, on the calling thread,
 * exactly as a clause body runs (`docs/architecture.md`). Not a general-purpose bridge: it exists so a binding
 * never reaches for `runBlocking`, which production lanes may not use.
 */
fun runEntry(block: suspend () -> Unit) {
    runTest { block() }
}

/**
 * The committed recording's name, without `.rec`, for [contract] recorded on [host] under [grant] or [precondition]
 * (`docs/architecture.md`, "A recording is one committed plain-text file per contract and host"):
 * `<Contract>@<HOST>` where neither is declared, `<Contract>@<HOST>.<GRANT>` or `<Contract>@<HOST>.<PRECONDITION>`
 * where one is.
 */
fun recordingName(contract: String, host: Host, grant: GalleryAccess?, precondition: String? = null): String =
    "$contract@${host.name}" + ((grant?.name ?: precondition)?.let { ".$it" } ?: "")
