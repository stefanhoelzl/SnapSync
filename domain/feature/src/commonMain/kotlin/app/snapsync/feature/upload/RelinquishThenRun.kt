package app.snapsync.feature.upload

/**
 * A resolved mechanism that first **relinquishes what the other mechanism left behind**, then runs
 * (capability `upload-lifecycle`, "The upload mechanism is resolved, never selected").
 *
 * Both mechanisms leave state the OS keeps on this app's behalf, and **both kinds of leftovers outlive
 * the process**, so a freshly-launched process can be running behind work it never started:
 *
 * | left by | what survives | what relinquishing it means |
 * |---|---|---|
 * | OS-driven | the upload-job configuration record, keyed by bundle id — survives relaunch **and reinstall** | its `stop()`: deregister |
 * | app-driven | in-flight background `URLSession` tasks and a submitted `BGProcessingTask` | its `stop()`: cancel them |
 *
 * In both directions the relinquish is the outgoing mechanism's ordinary `stop()`, and neither `stop()`
 * repairs ledger rows: the `REQUESTED` rows a relinquish orphans are demoted by the incoming mechanism's own
 * `start()` (`upload-lifecycle`). An earlier design needed a teardown *narrower* than the OS-driven `stop()`
 * here, because that `stop()` deleted every `REQUESTED` row and reset the shared cursor; with the repair
 * moved into `start()`, there is nothing left to narrow.
 *
 * [relinquish] stays a lambda rather than an [UploadProducer] because the composition hands the table the
 * OS-driven mechanism only where the OS carries it, while this wrapper is bound at the composition site.
 * That keeps the lifecycle seam at exactly two verbs and keeps this class platform-free.
 *
 * **Relinquishing happens on `start()`, not on `stop()`.** The arm hands over by starting the incoming
 * cell, so the outgoing mechanism's teardown belongs there — that is what makes the switch stop-then-start
 * without the arm having to know which teardown this direction needs. Doing it on `stop()` as well would
 * tear the *incoming* mechanism down on a leave that stops both cells, and would deregister a registration
 * a re-register is about to recreate.
 */
class RelinquishThenRun(
    /** Give up what the other mechanism left — that mechanism's ordinary `stop()`. */
    private val relinquish: suspend () -> Unit,
    /** The mechanism that actually uploads here, and that answers every trigger. */
    private val mechanism: UploadMechanismRuntime,
) : UploadMechanismRuntime by mechanism {

    override suspend fun start() {
        relinquish()
        mechanism.start()
    }
}
