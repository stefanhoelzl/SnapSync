package app.snapsync.feature.trust

import app.snapsync.ports.AttestClient
import app.snapsync.ports.Clock
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Renew once the token has less than this left. The token lives 30 days, so renewal is attempted from
 * day 23 onward — at every wake the app process happens to get, not on a schedule.
 *
 * Renewal is *cheap* (a local Secure-Enclave assertion, no Apple round-trip), which is the only reason it
 * can be attempted this eagerly. Were it a full re-attestation — Apple's throttled path — it would have to
 * be rare, and rare renewal means a narrow window near expiry, which is the failure this margin exists to
 * avoid.
 */
private const val RENEW_WHEN_REMAINING_SECONDS: Long = 7 * 24 * 60 * 60

/**
 * The device token's expiry, in epoch seconds, or null if [token] is not one of ours.
 *
 * The token is `<deviceId>.<expiry>.<signature>` — **signed, not encrypted** — so the device can read its
 * own expiry with no key and no network. That is what lets the staleness check below be a local decision.
 */
fun tokenExpirySeconds(token: String): Long? = token.split(".").getOrNull(1)?.toLongOrNull()

/**
 * The device-attestation use case (capability `device-attestation`): obtain and keep alive the bearer
 * token every backend call carries.
 *
 * **Only the app process runs this.** App Attest is unavailable in the upload extension
 * ([AttestKey.isSupported] is false there — verified on device), so the extension only ever *reads*
 * [AttestStore.token], sends whatever it finds, and lets a `401` be retried. Everything here — attest,
 * renew, the staleness check — happens in the app.
 *
 * [refresh] is called at **every** point the app process is already awake: launch, foreground, a
 * silent-push wake, and each `BGTask` handler. Not on a schedule and not from a dedicated background task:
 * iOS budgets task identifiers per app, so a dedicated renewal task would compete with the two the app
 * already has and would still run only when the system felt like it. Checking at every wake yields
 * strictly more chances to renew than any schedule could.
 *
 * **The accepted degradation.** Because the extension cannot renew, and because the silent push that most
 * reliably wakes the app is itself triggered by a *successful* upload, an expired token deadlocks its own
 * renewal: 401 → no completion → no notify → no push → no wake. The 30-day lifetime is what makes falling
 * into that rare; retry-forever means no photo is ever lost when it happens; and the visible error state is
 * the signal that it has. Decision record: `changes/archive/2026-07-14-add-device-attestation`.
 */
