package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.GalleryResourceHandle

/**
 * **The mechanism a process creates its upload jobs through** — the app's own session ([SessionTier]) or the OS's
 * queue ([OsQueueTier]); both record a created job's row requested in the same step as the create.
 */
interface UploadTier {
    /** Make [row]'s upload into [event] a job sending [handle]'s bytes. */
    suspend fun create(event: EventId, row: UploadRow.Discovered, handle: GalleryResourceHandle): CreateAnswer
}
