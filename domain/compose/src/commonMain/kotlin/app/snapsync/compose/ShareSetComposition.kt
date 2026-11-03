package app.snapsync.compose

import app.snapsync.entrypoints.OnPhotoGrantUsable
import app.snapsync.feature.membership.ShareSetLoad
import app.snapsync.services.backend.DeviceFilesSource

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
 * The app's answer to a changed photo grant: the received photos an earlier install left are recognised
 * ([OnPhotoGrantUsable]), and the uploads follow the grant. Neither waits for the other: nothing is shared, and
 * nothing imported, before the membership is recorded recognised. A top-level extension because `AppCore` is measured
 * (see [shareSetLoadFor]).
 */
internal suspend fun AppCore.onGrantChanged() {
    work.photoGrantUsable.requested()
    uploadTransitions.onPermissionChanged()
}
