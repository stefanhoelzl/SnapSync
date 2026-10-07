package app.snapsync.feature.membership

import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.model.EventLookup
import app.snapsync.services.backend.EventDirectory
import app.snapsync.model.JoinResult

import app.snapsync.services.config.ConfigService
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.clampToCeiling
import app.snapsync.model.clampToFloor
import app.snapsync.model.JoinLoad
import app.snapsync.services.crypto.EventKeys
import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger

/**
 * The outcome of a confirmed join (capability `join-event`).
 * - [Committed]: enrolled and provisioned — the device is now joined.
 * - [AlreadyJoined]: the target is the currently-configured event — a no-op that does **not** re-enroll
 *   (protects a real asset manifest from the empty-manifest clobber; see the join-event spec).
 * - [EventFull]: the event already holds its maximum number of devices — a refusal the USER can act on,
 *   kept apart from [EnrollFailed] because the two have different remedies and a screen must be able to
 *   say which (`docs/architecture.md`, "Absence is never silent"). Nothing is persisted either way.
 * - [Unverified]: the service did not accept this phone's credential — the attestation's refusal says why.
 * - [EnrollFailed]: the join request failed or the event is gone — nothing persisted, no producer enabled.
 */
enum class JoinOutcome { Committed, AlreadyJoined, EventFull, EventClosed, Unverified, EnrollFailed, WrongLink }

/**
 * What the join surface should show for this outcome (capability `join-event`).
 *
 * A pure mapping, here rather than in `compose/`, because deciding that capacity and a transient
 * failure reach DIFFERENT screens — one offering a Retry, one deliberately not — is a rule, and a rule
 * in the wiring is a rule no test can reach. `AlreadyJoined` folds into [JoinCommit.Committed]: a
 * member who is already in the event is in the event, and re-confirming is a no-op, not a failure.
 */
fun JoinOutcome.toCommit(): JoinCommit = when (this) {
    JoinOutcome.Committed, JoinOutcome.AlreadyJoined -> JoinCommit.Committed
    JoinOutcome.EventFull -> JoinCommit.Full
    JoinOutcome.EventClosed -> JoinCommit.Closed
    JoinOutcome.Unverified -> JoinCommit.Unverified
    JoinOutcome.EnrollFailed -> JoinCommit.Failed
    JoinOutcome.WrongLink -> JoinCommit.WrongLink
}

/**
 * The app-side join use-case: the details fetch, the register-only enrollment, and the commit. Pure
 * `commonMain` — the platform commit (save config + enable upload + reconcile downloads) is injected as
 * [provision], so the shell stays wiring-only. The switch (leave-then-join) is composed by the caller
 * (the presentation container), not here, so this stays free of the leave use-case.
 */
