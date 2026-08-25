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
 * | OS-driven | the upload-job configuration record, keyed by bundle id — survives relaunch **and reinstall** | deregister it, and *only* that |
 * | app-driven | in-flight background `URLSession` tasks and a submitted `BGProcessingTask` | cancel them |
 *
 * The structure is symmetric; the content is not, and that asymmetry is the point. Tearing the OS-driven
 * mechanism down *on the way to the app-driven one* must be **deregistration only** — its full `stop()`
 * also runs a blanket `clearRequested()` and resets the shared discovery cursor, which on this path is
 * not merely expensive but redundant *and blunter than what follows it*: the mechanism about to start
 * reconciles stranded `REQUESTED` rows precisely from its own task enumeration and by contract "SHALL NOT
 * depend on `clearRequested`" (`ios-url-session-upload`). Running it would delete in-flight rows
 * belonging to the mechanism about to use them. Going the other way there is no such hazard, so the
 * app-driven mechanism's ordinary `stop()` is the right relinquish.
 *
 * That is why [relinquish] is a lambda rather than an [UploadProducer]: what "give this up" means differs
 * per direction, and one of the two is deliberately *narrower* than the seam's `stop()`. Binding it at the
 * composition site keeps the lifecycle seam at exactly two verbs and keeps this class platform-free.
 *
 * **Relinquishing happens on `start()`, not on `stop()`.** The arm hands over by starting the incoming
 * cell, so the outgoing mechanism's teardown belongs there — that is what makes the switch stop-then-start
 * without the arm having to know which teardown this direction needs. Doing it on `stop()` as well would
 * tear the *incoming* mechanism down on a leave that stops both cells, and would deregister a registration
 * a re-register is about to recreate.
 */
class RelinquishThenRun(
    /** Give up what the other mechanism left. Narrower than `stop()` where the seam's stop would over-reach. */
    private val relinquish: suspend () -> Unit,
    /** The mechanism that actually uploads here, and that answers every trigger. */
    private val mechanism: UploadMechanismRuntime,
) : UploadMechanismRuntime by mechanism {

    override suspend fun start() {
        relinquish()
        mechanism.start()
    }
}