class DeviceAttestation(
    private val key: AttestKey,
    private val client: AttestClient,
    private val store: AttestStore,
    private val deviceId: () -> String,
    private val clock: Clock,
    private val log: Logger = Logger.withTag("DeviceAttestation"),
) {

    private val _tokenChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _attested = MutableStateFlow(true)

    /**
     * Emits whenever a NEW token is obtained (minted or renewed).
     *
     * Anything that had to be *sent* with the old credential and was refused must be re-sent — most
     * importantly the APNs registration, which is `PUT` exactly once per OS-delivered token. If its `PUT`
     * is refused (a fresh install races attestation, or the token is rejected), the OS never delivers that
     * token again, so without this the device would go **permanently unregistered**: no silent pushes, no
     * download wakes, and none of the wake-driven renewals this capability depends on.
     */
    val tokenChanged: Flow<Unit> = _tokenChanged

    /** What every request builder reads. MAY be absent or expired — a `401` is a retryable failure. */
    fun token(): String? = store.token()

    /**
     * The backend REJECTED our token (a `401` from a gated route). Drop it, so the next [ensureFresh]
     * obtains a new one.
     *
     * This exists because "rejected" and "expired" are NOT the same thing, and [isStale] only knows about
     * the second. A token is rejected while still far from expiry whenever the server-side signing key is
     * rotated, or the leave cascade collects this device's attestation record. Without dropping it, the app
     * would look at a perfectly fresh-*looking* token, decide nothing needed doing, and re-send the same
     * dead credential forever — a permanent, silent 401 loop that no wake could heal.
     *
     * The `keyId` is deliberately kept: the Secure-Enclave key is still valid, so recovery is a cheap
     * assertion, not a throttled re-attestation.
     */
    fun onRejected() {
        log.w { "the backend rejected our token — dropping it so the next wake obtains a new one" }
        store.clearToken()
    }

    /**
     * Serializes refreshes. Observed on device: a rejected token produces a `401` on EVERY in-flight
     * request, each of which independently asked for a refresh — three concurrent `/attest/renew` calls
     * went out for one rejection. Harmless there, but it is a thundering herd aimed at Apple's throttled
     * path, and the second and third refresh are pure waste: the first already fixed it.
     *
     * The staleness check is re-run INSIDE the lock, so callers that queued behind a refresh that already
     * succeeded do nothing at all.
     */
    private val refreshing = Mutex()

    /** Whether [token] is missing, expired, or close enough to expiry to renew now. */
    fun isStale(token: String?): Boolean {
        val expiry = token?.let(::tokenExpirySeconds) ?: return true
        return expiry - clock.now().epochSeconds < RENEW_WHEN_REMAINING_SECONDS
    }

    /**
     * Whether [token] cannot authorize a request **right now**: it is absent, unreadable, or past its
     * expiry.
     *
     * **This is NOT [isStale], and the two must never be collapsed into one predicate.** [isStale]
     * answers *should this wake spend a renewal?* and deliberately fires a full week early, because
     * renewing eagerly is the only thing that keeps the token alive across iOS starving the app's
     * background wakes. This one answers *is the device stuck?* — and a token inside that week-wide
     * margin is not stuck at all: it authorizes every gated request until the instant it expires
     * (the backend's check is this same expiry comparison plus one HMAC, nothing else). Answering the
     * second question with the first is how the status screen came to tell a member that sharing was
     * paused while their token had six days left and every upload was authorized (`SNAPSYNC-20`).
     *
     * An unreadable token counts as unusable. [tokenExpirySeconds] returns null for anything that is
     * not `<deviceId>.<expiry>.<signature>`, and a token whose expiry we cannot read is one we cannot
     * show to be valid — the backend will reject it, and calling it usable would put the member back
     * behind a screen reading "Syncing" while every upload `401`s.
     *
     * The comparison matches the backend's `verifyToken` exactly: valid while `now <= expiry`.
     */
    fun isUnusable(token: String?): Boolean {
        val expiry = token?.let(::tokenExpirySeconds) ?: return true
        return clock.now().epochSeconds > expiry
    }

    /**
     * Refresh the token if it is stale. Safe to call at every wake: it is a no-op on a fresh token, and it
     * never throws — attestation failure is reduced to `false` and surfaced by the caller, never propagated
     * into a background wake it would crash.
     *
     * Returns whether a usable (fresh) token is in the store afterwards.
     */
    suspend fun ensureFresh(): Boolean = refreshing.withLock { refreshLocked() }

    /**
     * Whether this device holds a usable attestation token — the one fact the status screen surfaces
     * (`SyncHealth.Unattested`, capability `sync-status-screen`).
     *
     * A **derived cache of the last refresh**, never authority: authority is the token itself, in the
     * `AttestStore` behind the port (law "State and authority"). Kill the process and this is rebuilt
     * from the store by the first refresh; nothing durable depends on it.
     *
     * `false` means what it says: the token is **unusable** ([isUnusable] — absent, unreadable, or
     * expired) **and** the refresh could not obtain one. It does NOT mean the token is stale; a stale
     * token that renews is a non-event, and one that fails to renew while still valid is *also* a
     * non-event, because it still authorizes every upload.
     *
     * **A value here never outlives the start of the next [refresh].** That is the second half of
     * `SNAPSYNC-20` and the reason this lives in the feature rather than in a cell the shell owns: the
     * app re-checks attestation only when its process wakes, and a process can carry an outcome across
     * an arbitrary suspension. A background wake with no network wrote `false` on 2026-08-17 13:37 and
     * the member saw it rendered as the first frame of a foreground entry 25 h 47 min later, under
     * conditions that no longer held. [refresh] clearing this on entry is what makes that unrenderable.
     */
    val attested: StateFlow<Boolean> = _attested.asStateFlow()

    /**
     * Refresh the token if it is stale, and publish what that says about [attested].
     *
     * Called at every wake — launch, foreground, silent push, each `BGTask` — through the trigger flows.
     * Non-throwing, like [ensureFresh]: a background wake must not die because attestation failed.
     *
     * **The clear on entry is load-bearing, not tidiness.** It brackets the *attempt*: from here until
     * this call returns, the last wake's verdict is gone and only this attempt's answer can be shown. It
     * deliberately sits OUTSIDE [refreshing] — a caller queued behind a slow refresh must clear the stale
     * verdict when it *arrives*, not when it finally acquires the lock, or the very frame this exists to
     * fix would still render the old answer while waiting.
     *
     * The cost is stated plainly: for the duration of a refresh the app claims attested while it does not
     * yet know. That was already the standing default (the flag initialises `true`), the state it briefly
     * suppresses is non-actionable, and the alternative — a third "checking" value — produces an identical
     * screen. Design record: `changes/archive/2026-08-25-correct-attestation-health-surfacing` D2/D4.
     */
    suspend fun refresh() {
        _attested.value = true
        _attested.value = refreshOutcome()
    }

    /**
     * [ensureFresh], reduced to the one fact [attested] carries.
     *
     * The second clause is what keeps a *working* device quiet: a refresh that reports `false` still
     * leaves the device fine if the stored token is usable — it was merely due for renewal, or a
     * concurrent path already fixed it. (Drained verbatim from the untested app shell at the migration
     * finale — the rule is the trust feature's, not wiring — and narrowed here from [isStale] to
     * [isUnusable], which is the `SNAPSYNC-20` correction.)
     */
    private suspend fun refreshOutcome(): Boolean {
        val ok = runCatching { ensureFresh() }.getOrDefault(false)
        return ok || !isUnusable(token())
    }

    private suspend fun refreshLocked(): Boolean {
        if (!key.isSupported()) {
            // The extension. It must never reach here — but if it ever does, do nothing rather than
            // half-attesting: it has no App Attest to attest WITH.
            log.d { "App Attest is unavailable in this process — not attesting" }
            return !isStale(store.token())
        }

        val current = store.token()
        if (!isStale(current)) return true

        val challenge = client.challenge()
        if (challenge == null) {
            log.w { "could not obtain a challenge — leaving the existing token in place" }
            return false
        }

        // Renew with an ASSERTION when this install has already attested: no Apple round-trip, so it is
        // cheap enough to have been attempted at every wake. Only a device that has never attested (a fresh
        // install, or one whose Secure-Enclave key died with a reinstall) pays for a full attestation.
        val existingKeyId = store.keyId()
        if (existingKeyId != null) {
            val renewed = runCatching {
                client.renewToken(deviceId(), key.assert(existingKeyId, challenge), challenge)
            }.getOrElse {
                // The assertion itself failed, LOCALLY — no renewal request was ever sent. `AttestClient`
                // maps every transport and refusal outcome to null by contract, so the only thing that can
                // throw in here is the Secure-Enclave `assert`, and the platform's own error value is the
                // one diagnostic that says why (a dead key reads differently from a transient fault).
                //
                // It used to be discarded with `getOrNull()` and reported as "renewal refused", which named
                // the backend for something the backend was never asked about. That is what made
                // `SNAPSYNC-20` unanswerable: the dump showed a refusal and no request, and the `DCError`
                // that would have settled it had been thrown away. Warn, not Error — the app recovers by
                // attesting afresh below, and this rides as a breadcrumb rather than a crash-triage event.
                log.w(it) { "could not produce a renewal assertion — attesting afresh" }
                null
            }
            if (renewed != null) {
                store.setToken(renewed)
                _tokenChanged.tryEmit(Unit)
                log.i { "token renewed" }
                return true
            }
            // Reached two ways, and the log above distinguishes them: the assertion could not be produced
            // (a throwable, no request), or the backend declined the one we sent — typically because its
            // record of this device is gone (the leave cascade GCs it). Either way, attest afresh rather
            // than stalling forever.
            log.w { "renewal did not yield a token — attesting afresh" }
        }

        return runCatching {
            val keyId = key.generateKey()
            val attestation = key.attest(keyId, challenge)
            val minted = client.mintToken(deviceId(), keyId, attestation, challenge)
            if (minted == null) {
                log.w { "the backend refused the attestation" }
                false
            } else {
                // Persist the keyId only once the backend has ACCEPTED its attestation. A keyId stored for
                // an attestation the backend never recorded would send every future renewal down the
                // assertion path, against a key the server has never heard of.
                store.setKeyId(keyId)
                store.setToken(minted)
                _tokenChanged.tryEmit(Unit)
                log.i { "attested and minted a fresh token" }
                true
            }
        }.getOrElse {
            log.w(it) { "attestation failed" }
            false
        }
    }
}
