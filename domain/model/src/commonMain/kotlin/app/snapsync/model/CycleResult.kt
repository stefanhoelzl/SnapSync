package app.snapsync.model

/**
 * Outcome of a create attempt. [CREATED] → the platform job exists (record `UploadStarted`);
 * [LIMIT_EXCEEDED] → the system's in-flight job cap (defer, request re-invocation); [FAILED] → the
 * job could not be created (e.g. a malformed destination or an unusable resource payload) and was
 * NOT created, so the caller must NOT record `REQUESTED` for a job that does not exist.
 */
enum class CreateResult { CREATED, LIMIT_EXCEEDED, FAILED }

/**
 * The terminal disposition of one cycle; the Swift shell maps it to the system result.
 *
 * [SKIPPED] is not a flavour of [COMPLETED]: a caller that re-arms background work must be able to tell
 * "there is nothing left to do **right now**" from "this device contributes nothing, **ever**". Collapsing
 * them re-arms a heartbeat forever on a device that will never upload. Keeping them apart is also what makes
 * every `when` over this type a decision the compiler forces, rather than a default someone inherits.
 *
 * Sealed rather than an enum because [Paused] carries its reason.
 */
sealed interface CycleResult {
    /** The cycle drained: discovery is exhausted and nothing is pending. */
    data object COMPLETED : CycleResult

    /** Work remains (cap reached / backpressure); an external trigger must re-invoke. */
    data object PROCESSING : CycleResult

    /** The cycle failed. */
    data object FAILED : CycleResult

    /**
     * The cycle **declined**: this membership contributes nothing (`Contribution.None` — its participation
     * direction excludes upload), there is no membership at all, this process's engine is not the resolved
     * mechanism, or this process holds no full photo grant (capability `background-upload`). No walk and no job.
     *
     * Distinct from [COMPLETED] because the re-arm answer differs: a drained cycle may deserve another wake,
     * a declined one never does — whatever makes it eligible again is a transition, and the transition arms.
     */
    data object SKIPPED : CycleResult

    /**
     * The cycle was admitted but **may not run yet**, and touched nothing — not even the platform's presented
     * results, which the next run acknowledges. Unlike [SKIPPED] it asks to be re-invoked: what it waits for
     * is another process's work, not a transition of this one.
     */
    data class Paused(val reason: PauseReason) : CycleResult

    companion object {
        /** Every case, [Paused] once per reason — for tables that must cover them all. */
        val all: List<CycleResult> =
            listOf(COMPLETED, PROCESSING, FAILED, SKIPPED) + PauseReason.entries.map(::Paused)
    }
}

/** Why an admitted cycle [CycleResult.Paused]. */
enum class PauseReason {
    /**
     * The download store this process reads for echo suppression is at an older schema than this build's, and
     * this process may not migrate it (the upload extension opens it read-only). Running without suppression
     * would re-upload downloaded photos, so it waits for the app — the store's one writer — to migrate.
     */
    OLD_SCHEMA,
}

/**
 * Whether this process's echo-suppression read can answer right now — asked by the upload cycle after its
 * admission and before anything else (capability `receiving-photos`).
 */
sealed interface SuppressionReadiness {
    /** It can answer (an absent store answers "nothing suppressed": nothing was ever downloaded). */
    data object Ready : SuppressionReadiness

    /** The store is at an older schema this process may not migrate: pause, and ask to be re-invoked. */
    data object OldSchema : SuppressionReadiness

    /** The store could not be opened: "I could not look" — upload nothing this run. */
    data class Unavailable(val detail: String) : SuppressionReadiness
}
