package app.snapsync.services.downloads

import app.snapsync.domain.event.EventId

/**
 * The download store's recognition read (`changes/archive/2026-10-11-close-share-back-gaps`): whether a
 * membership's received photos have been recognised. Until they have, nothing of the membership may be shared — a
 * received photo no pass has recognised would count as the member's own. Implemented by both processes' views of the
 * store: [Recognition] in the app, [SuppressionService] (read-only) in the extension. Never "yes" from a store it could
 * not read.
 */
interface RecognitionSource {
    suspend fun isRecognised(eventId: EventId): Boolean
}
