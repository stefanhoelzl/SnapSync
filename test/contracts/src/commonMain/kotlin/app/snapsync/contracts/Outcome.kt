package app.snapsync.contracts

/**
 * How one clause ended against one binding (`docs/architecture.md`, "Outcomes are explicit and none
 * is silent"). Every clause ends in exactly one of these.
 */
sealed interface Outcome {
    /** The clause held. */
    data object Passed : Outcome

    /** The implementation — or a recorded operating-system answer — violates the clause. Fix code or clause. */
    data class Failed(val message: String) : Outcome

    /** This binding cannot reach the clause's state. Admissible only if another real host covers it. */
    data class NotRunHere(val reason: String) : Outcome

    /** Replay: the adapter made an operating-system call the recording does not hold. Re-record. */
    data class Diverged(val message: String) : Outcome

    /** A bounded wait on an operating-system callback expired. */
    data class NotWithin(val millis: Long) : Outcome
}

class ClauseResult(val clauseId: String, val outcome: Outcome)

/**
 * Thrown by a replaying seam when the adapter's call is not the next recorded one. An [Error], not an
 * [Exception], so adapter code that catches exceptions to classify a platform failure cannot swallow it.
 */
class Divergence(message: String) : Error(message)

/** Thrown by a bounded wait that expired. */
class WaitExpired(val millis: Long) : Error("no callback within ${millis}ms")
