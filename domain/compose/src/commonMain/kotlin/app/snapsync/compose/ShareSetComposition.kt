package app.snapsync.compose

import app.snapsync.feature.download.OthersDeclaredUnion
import app.snapsync.feature.membership.ReceivedPhotoAdoption
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.model.GalleryAccess
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.GalleryReader
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.DeviceFilesSource
import app.snapsync.services.gallery.MarkedPhotoLookup

/**
 * The join-time load over the app's ports: a provision into a new
 * membership makes the upload ledger its share set, from the device's stored-file listing ([files]).
 *
 * Composed in the APP on every tier. The load needs no `LedgerWriter`: `resetTo` and `clear` are the store's
 * reset family, owned by the membership use-cases, which a holder of the store may invoke whichever process's
 * cycle also records (decision record `changes/archive/2026-09-22-both-uploaders-active`).
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
 * The join-time adoption: the union over [backend], the download store, and the
 * library's marked photos over [gallery] under the same read plan as upload discovery ([AppCore.readPlan]),
 * written through the download controller's locked write. Every union it reads is offered to [AppCore.joinUnion], so
 * the provision it runs in plans its downloads from the same answer — read, as the controller reads it, net of this
 * device's own declaration ([OthersDeclaredUnion]). A top-level factory for the same reason as [shareSetLoadFor].
 */
internal fun receivedPhotoAdoptionFor(
    services: AppServices,
    backend: BackendServices,
    gallery: GalleryReader,
    core: AppCore,
): ReceivedPhotoAdoption = ReceivedPhotoAdoption(
    union = { eventId, cursor, trigger ->
        OthersDeclaredUnion(backend.union, services.manifestStore).union(eventId, cursor, trigger)
            .onSuccess { core.joinUnion.offer(eventId, it) }
    },
    store = services.downloadStore,
    library = MarkedPhotoLookup(gallery, core.selection),
    record = core.downloadController::settleAdopted,
    identity = services.deviceIdentity,
    log = services.log,
)

/**
 * The app's answer to a changed photo grant: the received photos an earlier install left are recognised first
 * ([ReceivedPhotoAdoption.ensureAdoptedUnder]), then the uploads follow the grant. A top-level extension because
 * `AppCore` is measured (see [shareSetLoadFor]).
 */
internal suspend fun AppCore.onGrantChanged(permission: GalleryAccess) {
    receivedPhotoAdoption.ensureAdoptedUnder(permission.grantsPhotoAccess, services.config.config.value)
    uploadTransitions.onPermissionChanged()
}

/**
 * Whether the download drain may import now: only under a usable grant, and only once
 * the joined membership's received photos have been recognised — which this runs, so every import path waits for it.
 */
internal suspend fun AppCore.importsReady(): Boolean =
    receivedPhotoAdoption.ensureAdoptedUnder(galleryAccess.usable, services.config.config.value)
