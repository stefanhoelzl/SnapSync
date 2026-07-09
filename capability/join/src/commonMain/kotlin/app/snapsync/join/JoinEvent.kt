package app.snapsync.join

import app.snapsync.config.ConfigSource
import app.snapsync.config.Direction
import app.snapsync.config.EventConfig
import app.snapsync.deviceid.DeviceIdentity

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
    private val deviceIdentity: DeviceIdentity,
    private val details: EventDetailsSource,
    private val enroller: DeviceEnroller,
    private val provision: suspend (EventConfig) -> Unit,
) {

    /** Fetch the event's details for the confirmation gate (loading → loaded/not-found/failed). */
    suspend fun loadDetails(eventId: String): EventDetails = details.fetch(eventId)

    /**
     * Confirm the join for [eventId] with the loaded [name] (required, non-null — the gate only
     * provisions from a loaded phase that carries a name), this device's chosen capture-date
     * [minPhotoDate] cutoff (capability `photo-date-cutoff`; always present — a membership without a
     * cutoff would upload the whole library), its chosen
     * participation [direction] (capability `join-event`), and whether it opted into an event album
     * ([saveToAlbum], capability `event-album`): enroll (register-only empty manifest) — for
     * **every** direction, so a download-only device is still an enrolled member — then, only on a
     * successful enrollment, provision (save config **with the cutoff and direction**). The injected
     * [provision] enables the upload producer only when [Direction.includesUpload] and runs the download
     * reconcile only when [Direction.includesDownload] (the latter gated inside the download controller).
     * Re-confirming the already-joined event is a [JoinOutcome.AlreadyJoined] no-op that skips enrollment
     * entirely (the cutoff and direction stay immutable — a change is a leave-then-rejoin).
     */
    suspend fun join(
        eventId: String,
        name: String,
        minPhotoDate: String,
        direction: Direction,
        saveToAlbum: Boolean,
    ): JoinOutcome {
        if (configSource.config.value?.eventId == eventId) return JoinOutcome.AlreadyJoined
        if (!enroller.enroll(eventId, deviceIdentity.deviceId())) return JoinOutcome.EnrollFailed
        provision(
            EventConfig(
                eventId = eventId,
                name = name,
                minPhotoDate = minPhotoDate,
                direction = direction,
                saveToAlbum = saveToAlbum,
            ),
        )
        return JoinOutcome.Committed
    }
}
