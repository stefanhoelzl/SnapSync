package app.snapsync.services.gallery

import app.snapsync.data.receiving.HandlesRead
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.receiving.ReceivedReadiness
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.domain.event.EventId

/** A device that has received nothing through SnapSync: no photo of the library is anyone else's. */
internal object NothingReceived : ReceivedPhotosRead {
    override suspend fun readiness(): ReceivedReadiness = ReceivedReadiness.Ready

    override suspend fun handles(): HandlesRead = HandlesRead.Ok(emptySet())

    override suspend fun recognition(event: EventId): RecognitionRead = RecognitionRead.NotRecognised
}
