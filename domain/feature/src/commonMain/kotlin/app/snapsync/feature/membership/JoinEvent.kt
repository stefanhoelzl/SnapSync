package app.snapsync.feature.membership

import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.event.EventId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinResult
import app.snapsync.model.TimestampText
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.config.ConfigService
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.identity.requireDeviceId
import co.touchlab.kermit.Logger

/**
 * The outcome of a confirmed join.
 * - [Committed]: enrolled and provisioned — the device is now joined.
 * - [AlreadyJoined]: the target is the currently-configured event — a no-op that does **not** re-enroll
 *   (protects a real asset manifest from the empty-manifest clobber; see the join-event spec).
 * - [EventClosed]: the event has closed and admits nobody — a refusal the USER can act on, kept apart from
 *   [EnrollFailed] because the two have different remedies and a screen must be able to say which
 *   (`docs/architecture.md`, "Absence is never silent"). Nothing is persisted either way.
 * - [Unverified]: the service did not accept this phone's credential — the attestation's refusal says why.
 * - [EnrollFailed]: the join request failed or the event is gone — nothing persisted, no producer enabled.
 * - [InvalidRange]: the chosen capture range breaks the share-range rule ([CaptureRange]) — refused before anything
 *   is enrolled. No shipped entry path can produce one, so the screen shows it as a failed join.
 */
sealed interface JoinOutcome {
    data object Committed : JoinOutcome

    data object AlreadyJoined : JoinOutcome

    data object EventClosed : JoinOutcome

    data object Unverified : JoinOutcome

    /** [fault] is how the service failed the join request — `null` for a failure on this device. */
    data class EnrollFailed(val fault: ServiceFault? = null) : JoinOutcome

    data object WrongLink : JoinOutcome

    data object InvalidRange : JoinOutcome
}

/**
 * What the join surface should show for this outcome.
 *
 * A pure mapping, here rather than in `compose/`, because deciding that a closed event and a transient
 * failure reach DIFFERENT screens — one offering a Retry, one deliberately not — is a rule, and a rule
 * in the wiring is a rule no test can reach. `AlreadyJoined` folds into [JoinCommit.Committed]: a
 * member who is already in the event is in the event, and re-confirming is a no-op, not a failure.
 */
