package app.snapsync.flow

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **silent-push** OS-callback trigger flow (capability `push-registration`). A `content-available`
 * push means "the event changed" — news to **both** arms: foreign photos to pull (`photo-download`)
 * and, since the event is demonstrably live, a good moment to contribute our own
 * (`ios-url-session-upload`). This flow is the cross-arm **fan-out** that used to be
 * `FanOutPushReceiver`: fanning one push out to each arm's receiver IS the trigger's coordination, so
 * it lives here rather than in a receiver that would then have to know about the other arm.
 *
 * Each arm keeps its OWN active-event guard, in its own tested feature; this flow only fans out. The
 * receivers run **in sequence, isolated**: the push budget is short and both arms touch the
 * ledger/store, so serialising is cheaper and safer than racing them, and one throwing must never rob
 * the others of the scarce wake.
 *
 * [protectedDataGate] defers the whole fan-out to the next unlock when protected data is unreadable
 * (a silent push reaches a locked device, and the reconcile reads the Keychain + download store);
 * [refreshAttestation] is the wake-point token renewal. Both are irreducibly the shell's (the
 * `ProtectedDataGate` needs `UIApplication`), injected as `compose/`-built effect lambdas. The forge
 * guard, the entry-point log wrap, and the OS completion handler stay in the shell.
 */
class SilentPush(
    private val scope: CoroutineScope,
    /** Run the work now if protected data is readable, else defer under the tag until unlock. */
    private val protectedDataGate: (tag: String, work: () -> Unit) -> Unit,
    private val refreshAttestation: () -> Unit,
    /** Each arm's receiver as a `suspend (eventId)` — the download arm's, plus the upload arm's on the
     *  app-driven tier. Order preserved from composition (download, then upload). */
    private val receivers: List<suspend (eventId: String) -> Unit>,
    private val log: Logger = Logger.withTag("SilentPush"),
) {
    fun run(eventId: String) {
        protectedDataGate("onSilentPush") {
            // Wake point (capability `device-attestation`) — inside the gate: the Keychain holding the
            // token is unreadable before the first unlock since boot.
            refreshAttestation()
            scope.launch { fanOut(eventId) }
        }
    }

    /** Fan one push out to every arm's receiver, isolated: one failure never robs the others. */
    suspend fun fanOut(eventId: String) {
        for (receiver in receivers) {
            runCatching { receiver(eventId) }
                .onFailure { log.w(it) { "a push receiver failed for $eventId; the others still run" } }
        }
    }
}
