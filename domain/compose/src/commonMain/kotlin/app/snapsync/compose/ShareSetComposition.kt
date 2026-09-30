package app.snapsync.compose

import app.snapsync.feature.membership.ReceivedPhotoAdoption
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.model.SelectionScope
import app.snapsync.ports.GalleryReader
import app.snapsync.services.backend.DeviceFilesSource
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.gallery.MarkedPhotoLookup

/**
 * The join-time load (capability `photo-sharing`) over the app's ports: a provision into a new
 * membership makes the upload ledger its share set, from the device's stored-file listing ([files]).
 *
 * Composed in the APP on every tier. The load needs no `LedgerWriter`: `resetTo` and `clear` are the store's
 * reset family, owned by the membership use-cases, which a holder of the store may invoke whichever process's
 * cycle also records (capability `photo-sharing`, "Reader and writer capability split"; decision record
 * `changes/archive/2026-09-22-both-uploaders-active`).
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured: the `compose` tier's
 * `LargeClass` ceiling is what keeps that class from absorbing every composition in the graph.
 */
internal fun shareSetLoadFor(services: AppServices, files: DeviceFilesSource): ShareSetLoad = ShareSetLoad(
    files = files,
    ledger = services.ledger,
    identity = services.deviceIdentity,
    log = services.log,
)

/**
 * The join-time adoption (capability `receiving-photos`): the union over [union], the download store, and the library's
 * marked photos over [gallery] under the same read discipline as upload discovery ([selectionScope]). A top-level
 * factory for the same reason as [shareSetLoadFor].
 */
internal fun receivedPhotoAdoptionFor(
    services: AppServices,
    union: EventUnionSource,
    gallery: GalleryReader,
    selectionScope: () -> SelectionScope,
): ReceivedPhotoAdoption = ReceivedPhotoAdoption(
    union = union,
    store = services.downloadStore,
    library = MarkedPhotoLookup(gallery, selectionScope),
    identity = services.deviceIdentity,
    log = services.log,
)