fun JoinOutcome.toCommit(): JoinCommit = when (this) {
    JoinOutcome.Committed, JoinOutcome.AlreadyJoined -> JoinCommit.Committed
    JoinOutcome.EventClosed -> JoinCommit.Closed
    JoinOutcome.Unverified -> JoinCommit.Unverified
    is JoinOutcome.EnrollFailed -> JoinCommit.Failed(fault)
    JoinOutcome.InvalidRange -> JoinCommit.Failed()
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
    private val identity: DeviceIdentity,
    private val details: EventDirectory,
    private val enroller: DeviceEnroller,
    /** An encrypted event's key: checked against the event before a join, and kept once it succeeds. */
    private val keys: EventKeys,
    private val provision: suspend (EventConfig) -> Unit,
) {

    /** Fetch the event's details for the confirmation gate (loading → loaded/not-found/failed). */
    suspend fun loadDetails(eventId: EventId): EventLookup = details.fetch(eventId)

    /**
     * The confirmation gate's read: the event's details, and [JoinLoad.WrongLink] when the link's key ([linkKey]) does
     * not open it — so a link cut short in sharing is told before the member chooses anything, not after.
     */
    suspend fun loadJoin(
        eventId: EventId,
        linkKey: String?,
    ): JoinLoad = when (val load = loadDetails(eventId).toJoinLoad()) {
        is JoinLoad.Found -> if (keys.opens(linkKey, load.keyId)) load else JoinLoad.WrongLink
        else -> load
    }

    /**
     * Confirm the join of [choice]: the event with its loaded name (required, non-null — the gate only
     * provisions from a loaded phase that carries a name), the event's `startsAt` start date, this
     * device's chosen capture-date `minPhotoDate` cutoff (always present — a membership without a
     * cutoff would upload the whole library), its chosen participation `direction`, and whether it
     * opted into an event album (`saveToAlbum`): enroll (register-only empty manifest) — for **every** direction, so a
     * download-only device is still an enrolled member — then, only on a successful enrollment, provision
     * (save config **with the chosen range, the start date, and the direction**). The injected
     * [provision] enables the upload producer only when `Direction.includesUpload` and runs the download
     * reconcile only when `Direction.includesDownload` (the latter gated inside the download controller).
     * Re-confirming the already-joined event is a [JoinOutcome.AlreadyJoined] no-op that skips enrollment
     * entirely — re-*scanning* never rewrites config. Changing the cutoff, direction, or album opt-in of a
     * joined membership is done **in place** by `ReconfigureEvent`,
     * not by leaving and re-joining; only `startsAt` (the floor) stays immutable for the membership's life.
     *
     * **The share-range rule is applied here** ([CaptureRange]): the persisted range is the chosen one, unchanged,
     * or the join is refused as [JoinOutcome.InvalidRange] before anything is enrolled. Doing it in the use-case rather
     * than in the UI is what makes it total — **every** entry path funnels through this one call (the interactive
     * confirm, the switch confirm, the retry, and the rig's `autoJoin` path carrying a link-supplied range), so none of
     * them can skip it. It refuses rather than clamps: the calendar cannot produce a failing range, so one that arrives
     * is a bug, and moving it would join a range nobody chose.
     */
    private val log = Logger.withTag("JoinEvent")

    suspend fun join(choice: JoinChoice): JoinOutcome {
        val eventId = choice.eventId
        if (configSource.config.value?.eventId == eventId) return JoinOutcome.AlreadyJoined
        val range = CaptureRange.of(choice.minPhotoDate, choice.maxPhotoDate, choice.startsAt, choice.endsAt)
        if (range !is CaptureRangeVerdict.Ok) {
            log.e {
                val chosen = "${TimestampText.format(
                    choice.minPhotoDate.at,
                )}..${TimestampText.format(choice.maxPhotoDate.at)}"
                val event = "${TimestampText.format(choice.startsAt.at)}..${TimestampText.format(choice.endsAt.at)}"
                "join: refused — the range $chosen breaks the share-range rule ($range) for the event $event"
            }
            return JoinOutcome.InvalidRange
        }
        // Every entry path funnels here, the headless one included, so the key is checked here too — never only on
        // the screen: a member must not join an encrypted event it could not read, nor upload plaintext into it.
        if (!keys.opens(choice.linkKey, choice.eventKeyId)) return JoinOutcome.WrongLink
        // The key before the enrolment, and both before the config: a config naming a key id must never exist without
        // its key, and a refused write must fail the join before the event counts this device as a member — never
        // after, which would leave it enrolled at the event and joined to nothing (half-joined). A key kept with no
        // membership is inert (no membership names it), and the next join, leave or reset replaces or removes it. A
        // plain event's join removes a previous event's key.
        val kept = runCatchingCancellable { choice.linkKey?.let(keys::keep) ?: keys.forget() }
        if (kept.isFailure) {
            log.w(kept.exceptionOrNull()) { "join: the event key could not be kept — the join is retried" }
            return JoinOutcome.EnrollFailed()
        }
        when (val enrolled = enroller.enroll(eventId, identity.requireDeviceId())) {
            JoinResult.Joined -> Unit
            JoinResult.EventClosed -> return JoinOutcome.EventClosed
            JoinResult.Unverified -> return JoinOutcome.Unverified
            JoinResult.EventNotFound -> return JoinOutcome.EnrollFailed()
            is JoinResult.Failed -> return JoinOutcome.EnrollFailed(enrolled.fault)
        }
        provision(
            EventConfig(
                eventId = eventId,
                name = choice.name,
                minPhotoDate = range.range.from,
                startsAt = choice.startsAt,
                endsAt = choice.endsAt,
                maxPhotoDate = range.range.until,
                // Persisted verbatim from the loaded details, never computed here: it is the OFFLINE
                // witness of the self-leave, and a client-derived one would
                // decide whether this membership is later destroyed.
                deletesAt = choice.deletesAt,
                direction = choice.direction,
                saveToAlbum = choice.saveToAlbum,
                keyId = choice.eventKeyId,
                openUntil = choice.openUntil,
            ),
        )
        return JoinOutcome.Committed
    }
}
