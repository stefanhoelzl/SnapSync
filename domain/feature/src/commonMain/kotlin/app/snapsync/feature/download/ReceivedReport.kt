package app.snapsync.feature.download

import app.snapsync.domain.event.EventId
import app.snapsync.services.backend.ReceivedReporter
import app.snapsync.services.membership.MembershipProjection
import co.touchlab.kermit.Logger

/**
 * The end of a wake for what this device received: once the event's range has ended, tell the event up to which union
 * position this device holds every photo of the others — a member is done once it has them, and the event's photos are
 * deleted as soon as every member is (decision record `changes/back-dated-events`, D4 and D7). Only a higher position
 * than the last one reported: the backend keeps the highest. Every doubt reports nothing, and the next wake repeats.
 */
class ReceivedReport(
    private val config: MembershipProjection,
    /** The union position up to which this device holds every photo of the others, or `null` when it does not. */
    private val downloads: DownloadController,
    private val reporter: ReceivedReporter,
    private val log: Logger = Logger.withTag("ReceivedReport"),
) {
    /** The last position the backend recorded for an event, this process — so an unchanged one is not sent again. */
    private val reported = mutableMapOf<EventId, Long>()

    suspend fun report() {
        val current = config.config.value?.takeIf { !it.deleted && config.hasEnded(it) } ?: return
        val eventId = current.eventId
        val position = downloads.receivedPosition(eventId) ?: return
        // The read above takes time — the union's is a full one — and a member's own join or leave can land
        // meanwhile: a report is only ever about the membership this step found.
        if (config.activeEventId() != eventId || position <= (reported[eventId] ?: -1L)) return
        if (reporter.report(eventId, position)) {
            reported[eventId] = position
            log.i { "event $eventId: this device holds every photo of the others up to $position" }
        }
    }
}
