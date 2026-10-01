package app.snapsync.compose

import app.snapsync.feature.membership.MembershipEntry
import app.snapsync.feature.membership.ReceivedPhotoAdoption
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.model.GalleryAccess
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.GalleryReader
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.DeviceFilesSource
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
 * The join-time adoption (capability `receiving-photos`): the union over [backend], the download store, and the
 * library's marked photos over [gallery] under the same read discipline as upload discovery ([AppCore.selectionScope]),
 * written through the download controller's locked write. Every union it reads is offered to [AppCore.joinUnion], so
 * the provision it runs in plans its downloads from the same answer. A top-level factory for the same reason as
 * [shareSetLoadFor].
 */
internal fun receivedPhotoAdoptionFor(
    services: AppServices,
    backend: BackendServices,
    gallery: GalleryReader,
    core: AppCore,
): ReceivedPhotoAdoption = ReceivedPhotoAdoption(
    union = { eventId -> backend.union.union(eventId).onSuccess { core.joinUnion.offer(eventId, it) } },
    store = services.downloadStore,
    library = MarkedPhotoLookup(gallery, core::selectionScope),
    record = core.downloadController::settleAdopted,
    identity = services.deviceIdentity,
    log = services.log,
)

/**
 * The app's answer to a changed photo grant. A grant that became usable while joined first recognises the photos an
 * earlier install received (capability `receiving-photos`) — BEFORE the uploads are armed and the staged downloads
 * imported, because a reinstall's rejoin always provisions with the access dialog still open (design
 * `mark-received-photos`, D4). A top-level extension because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal suspend fun AppCore.onGrantChanged(permission: GalleryAccess) {
    val joined = services.config.config.value?.takeIf { permission.grantsPhotoAccess }
    joined?.let { receivedPhotoAdoption.ensureAdopted(it) }
    uploadTransitions.onPermissionChanged()
}

/**
 * Whether the download drain may import now (capability `receiving-photos`): only under a usable grant, and only once
 * the joined membership's received photos have been recognised — which this runs, so every import path waits for it.
 */
internal suspend fun AppCore.importsReady(): Boolean {
    val usable = ports.photoAccess.permission.value.grantsPhotoAccess
    if (usable) services.config.config.value?.let { receivedPhotoAdoption.ensureAdopted(it) }
    return usable
}

/**
 * Entering a new membership (capabilities `join-event`, `photo-sharing`, `receiving-photos`): the order is
 * `MembershipEntry`'s rule — stop, backend leave, load, adopt, save, start uploads. [notifyLeave] is the composition's
 * best-effort leave, awaited here as it always was on this path. A top-level factory because `AppCore` is measured
 * (see [shareSetLoadFor]).
 */
internal fun AppCore.membershipEntry(notifyLeave: suspend (eventId: String) -> Unit): MembershipEntry = MembershipEntry(
    stopUploads = { uploadTransitions.onLeave() },
    notifyLeave = notifyLeave,
    loadShareSet = { shareSetLoad.load() },
    adoptReceived = { cfg -> receivedPhotoAdoption.adopt(cfg) },
    saveConfig = { cfg -> services.config.save(cfg) },
    startUploads = { uploadTransitions.onJoin() },
)
