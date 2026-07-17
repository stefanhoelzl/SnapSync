package app.snapsync.join

import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDetailsSource

import app.snapsync.ports.ConfigSource
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.clampToFloor

/**
 * The outcome of a confirmed join (capability `join-event`).
 * - [Committed]: enrolled and provisioned — the device is now joined.
 * - [AlreadyJoined]: the target is the currently-configured event — a no-op that does **not** re-enroll
 *   (protects a real asset manifest from the empty-manifest clobber; see the join-event spec).
 * - [EnrollFailed]: the enrollment PUT failed — nothing was persisted and no producer enabled.
 */
enum class JoinOutcome { Committed, AlreadyJoined, EnrollFailed }

/**
 * The app-side join use-case: the details fetch, the register-only enrollment, and the commit. Pure
 * `commonMain` — the platform commit (save config + enable upload + reconcile downloads) is injected as
 * [provision], so the shell stays wiring-only. The switch (leave-then-join) is composed by the caller
 * (the presentation container), not here, so this stays free of the leave use-case.
 */
class JoinEvent(
    private val configSource: ConfigSource,
    private val deviceId: () -> String,
    private val details: EventDetailsSource,
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
     * entirely (the cutoff and direction stay immutable — a change is a leave-then-rejoin).
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
        startsAt: String,
        minPhotoDate: String,
        direction: Direction,
        saveToAlbum: Boolean,
    ): JoinOutcome {
        if (configSource.config.value?.eventId == eventId) return JoinOutcome.AlreadyJoined
        if (!enroller.enroll(eventId, deviceId())) return JoinOutcome.EnrollFailed
        provision(
            EventConfig(
                eventId = eventId,
                name = name,
                minPhotoDate = clampToFloor(chosen = minPhotoDate, startsAt = startsAt),
                startsAt = startsAt,
                direction = direction,
                saveToAlbum = saveToAlbum,
            ),
        )
        return JoinOutcome.Committed
    }
}
