package app.snapsync.services.gallery

import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.ports.GalleryImport
import app.snapsync.services.staging.StagingService

/**
 * **A foreign asset's import from staging** (capability `receiving-photos`): its staged resources, whose paths the
 * download store keeps relative to the shared area, are located on this device and handed to the photo library.
 *
 * Locating is this service's, not the caller's, because a staged path only means something on the device that staged
 * it — a restored device's container moved — and the library must be handed the platform's path. A shared area that
 * cannot be reached THROWS ([StagingService.locate]): importing from a directory nobody chose would lose the photo
 * without a trace, so the caller sees a throw, exactly as it sees one from the library call itself.
 */
class GalleryImporter(private val gallery: GalleryImport, private val staging: StagingService) {

    /**
     * Import [request], whose staged paths are relative. What the library reports is the answer — see
     * [GalleryImport.import] for what a throw does and does not promise.
     */
    suspend fun import(request: ImportRequest): ImportResult = gallery.import(
        ImportRequest(
            ref = request.ref,
            resources = request.resources.map { it.copy(stagedPath = staging.locate(it.stagedPath)) },
            creationDate = request.creationDate,
            album = request.album,
        ),
    )
}
