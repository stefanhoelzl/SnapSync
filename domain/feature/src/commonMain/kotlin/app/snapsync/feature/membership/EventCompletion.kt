package app.snapsync.feature.membership

import app.snapsync.model.contained
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.config.ConfigService
import app.snapsync.services.leave.PendingLeaves
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
import co.touchlab.kermit.Logger

/**
 * The end of a wake, for an event that may be finished — the app leaves on its own once the event is finished for
 * it (decision record `changes/early-event-completion`, D3–D6).
 *
 * Every wake the app gets — a foreground, a silent push, the heartbeat, a transfer relaunch — ends in the tail, and
 * the tail's end runs [finish] once. It does, in order:
 *
 * 1. **Delivers the leaves the backend has not confirmed** ([PendingLeaves]) — a leave made offline must still
 *    count, or a finished event would wait for its clock.
 * 2. Nothing more while the event's range has not ended: before the end nothing can close, so no request is spent.
 * 3. **Settles this device's share** ([publishFinal]) when its last published manifest is not yet marked final — the
 *    upload cycle marks it once the range has ended, but under a partial grant the tail itself publishes nothing, so
 *    this is what makes sure every device eventually says "settled".
 * 4. **Reads the event's state** ([EventDirectory]) and folds it through the one [MembershipRefresh] rule: a
 *    completed event ends the membership there, and a closed one is recorded. A **bounded** step — a wake that is not
 *    a push, an opening or a join — reads it at most once an hour per event ([EventChecks]; decision record
 *    `changes/timely-background-receiving`, D5): the close is announced by a push, so this read only covers a lost one,
 *    and a member that leaves a closed event up to an hour late changes nothing anyone can see.
 * 5. **Leaves once the closed event has nothing left for this device**: every photo it shares has reached the event
 *    (the ledger holds no unfinished upload of an asset its final manifest declares) and every photo of the others it receives
 *    is in its library or was deleted there ([everythingReceived]). A photo that repeatedly fails to arrive keeps
 *    the member — the event's clock ends it instead.
 *
 * The leave is [LeaveEvent]'s, without a dialog and without anything shown: the next open lands on the create screen.
 * Every doubt resolves toward staying joined — a failed read, an unreadable record, an unconfirmed upload.
 */
class EventCompletion(
    private val config: ConfigService,
    private val refresh: MembershipRefresh,
    private val leaveEvent: LeaveEvent,
    /** The event's details (`GET /events/:id`), sealed: completed, closed, open, gone, or could not tell. */
    private val directory: EventDirectory,
    /** The last manifest this device published, as recorded: `"<eventId> <json>"`. */
    private val manifestRecord: DeviceManifestService,
    /** The upload ledger — which of this device's resources have not finished uploading. */
    private val ledger: LedgerService,
    private val pendingLeaves: PendingLeaves,
    /** Publish this device's manifest once more — the sibling uploader's discovery → publish (feature-blindness). */
    private val publishFinal: suspend () -> Unit,
    /**
     * Whether every photo of the others this device receives is in its library or was deleted there — the sibling
     * download controller's answer (feature-blindness).
     */
    private val everythingReceived: suspend (eventId: String) -> Boolean,
    /** When a wake last read the event's state — every read stamps it; a [finish] that is bounded honours it. */
    private val checks: EventChecks,
    private val log: Logger = Logger.withTag("EventCompletion"),
) {

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
        config.activeEventId()?.takeIf { checksPhotos }?.let { eventId ->
            log.contained("the bounded photo check failed; the next wake runs it again") { photoCheck(eventId) }
        }
        return finish(bounded)
    }

    /**
     * Run the end-of-wake step — see the class. [bounded] for a wake that is not a push, an opening or a join: it reads
     * the event's state only when none was read within the hour. Never throws but for cancellation.
     */
    suspend fun finish(bounded: Boolean = false): CompletionOutcome {
        runCatchingCancellable { pendingLeaves.deliverAll() }.onFailure { log.w(it) { "pending leaves not delivered" } }
        val current = config.config.value ?: return CompletionOutcome.NOT_JOINED
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
            RefreshOutcome.COMPLETED, RefreshOutcome.ABSENT -> return CompletionOutcome.LEFT
            RefreshOutcome.INCONCLUSIVE, RefreshOutcome.REFRESHED -> return CompletionOutcome.WAITING
            RefreshOutcome.CLOSED -> Unit
        }

        val final = published(eventId)?.takeIf { it.final } ?: return CompletionOutcome.WAITING
        val declared = final.assets.mapTo(mutableSetOf()) { it.assetId }
        val ownArrived = runCatchingCancellable { ledger.pendingResources().none { it.assetId in declared } }
            .getOrDefault(false)
        if (!ownArrived) return CompletionOutcome.WAITING
        val received = runCatchingCancellable { everythingReceived(eventId) }.getOrDefault(false)
        if (!received) return CompletionOutcome.WAITING
        // The reads above take time — the union's is a full one, over the network — and a member's own join or leave
        // can land meanwhile. The leave tears down whatever is configured, so it runs only while that is still the
        // membership this step found finished, never the one that replaced it.
        if (config.config.value?.eventId != eventId) {
            log.i { "event $eventId was left or replaced while its completion was checked — nothing to leave" }
            return CompletionOutcome.WAITING
        }

        log.i { "event $eventId is closed and this device has everything — leaving it" }
        leaveEvent.leave()
        return CompletionOutcome.LEFT
    }

    /** The last published manifest for [eventId], or `null` when none is recorded for it or it cannot be read. */
    private fun published(eventId: String) = manifestRecord.loadLastUploaded()
        ?.takeIf { it.startsWith("$eventId ") }
        ?.let { runCatchingCancellable { deviceManifestFromJson(it.substringAfter(' ')) }.getOrNull() }
}

/** What [EventCompletion.finish] found. Only [LEFT] changed anything the member sees. */
enum class CompletionOutcome {
    /** No membership. */
    NOT_JOINED,

    /** The event's range has not ended (or its end is not known yet): nothing can close. */
    NOT_ENDED,

    /**
     * Ended, and still waiting — for the others to settle, for the close, or for this device's own photos; or the
     * membership was left or replaced while the step checked it, and there is nothing of it left to leave.
     */
    WAITING,

    /** The membership ended: the event completed, or it closed and this device had everything. */
    LEFT,
}
