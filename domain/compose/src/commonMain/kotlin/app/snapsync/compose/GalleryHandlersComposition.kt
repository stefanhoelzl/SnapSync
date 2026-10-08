package app.snapsync.compose

import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.GalleryHandlers
import app.snapsync.services.downloads.ImportMarkers
import kotlinx.coroutines.channels.SendChannel

/**
 * What the app's gallery tells the core (`docs/architecture.md`, the handler table) — built here and only here
 * (`ListenDoorTest`), registered by the host zone's `listen` as the graph is composed, so an import finishing in a
 * background wake finds them. Building them builds no feature:
 *
 *  - `onChanged` only hands the snapshot to the core's conflated [selection] channel, which host assembly consumes;
 *  - the import handlers are the download store's guarded marker writes ([ImportMarkers]), persisted inline on the
 *    platform's delivering thread — the placeholder inside the change block, the settle from the completion, which
 *    runs even when the requester is gone.
 */
internal fun galleryHandlers(
    markers: ImportMarkers,
    selection: SendChannel<SelectionSnapshot>,
): GalleryHandlers = GalleryHandlers(
    onChanged = { snapshot -> selection.trySend(snapshot) },
    onImportPlaceholder = markers::placeholder,
    onImportSettled = markers::settled,
)
