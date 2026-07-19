package app.snapsync.flow

import app.snapsync.model.pushEventId
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
 * [run] takes the OS payload **whole** (migration step 12, the transcriber law): the Swift shell
 * forwards `userInfo` raw, and the `model/` codec ([pushEventId]) is the one place that knows the
 * payload's shape — the field extraction used to be a Swift `guard`, where nothing could test it.
 * A payload with no usable event id fans out to no arm (the shell still releases the OS handler).
 *
 * Each arm keeps its OWN active-event guard, in its own tested feature; this flow only fans out. The
 * receivers run **in sequence, isolated**: the push budget is short and both arms touch the
 * ledger/store, so serialising is cheaper and safer than racing them, and one throwing must never rob
 * the others of the scarce wake.
 *
 * [reloadConfig] re-reads the persisted membership into the config StateFlow **before** the fan-out
 * (migration step 12, replacing the deleted protected-data defer ceremony): a push can reach a
 * process whose StateFlow was seeded from an unreadable pre-first-unlock read, or one another
 * process has since re-provisioned, and the receivers' active-event guards read that StateFlow. The
 * pre-first-unlock wake itself — never observed in production (settled proof ④: zero deferrals
 * across all logs) — now runs through and fails cleanly (adapters distinguish unreadable from
 * absent; nothing mints, clears, or leaves), converging at the next trigger. [refreshAttestation]
 * is the wake-point token renewal. Both are port/shell touches injected as `compose/`-built effect
 * lambdas. The forge guard, the entry-point log wrap, and the OS completion handler stay in the
 * shell.
 */
class SilentPush(
    private val scope: CoroutineScope,
    /** Re-read the persisted membership into the config StateFlow — the port touch, injected. */
    private val reloadConfig: () -> Unit,
    private val refreshAttestation: () -> Unit,
    /** Each arm's receiver as a `suspend (eventId)` — the download arm's, plus the upload arm's on the
     *  app-driven tier. Order preserved from composition (download, then upload). */
    private val receivers: List<suspend (eventId: String) -> Unit>,
    private val log: Logger = Logger.withTag("SilentPush"),
) {
    fun run(userInfo: Map<Any?, *>) {
        val eventId = pushEventId(userInfo)
        if (eventId == null) {
            log.i { "silent push carried no eventId — nothing to fan out" }
            return
        }
        reloadConfig()
        // Wake point (capability `device-attestation`): a scarce background wake is a renewal chance.
        refreshAttestation()
        scope.launch { fanOut(eventId) }
    }

    /** Fan one push out to every arm's receiver, isolated: one failure never robs the others. */
    suspend fun fanOut(eventId: String) {
        for (receiver in receivers) {
            runCatching { receiver(eventId) }
                .onFailure { log.w(it) { "a push receiver failed for $eventId; the others still run" } }
        }
    }
}
