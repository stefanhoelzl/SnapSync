package app.snapsync.feature.membership

import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDirectory
import app.snapsync.ports.JoinResult

import app.snapsync.ports.ConfigSource
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.DeletesAt
import app.snapsync.model.Direction
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinCommit
import app.snapsync.model.clampToCeiling
import app.snapsync.model.clampToFloor

/**
 * The outcome of a confirmed join (capability `join-event`).
 * - [Committed]: enrolled and provisioned — the device is now joined.
 * - [AlreadyJoined]: the target is the currently-configured event — a no-op that does **not** re-enroll
 *   (protects a real asset manifest from the empty-manifest clobber; see the join-event spec).
 * - [EventFull]: the event already holds its maximum number of devices — a refusal the USER can act on,
 *   kept apart from [EnrollFailed] because the two have different remedies and a screen must be able to
 *   say which (`module-architecture`, "Absence is never silent"). Nothing is persisted either way.
 * - [EnrollFailed]: the join request failed or the event is gone — nothing persisted, no producer enabled.
 */
enum class JoinOutcome { Committed, AlreadyJoined, EventFull, EnrollFailed }

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
    JoinOutcome.EnrollFailed -> JoinCommit.Failed
}

/**
 * The app-side join use-case: the details fetch, the register-only enrollment, and the commit. Pure
 * `commonMain` — the platform commit (save config + enable upload + reconcile downloads) is injected as
 * [provision], so the shell stays wiring-only. The switch (leave-then-join) is composed by the caller
 * (the presentation container), not here, so this stays free of the leave use-case.
 */
class JoinEvent(
    private val configSource: ConfigSource,
    private val deviceId: () -> String,
    private val details: EventDirectory,
    private val enroller: DeviceEnroller,
    private val provision: suspend (EventConfig) -> Unit,
) {

    /** Fetch the event's details for the confirmation gate (loading → loaded/not-found/failed). */
    suspend fun loadDetails(eventId: String): EventDetails = details.fetch(eventId)

    /**
     * Confirm the join for [eventId] with the loaded [name] (required, non-null — the gate only
     * provisions from a loaded phase that carries a name), the event's [startsAt] start date, this
     * device's chosen capture-date [minPhotoDate] cutoff (capability `photo-selection-policy`; always present
     * — a membership without a cutoff would upload the whole library), its chosen participation
     * [direction] (capability `join-event`), and whether it opted into an event album ([saveToAlbum],
     * capability `event-album`): enroll (register-only empty manifest) — for **every** direction, so a
     * download-only device is still an enrolled member — then, only on a successful enrollment, provision
     * (save config **with the clamped cutoff, the start date, and the direction**). The injected
     * [provision] enables the upload producer only when [Direction.includesUpload] and runs the download
     * reconcile only when [Direction.includesDownload] (the latter gated inside the download controller).
     * Re-confirming the already-joined event is a [JoinOutcome.AlreadyJoined] no-op that skips enrollment
     * entirely — re-*scanning* never rewrites config. Changing the cutoff, direction, or album opt-in of a
     * joined membership is done **in place** by `ReconfigureEvent` (capability `reconfigure-membership`),
     * not by leaving and re-joining; only [startsAt] (the floor) stays immutable for the membership's life.
     *
     * **The floor is applied here** (capability `photo-selection-policy`): the persisted cutoff is
     * `max(chosen, startsAt)`, never the raw [minPhotoDate]. Doing it in the use-case rather than in the
     * UI is what makes it total — **every** entry path funnels through this one call (the interactive
     * confirm, the switch confirm, the retry, and the `autoJoin` path carrying an event-link-supplied
     * cutoff), so none of them can forget it. That last one is the reason it matters: `minPhotoDate` is
     * decoded from **any** event link, so without the clamp a hostile QR carrying
     * `autoJoin=true` + a distant-past cutoff would auto-confirm a join at near-whole-library scope
     * *without a tap*.
     *
     * Because [startsAt] is immutable, the clamped value is stable for the life of the membership — which
     * is what lets the upload cycle keep filtering on a single cutoff, with `startsAt` never reaching the
     * upload path at all.
     */
    suspend fun join(
        eventId: String,
        name: String,
        startsAt: EventStart,
        endsAt: EventEnd,
        deletesAt: DeletesAt,
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
        direction: Direction,
        saveToAlbum: Boolean,
    ): JoinOutcome {
        if (configSource.config.value?.eventId == eventId) return JoinOutcome.AlreadyJoined
        when (enroller.enroll(eventId, deviceId())) {
            JoinResult.JOINED -> Unit
            JoinResult.EVENT_FULL -> return JoinOutcome.EventFull
            JoinResult.EVENT_NOT_FOUND, JoinResult.FAILED -> return JoinOutcome.EnrollFailed
        }
        provision(
            EventConfig(
                eventId = eventId,
                name = name,
                minPhotoDate = clampToFloor(chosen = minPhotoDate, startsAt = startsAt),
                startsAt = startsAt,
                endsAt = endsAt,
                maxPhotoDate = clampToCeiling(chosen = maxPhotoDate, endsAt = endsAt),
                // Persisted verbatim from the loaded details, never computed here: it is the OFFLINE
                // witness of the self-leave (capability `leave-event`), and a client-derived one would
                // decide whether this membership is later destroyed.
                deletesAt = deletesAt,
                direction = direction,
                saveToAlbum = saveToAlbum,
            ),
        )
        return JoinOutcome.Committed
    }
}
