package app.snapsync.compose

import app.snapsync.model.ImportResult
import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.GalleryHandlers
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.SendChannel

/**
 * What the app's gallery tells the core (`docs/architecture.md`, the handler table) — built here and only here
 * (`ListenDoorTest`), registered by the host zone's `listen` as the graph is composed, so an import finishing in a
 * background wake finds them. Building them builds no feature:
 *
 *  - `onChanged` only hands the snapshot to the core's conflated [selection] channel, which host assembly consumes;
 *  - the import handlers are the [downloads] store's guarded marker writes, persisted inline on the platform's
 *    delivering thread — the placeholder inside the change block, the settle from the completion, which runs even
 *    when the requester is gone.
 */
internal fun galleryHandlers(
    downloads: DownloadStore,
    log: Logger,
    selection: SendChannel<SelectionSnapshot>,
): GalleryHandlers = GalleryHandlers(
    onChanged = { snapshot -> selection.trySend(snapshot) },
    onImportPlaceholder = { ref, id ->
        // `false`: the row was pruned out from under this import, so the asset being created has no suppression
        // handle and this device would upload a downloaded photo back into the event (capability
        // `receiving-photos`). Error, so it reaches crash reporting: this line is the only evidence.
        if (!downloads.recordCreatedLocalId(ref, id)) {
            log.e { "import: marker $id for ${ref.sourceAssetId} landed on NO ROW — its row was pruned mid-import" }
        }
    },
    onImportSettled = { ref, outcome ->
        when (outcome) {
            is ImportResult.Imported -> downloads.confirmCreatedLocalId(ref, outcome.createdLocalId)
            is ImportResult.Failed -> outcome.placeholder?.let { downloads.clearCreatedLocalId(ref, it) }
        }
    },
)
