package app.snapsync.compose

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.upload.DeviceListing
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.entrypoints.OnPhotoGrantUsable
import app.snapsync.feature.membership.ShareSetLoad

/**
 * The join-time load over the app's ports: a provision into a new
 * membership makes the upload ledger its share set, from the device's stored-file listing (over [gateway]).
 *
 * Composed in the APP on every tier. The load needs no job record: `seed` and `purgeExcept` are the rows'
 * reset family, owned by the membership use-cases, which a holder of the store may invoke whichever process's
 * cycle also records (decision record `changes/archive/2026-09-22-both-uploaders-active`).
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured: the `compose` tier's
 * `LargeClass` ceiling is what keeps that class from absorbing every composition in the graph.
 */
internal fun shareSetLoadFor(services: AppServices, gateway: BackendGateway, reset: UploadRowsReset): ShareSetLoad =
    ShareSetLoad(
        listing = DeviceListing(gateway, services.deviceIdentity),
        reset = reset,
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
    work.uploaderChange.deliver(UploaderChange.GRANT_CHANGED)
}
