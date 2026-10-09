package app.snapsync.services.downloads

import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import co.touchlab.kermit.Logger

/**
 * What an import's two platform callbacks write: the [downloads] store's guarded marker
 * writes, persisted inline on the platform's delivering thread — the placeholder inside the change block, the settle
 * from the completion, which runs even when the requester is gone. Non-suspending, like the writes, because both
 * callers are PhotoKit blocks.
 */
class ImportMarkers(private val downloads: DownloadService, private val log: Logger) {

    /**
     * The placeholder [id] for [ref], written before the asset's creation commits. A write that lands on no row means
     * the row was pruned out from under this import, so the asset being created has no suppression handle and this
     * device would upload a downloaded photo back into the event — logged at `Error`, so it reaches crash reporting:
     * this line is the only evidence.
     */
    fun placeholder(ref: AssetRef, id: AssetId) {
        if (!downloads.recordCreatedLocalId(ref, id)) {
            log.e { "import: marker $id for ${ref.sourceAssetId} landed on NO ROW — its row was pruned mid-import" }
        }
    }

    /** The import's outcome: an imported asset confirms its marker, a failed one clears the placeholder it left. */
    fun settled(ref: AssetRef, outcome: ImportResult) {
        when (outcome) {
            is ImportResult.Imported -> downloads.confirmCreatedLocalId(ref, outcome.createdLocalId)
            is ImportResult.Failed -> outcome.placeholder?.let { downloads.clearCreatedLocalId(ref, it) }
        }
    }
}
