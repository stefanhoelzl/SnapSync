package app.snapsync.feature.support

import app.snapsync.domain.event.EventId
import app.snapsync.services.downloads.RecognitionSource

/**
 * A download store's recognition read in which exactly [eventIds] have been recognised (capability `receiving-photos`):
 * for a feature that only asks whether a membership may be counted or shared yet.
 */
class Recognised(private val eventIds: List<EventId> = emptyList()) : RecognitionSource {
    override suspend fun isRecognised(eventId: EventId): Boolean = eventId in eventIds
}
