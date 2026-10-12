package app.snapsync.feature.membership

import app.snapsync.model.contained
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.backend.ReceivedReporter
import app.snapsync.services.config.ConfigService
import app.snapsync.services.leave.PendingLeaves
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
import co.touchlab.kermit.Logger

/**
 * The end of a wake, for an event whose range has ended — this device tells the event when it is done with it, so
 * the event's photos are deleted as soon as every member has them (decision record
 * `changes/back-dated-events`, D4 and D7). The app **never leaves an event on its own** here: a finished event stays
 * joined, and one whose photos the server confirms deleted is marked so ([MembershipRefresh]) and stays too.
 *
 * Every wake the app gets — a foreground, a silent push, the heartbeat, a transfer relaunch — ends in the tail, and
 * the tail's end runs [finish] once. It does, in order:
 *
 * 1. **Delivers the leaves the backend has not confirmed** ([PendingLeaves]) — a leave made offline must still count.
 * 2. Nothing more while the event's range has not ended — nobody can be done before it — or once its photos are
 *    known to be deleted.
 * 3. **Settles this device's share** ([publishFinal]) when its last published manifest is not yet marked final — the
 *    upload cycle marks it once the range has ended, but under a partial grant the tail itself publishes nothing, so
 *    this is what makes sure every device eventually says "settled".
 * 4. **Reads the event's state** ([EventDirectory]) and folds it through the one [MembershipRefresh] rule — which marks
 *    a deleted event. A **bounded** step — a wake that is not a push, an opening or a join — reads it at most once an
 *    hour per event ([EventChecks]; decision record `changes/timely-background-receiving`, D5).
 * 5. **Reports up to which union position this device holds every photo of the others** ([receivedPosition] →
 *    [reporter]), when it does. A higher position than the last one reported only: the backend keeps the
 *    highest, so repeating one changes nothing.
 *
 * Every doubt resolves toward reporting nothing — a failed read, an unreadable record — and the next wake repeats.
 */
class EventCompletion(
    private val config: ConfigService,
    private val refresh: MembershipRefresh,
    /** The event's details (`GET /events/:id`), sealed: completed, open, gone, or could not tell. */
    private val directory: EventDirectory,
    /** The last manifest this device published, as recorded: `"<eventId> <json>"`. */
    private val manifestRecord: DeviceManifestService,
    private val pendingLeaves: PendingLeaves,
    /** Publish this device's manifest once more — the sibling uploader's discovery → publish (feature-blindness). */
    private val publishFinal: suspend () -> Unit,
    /**
     * The union position up to which this device holds every photo of the others, or `null` when it does not — the
     * sibling download controller's answer (feature-blindness).
     */
    private val receivedPosition: suspend (eventId: String) -> Long?,
    /** Tells the backend that position. */
    private val reporter: ReceivedReporter,
    /** When a wake last read the event's state — every read stamps it; a [finish] that is bounded honours it. */
    private val checks: EventChecks,
    private val log: Logger = Logger.withTag("EventCompletion"),
) {
    /** The last position the backend recorded for an event, this process — so an unchanged one is not sent again. */
    private val reported = mutableMapOf<String, Long>()

    /**
     * The end of every wake whose tail covered the whole pass (decision record `changes/timely-background-receiving`,
     * D4–D5): first — when [checksPhotos] — the **bounded photo
     * check** for the joined event ([photoCheck], the union read at most once an hour per event, so others' photos arrive
     * when no push does), contained; then [finish], [bounded] as the wake's trigger says.
     */
    suspend fun endOfWake(
        checksPhotos: Boolean,
        bounded: Boolean,
        photoCheck: suspend (eventId: String) -> Unit,
    ): CompletionOutcome {
        config.config.value?.takeIf { checksPhotos && !it.deleted }?.let { joined ->
            log.contained("the bounded photo check failed; the next wake runs it again") { photoCheck(joined.eventId) }
        }
        return finish(bounded)
    }

    /**
     * Run the end-of-wake step — see the class. [bounded] for a wake that is not a push, an opening or a join: it reads
     * the event's state only when none was read within the hour. Never throws but for cancellation.
     */
    suspend fun finish(bounded: Boolean = false): CompletionOutcome {
        pendingLeaves.deliverAll() // never raises: what it cannot read or confirm waits for the next wake
        val current = config.config.value ?: return CompletionOutcome.NOT_JOINED
        if (current.deleted) return CompletionOutcome.DELETED
        if (!config.hasEnded(current)) return CompletionOutcome.NOT_ENDED
        val eventId = current.eventId

        if (published(eventId)?.final != true) {
            runCatchingCancellable { publishFinal() }.onFailure {
                log.w(
                    it,
                ) { "settling the share failed; next wake retries" }
            }
        }

        if (bounded && !checks.due(EventCheck.CLOSE, eventId)) {
            log.i { "the event's state was read within the hour — this wake reads none" }
            return CompletionOutcome.WAITING
        }
        checks.stamp(EventCheck.CLOSE, eventId)
        when (refresh.refresh(eventId, directory.fetch(eventId).toJoinLoad())) {
            RefreshOutcome.COMPLETED, RefreshOutcome.ABSENT -> return CompletionOutcome.DELETED
            RefreshOutcome.INCONCLUSIVE, RefreshOutcome.REFRESHED -> Unit
        }

        val position = runCatchingCancellable { receivedPosition(eventId) }.getOrNull()
            ?: return CompletionOutcome.WAITING
        // The read above takes time — the union's is a full one, over the network — and a member's own join or leave
        // can land meanwhile: a report is only ever about the membership this step found.
        if (config.config.value?.eventId != eventId || position <= (reported[eventId] ?: -1L)) {
            return CompletionOutcome.WAITING
        }
        if (reporter.report(eventId, position)) {
            reported[eventId] = position
            log.i { "event $eventId: this device holds every photo of the others up to $position" }
            return CompletionOutcome.REPORTED
        }
        return CompletionOutcome.WAITING
    }

    /** The last published manifest for [eventId], or `null` when none is recorded for it or it cannot be read. */
    private fun published(eventId: String) = manifestRecord.loadLastUploaded()
        ?.takeIf { it.eventId == eventId }
        ?.let { runCatchingCancellable { deviceManifestFromJson(it.manifest) }.getOrNull() }
}

/** What [EventCompletion.finish] found. Only [DELETED] changed anything the member sees. */
enum class CompletionOutcome {
    /** No membership. */
    NOT_JOINED,

    /** The event's range has not ended (or its end is not known yet): nobody can be done with it. */
    NOT_ENDED,

    /**
     * Ended, and nothing reported — this device does not hold everything yet, the read was bounded or failed, the
     * position was already reported, or the membership was left or replaced while the step checked it.
     */
    WAITING,

    /** This device told the backend up to which position it holds every photo of the others. */
    REPORTED,

    /** The event's photos are deleted: the membership is marked so, and stays. */
    DELETED,
}
