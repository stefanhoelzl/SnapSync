package app.snapsync.contracts

import kotlinx.coroutines.test.runTest
import kotlin.test.fail

/**
 * Runs every clause of [contract] against [binding] and returns one result per clause, in contract order.
 * Used unchanged on CI (through [verify]) and in-app on a device (through the rig).
 */
fun <K : Enum<K>, T> run(contract: Contract<K, T>, binding: Binding<K, T>): List<ClauseResult> =
    contract.clauses.map { clause -> ClauseResult(clause.id, runOne(clause, binding)) }

private fun <K : Enum<K>, T> runOne(clause: Clause<K, T>, binding: Binding<K, T>): Outcome {
    val declared = clause.state in binding.reaches
    return when (val entered = binding.create(clause.state, clause.id)) {
        is Entered.Unreachable ->
            if (declared) {
                Outcome.Failed("the binding declares ${clause.state} reachable but answered Unreachable: ${entered.reason}")
            } else {
                Outcome.NotRunHere(entered.reason)
            }
        is Entered.Ready ->
            try {
                if (!declared) {
                    Outcome.Failed("the binding produced ${clause.state}, which it does not declare reachable")
                } else {
                    execute(clause, entered.subject)
                }
            } finally {
                entered.dispose()
            }
    }
}

private fun <K : Enum<K>, T> execute(clause: Clause<K, T>, subject: T): Outcome =
    try {
        runTest { clause.body(this, subject) }
        Outcome.Passed
    } catch (d: Divergence) {
        Outcome.Diverged(d.message ?: "diverged")
    } catch (w: WaitExpired) {
        Outcome.NotWithin(w.millis)
    } catch (t: Throwable) {
        Outcome.Failed("${t::class.simpleName}: ${t.message}")
    }

/** One line per clause, in contract order — the table a failing run reports and a device run returns. */
fun List<ClauseResult>.table(): String = joinToString("\n") { "${it.clauseId} ${it.outcome.render()}" }

private fun Outcome.render(): String = when (this) {
    Outcome.Passed -> "Passed"
    is Outcome.Failed -> "Failed($message)"
    is Outcome.NotRunHere -> "NotRunHere($reason)"
    is Outcome.Diverged -> "Diverged($message)"
    is Outcome.NotWithin -> "NotWithin(${millis}ms)"
}

/**
 * The CI entry point: runs the whole contract and fails ONCE, with the full outcome table, if any clause is
 * [Outcome.Failed] or [Outcome.Diverged]. `NotRunHere` never fails a run by itself — whether it is
 * admissible is the contract-coverage gate's question.
 */
fun <K : Enum<K>, T> verify(contract: Contract<K, T>, binding: Binding<K, T>) {
    val results = run(contract, binding)
    val bad = results.count { it.outcome is Outcome.Failed || it.outcome is Outcome.Diverged }
    if (bad > 0) {
        fail("${contract.name} on ${binding.host} (${binding.kind}): $bad of ${results.size} clauses failed\n${results.table()}")
    }
}