class JoinEvent(
    private val configSource: ConfigService,
    private val identity: PersistedDeviceIdentity,
    private val details: EventDirectory,
    private val enroller: DeviceEnroller,
    /** An encrypted event's key: checked against the event before a join, and kept once it succeeds. */
    private val keys: EventKeys,
    private val provision: suspend (EventConfig) -> Unit,
) {

    /** Fetch the event's details for the confirmation gate (loading → loaded/not-found/failed). */
    suspend fun loadDetails(eventId: String): EventLookup = details.fetch(eventId)

    /**
     * The confirmation gate's read: the event's details, and [JoinLoad.WrongLink] when the link's key ([linkKey]) does
     * not open it — so a link cut short in sharing is told before the member chooses anything, not after.
     */
    suspend fun loadJoin(eventId: String, linkKey: String?): JoinLoad = when (val load = loadDetails(eventId).toJoinLoad()) {
        is JoinLoad.Found -> if (keys.opens(linkKey, load.keyId)) load else JoinLoad.WrongLink
        else -> load
    }

    /**
     * Confirm the join of [choice]: the event with its loaded name (required, non-null — the gate only
     * provisions from a loaded phase that carries a name), the event's `startsAt` start date, this
     * device's chosen capture-date `minPhotoDate` cutoff (capability `photo-sharing`; always present
     * — a membership without a cutoff would upload the whole library), its chosen participation
     * `direction` (capability `join-event`), and whether it opted into an event album (`saveToAlbum`,
     * capability `event-album`): enroll (register-only empty manifest) — for **every** direction, so a
     * download-only device is still an enrolled member — then, only on a successful enrollment, provision
     * (save config **with the clamped cutoff, the start date, and the direction**). The injected
     * [provision] enables the upload producer only when `Direction.includesUpload` and runs the download
     * reconcile only when `Direction.includesDownload` (the latter gated inside the download controller).
     * Re-confirming the already-joined event is a [JoinOutcome.AlreadyJoined] no-op that skips enrollment
     * entirely — re-*scanning* never rewrites config. Changing the cutoff, direction, or album opt-in of a
     * joined membership is done **in place** by `ReconfigureEvent` (capability `manage-membership`),
     * not by leaving and re-joining; only `startsAt` (the floor) stays immutable for the membership's life.
     *
     * **The floor is applied here** (capability `photo-sharing`): the persisted cutoff is
     * `max(chosen, startsAt)`, never the raw `minPhotoDate`. Doing it in the use-case rather than in the
     * UI is what makes it total — **every** entry path funnels through this one call (the interactive
     * confirm, the switch confirm, the retry, and the `autoJoin` path carrying an event-link-supplied
     * cutoff), so none of them can forget it. That last one is the reason it matters: `minPhotoDate` is
     * decoded from **any** event link, so without the clamp a hostile QR carrying
     * `autoJoin=true` + a distant-past cutoff would auto-confirm a join at near-whole-library scope
     * *without a tap*.
     *
     * Because `startsAt` is immutable, the clamped value is stable for the life of the membership — which
     * is what lets the upload cycle keep filtering on a single cutoff, with `startsAt` never reaching the
     * upload path at all.
     */
    private val log = Logger.withTag("JoinEvent")

    suspend fun join(choice: JoinChoice): JoinOutcome {
        val eventId = choice.eventId
        if (configSource.config.value?.eventId == eventId) return JoinOutcome.AlreadyJoined
        // Every entry path funnels here, the headless one included, so the key is checked here too — never only on
        // the screen: a member must not join an encrypted event it could not read, nor upload plaintext into it.
        if (!keys.opens(choice.linkKey, choice.eventKeyId)) return JoinOutcome.WrongLink
        when (enroller.enroll(eventId, identity.deviceId())) {
            JoinResult.JOINED -> Unit
            JoinResult.EVENT_FULL -> return JoinOutcome.EventFull
            JoinResult.EVENT_CLOSED -> return JoinOutcome.EventClosed
            JoinResult.UNVERIFIED -> return JoinOutcome.Unverified
            JoinResult.EVENT_NOT_FOUND, JoinResult.FAILED -> return JoinOutcome.EnrollFailed
        }
        // The key before the config: a config naming a key id must never exist without its key. A refused write fails
        // the join, which a retry repeats; a plain event's join removes a previous event's key.
        val kept = runCatchingCancellable { choice.linkKey?.let(keys::keep) ?: keys.forget() }
        if (kept.isFailure) {
            log.w(kept.exceptionOrNull()) { "join: the event key could not be kept — the join is retried" }
            return JoinOutcome.EnrollFailed
        }
        provision(
            EventConfig(
                eventId = eventId,
                name = choice.name,
                minPhotoDate = clampToFloor(chosen = choice.minPhotoDate, startsAt = choice.startsAt),
                startsAt = choice.startsAt,
                endsAt = choice.endsAt,
                maxPhotoDate = clampToCeiling(chosen = choice.maxPhotoDate, endsAt = choice.endsAt),
                // Persisted verbatim from the loaded details, never computed here: it is the OFFLINE
                // witness of the self-leave (capability `manage-membership`), and a client-derived one would
                // decide whether this membership is later destroyed.
                deletesAt = choice.deletesAt,
                direction = choice.direction,
                saveToAlbum = choice.saveToAlbum,
                keyId = choice.eventKeyId,
            ),
        )
        return JoinOutcome.Committed
    }
}
