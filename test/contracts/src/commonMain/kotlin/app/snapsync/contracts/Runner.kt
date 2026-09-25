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
    // Entering a state is part of the clause too: a replaying binding's seeding calls can diverge.
    val entered = try {
        binding.create(clause.state, clause.id)
    } catch (t: Throwable) {
        return classify { throw t }
    }
    return when (entered) {
        is Entered.Unreachable ->
            if (declared) {
                Outcome.Failed("the binding declares ${clause.state} reachable but answered Unreachable: ${entered.reason}")
            } else {
                Outcome.NotRunHere(entered.reason)
            }
        is Entered.Ready -> {
            val outcome = if (declared) {
                execute(clause, entered.subject)
            } else {
                Outcome.Failed("the binding produced ${clause.state}, which it does not declare reachable")
            }
            // Disposal is part of the clause: a replaying binding checks there that every recorded call was
            // made, so a call the adapter silently STOPPED making diverges instead of replaying green.
            val disposal = classify { entered.dispose() }
            if (outcome == Outcome.Passed) disposal else outcome
        }
    }
}

private inline fun classify(block: () -> Unit): Outcome =
    try {
        block()
        Outcome.Passed
    } catch (d: Divergence) {
        Outcome.Diverged(d.message ?: "diverged")
    } catch (w: WaitExpired) {
        Outcome.NotWithin(w.millis)
    } catch (t: Throwable) {
        Outcome.Failed("${t::class.simpleName}: ${t.message}")
    }

private fun <K : Enum<K>, T> execute(clause: Clause<K, T>, subject: T): Outcome =
    classify { runTest { clause.body(this, subject) } }

/** One line per clause, in contract order — the table a failing run reports and a device run returns. */
fun List<ClauseResult>.table(): String = joinToString("\n") { "${it.clauseId} ${it.outcome.render()}" }

/** One outcome as the outcome table and a recording's header spell it. */
fun Outcome.render(): String = when (this) {
    Outcome.Passed -> "Passed"
    is Outcome.Failed -> "Failed($message)"
    is Outcome.NotRunHere -> "NotRunHere($reason)"
    is Outcome.Diverged -> "Diverged($message)"
    is Outcome.NotWithin -> "NotWithin(${millis}ms)"
}

/**
 * The prefix a contract's answer carries when the process running it is NOT the host that contract records
 * for — a simulator app asked for a device recording, say. A channel serving contracts answers such a body
 * with a refusal status, so it cannot be redirected into a recording file unnoticed.
 */
const val CONTRACT_REFUSED: String = "refused: "

/**
 * The prefix a contract's answer carries when a run another process performs — the upload extension's, requested
 * through the App Group — produced no result within its bound. A channel answers it with a timeout status and no
 * recording, never a partial one.
 */
const val CONTRACT_TIMEOUT: String = "timeout: "

/**
 * The CI entry point: runs the whole contract and fails ONCE, with the full outcome table, if any clause is
 * [Outcome.Failed], [Outcome.Diverged] or [Outcome.NotWithin], whatever the binding's kind (capability
 * `docs/architecture.md`, "Outcomes are explicit and none is silent"). An expired wait established nothing: on a
 * `Live` binding the clause ran nothing, on a `Replay` a recorded answer was never delivered, and on a `Fake`
 * the double did not deliver what the clause requires. `NotRunHere` never fails a run by itself — whether it
 * is admissible is the contract-coverage gate's question.
 */
fun <K : Enum<K>, T> verify(contract: Contract<K, T>, binding: Binding<K, T>) {
    val results = run(contract, binding)
    val bad = results.count { it.outcome is Outcome.Failed || it.outcome is Outcome.Diverged || it.outcome is Outcome.NotWithin }
    if (bad > 0) {
        fail("${contract.name} on ${binding.host} (${binding.kind}): $bad of ${results.size} clauses failed\n${results.table()}")
    }
}
