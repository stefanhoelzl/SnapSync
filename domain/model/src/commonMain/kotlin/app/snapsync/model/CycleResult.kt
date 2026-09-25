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
 * every `when` over this enum a decision the compiler forces, rather than a default someone inherits.
 */
enum class CycleResult {
    /** The cycle drained: discovery is exhausted and nothing is pending. */
    COMPLETED,

    /** Work remains (cap reached / backpressure); an external trigger must re-invoke. */
    PROCESSING,

    /** The cycle failed. */
    FAILED,

    /**
     * The cycle **declined**: this membership contributes nothing (`Contribution.None` — its participation
     * direction excludes upload), there is no membership at all, this process's engine is not the resolved
     * mechanism, or this process holds no full photo grant (capability `background-upload`). No walk and no job.
     *
     * Distinct from [COMPLETED] because the re-arm answer differs: a drained cycle may deserve another wake,
     * a declined one never does — whatever makes it eligible again is a transition, and the transition arms.
     */
    SKIPPED,
}
