package app.snapsync.compose

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
