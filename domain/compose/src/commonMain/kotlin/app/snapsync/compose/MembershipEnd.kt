package app.snapsync.compose

import app.snapsync.feature.membership.EventCompletion
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.services.leave.PendingLeaves

/**
 * How a membership ends without the member — the app leaves on its own once the event is finished for it — and how
 * every leave reaches the backend, so a leave made offline still counts — composed over [core], and a piece of its
 * graph of its own for the reason [AppTail] is: `AppCore` is already the graph, and its size ceiling says so.
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
            // a partial grant it reads the selection snapshot, never the library.

            publishFinal = { core.appUploader.walkAndPublish { false } },
            everythingReceived = { eventId -> core.downloadController.everythingReceived(eventId) },
            checks = core.services.eventChecks,
            log = core.services.log,
        )
    }

    /**
     * The end of every wake whose tail covered the whole pass — [EventCompletion.endOfWake], with [trigger]'s own
     * facts:
     * whether it runs the bounded photo check (the download arm's union read), and whether its read of the event's
     * state is bounded.
     */
    suspend fun endOfWake(trigger: TailTrigger) {
        completion.endOfWake(trigger.checksPhotos, trigger.boundsEventRead) { eventId ->
            core.downloadController.reconcileIfDue(eventId)
        }
    }

    /**
     * The backend-leave effect: a re-join asks the event at once, so its bounded background checks start over; then
     * the leave is recorded and every outstanding one sent ([PendingLeaves.leave]).
     */
    suspend fun notifyLeave(eventId: String, received: Boolean) {
        core.services.eventChecks.clear(eventId)
        pendingLeaves.leave(eventId, received)
    }
}
