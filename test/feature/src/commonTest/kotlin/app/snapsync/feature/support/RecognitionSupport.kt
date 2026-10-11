package app.snapsync.feature.support

import app.snapsync.services.downloads.RecognitionSource

/**
 * A download store's recognition read in which exactly [eventIds] have been recognised (capability `receiving-photos`):
 * for a feature that only asks whether a membership may be counted or shared yet.
 */
class Recognised(private vararg val eventIds: String) : RecognitionSource {
    override suspend fun isRecognised(eventId: String): Boolean = eventId in eventIds
}
