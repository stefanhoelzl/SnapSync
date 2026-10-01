package app.snapsync.compose

import app.snapsync.model.runCatchingCancellable
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.feature.membership.EventCompletion
import app.snapsync.services.leave.PendingLeaves

/**
 * How a membership ends without the member (capability `manage-membership`, "The app leaves on its own once the event
 * is finished for it"), and how every leave reaches the backend (capability `event-lifetime`, "A leave made offline
 * still counts") — composed over [core], and a piece of its graph of its own for the reason [AppTail] is: `AppCore` is
 * already the graph, and its size ceiling says so.
 */
class MembershipEnd internal constructor(private val core: AppCore) {

    /** The leaves the backend has not confirmed — see [PendingLeaves]. */
    val pendingLeaves: PendingLeaves by lazy {
        PendingLeaves(core.process.files, core.backend.leave, core.services.log)
    }

    /**
     * The end of every wake — see [EventCompletion]. Run once after each wake's tail ([AppTail]), outside it: it may
     * leave the event, and a leave must never run inside the tail it would stop.
     */
    val completion: EventCompletion by lazy {
        EventCompletion(
            config = core.services.config,
            refresh = core.membershipRefresh,
            leaveEvent = core.leaveEvent,
            directory = core.backend.directory,
            manifestRecord = core.services.manifestStore,
            ledger = core.services.ledger,
            pendingLeaves = pendingLeaves,
            // The uploader's own discovery → publish, which marks the manifest settled once the range has ended. Under
            // a partial grant it reads the selection snapshot, never the library (capability `photo-access`).
            publishFinal = { core.appUploader.walkAndPublish { false } },
            everythingReceived = { eventId -> core.downloadController.everythingReceived(eventId) },
            checks = core.services.eventChecks,
            log = core.services.log,
        )
    }

    /**
     * The end of every wake whose tail covered the whole pass (capabilities `receiving-photos` and `manage-membership`;
     * decision record `changes/timely-background-receiving`, D4–D5): first the **bounded photo check** — the union
     * read, at most once an hour per event, so others' photos arrive when no push does — then the event-completion
     * step, whose read of the event's state is bounded the same way unless [trigger] is one that asks the event anyway:
     * a push (the close is announced by one), an opening, a join.
     *
     * An arm's tail runs no photo check: an arm is requested from inside a join or a reconfigure, which read the union
     * in their own work, and the tail can end before that read has stamped the hour — one union read too many per join.
     */
    suspend fun endOfWake(trigger: TailTrigger) {
        core.services.config.config.value?.eventId?.takeIf { trigger != TailTrigger.ARM }?.let { eventId ->
            runCatchingCancellable { core.downloadController.reconcileIfDue(eventId) }
                .onFailure { core.services.log.w(it) { "the bounded photo check failed; the next wake runs it again" } }
        }
        completion.finish(bounded = trigger !in ASKS_THE_EVENT)
    }

    /**
     * The backend-leave effect: recorded first (idempotent — the leave command recorded it already), then every
     * outstanding leave is sent. One the backend does not confirm stays recorded, and the next wake's [completion]
     * sends it again — a finished event is deleted once everyone has LEFT, so a lost leave is no longer harmless.
     */
    suspend fun notifyLeave(eventId: String) {
        // A re-join asks the event at once: its bounded background checks start over.
        core.services.eventChecks.clear(eventId)
        pendingLeaves.record(eventId)
        val outstanding = pendingLeaves.deliverAll()
        if (outstanding > 0) {
            core.services.log.i { "leave of $eventId not confirmed yet — $outstanding retried on the next wake" }
        }
    }
}

/** The triggers whose own reason is to ask the event now — their end-of-wake read of its state is not bounded. */
private val ASKS_THE_EVENT = setOf(TailTrigger.SILENT_PUSH, TailTrigger.FOREGROUND, TailTrigger.ARM)
