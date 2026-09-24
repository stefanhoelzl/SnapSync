package app.snapsync.flow

import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.pushEventId
import co.touchlab.kermit.Logger

/**
 * The **silent-push** OS-callback trigger flow (capability `push-registration`, "Silent-push receive seam"). A
 * `content-available` push means "the event changed": foreign photos to pull. This flow runs the push's **own work**
 * and only that — the download arm's receiver, whose union read, plan and enqueue are what the push exists to cause
 * (decision record `changes/own-work-per-wake`, D1).
 *
 * **The upload arm is no longer a receiver.** A push is still news to it — another member's upload completing is when
 * this device most likely has photos of its own to contribute — but the upload top-up and the walk reach the wake only
 * through the process's tail, which runs after the OS handler is released, under the process's background time.
 * Running the upload cycle here held the handler behind a library walk (measured: a push that waited 22.5 s behind
 * another cycle's walk) for work the push is not about. Whether the wake joins that tail — only for the active event
 * — is decided by the upload arm's tested guard, and the tail is requested by the inbound port's implementation
 * **after** this flow returns, never from inside it (law "A trigger flow never outlives its own run").
 *
 * [run] takes the OS payload **whole** (migration step 12, the transcriber law): the `model/` codec ([pushEventId]) is
 * the one place that knows the payload's shape. A payload with no usable event id runs no receiver; the OS handler is
 * released either way.
 *
 * [reloadConfig] re-reads the persisted membership into the config StateFlow **before** the receiver: a push can
 * reach a process whose StateFlow was seeded from an unreadable pre-first-unlock read, or one another process has
 * since re-provisioned, and the guards read that membership. A pre-first-unlock wake runs through and fails cleanly
 * (the adapters distinguish unreadable from absent; nothing mints, clears, or leaves), converging at the next trigger.
 * [refreshAttestation] is the wake-point token renewal. Both are port touches injected as `compose/`-built effects.
 */
class SilentPush(
    /** Re-read the persisted membership into the config StateFlow — the port touch, injected. */
    private val reloadConfig: suspend () -> Unit,
    private val refreshAttestation: suspend () -> Unit,
    /** The download arm's receiver — the push's own work. Its active-event and direction guards are its own. */
    private val downloadReceiver: suspend (eventId: String) -> Unit,
    private val log: Logger = Logger.withTag("SilentPush"),
) {
    suspend fun run(userInfo: Map<Any?, *>) {
        val eventId = pushEventId(userInfo)
        if (eventId == null) {
            log.i { "silent push carried no eventId — no receiver runs" }
            return
        }
        reloadConfig()
        // Wake point (capability `device-attestation`): a scarce background wake is a renewal chance. Awaited before
        // the receiver, so its requests carry the token this just renewed rather than racing it.
        refreshAttestation()
        // Awaited, not launched (law "A trigger flow never outlives its own run"): the caller releases the OS handler
        // when this returns, and a push whose own work is merely queued then is one the system may suspend mid-way.
        // Isolated: a receiver that throws still leaves the wake its tail — the imports it would drain are staged
        // already, and a failed union read has nothing to say about them.
        runCatchingCancellable { downloadReceiver(eventId) }
            .onFailure { log.w(it) { "the download arm failed for push $eventId; the tail still runs if due" } }
    }
}
