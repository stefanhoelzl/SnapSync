package app.snapsync.compose

import app.snapsync.domain.upload.UploaderChange
import app.snapsync.entrypoints.OnPhotoGrantUsable

/**
 * The app's answer to a changed photo grant: the received photos an earlier install left are recognised
 * ([OnPhotoGrantUsable]), and the uploads follow the grant. Neither waits for the other: nothing is shared, and
 * nothing imported, before the membership is recorded recognised. A top-level extension because `AppCore` is measured:
 * the `compose` tier's `LargeClass` ceiling is what keeps that class from absorbing every composition in the graph.
 */
internal suspend fun AppCore.onGrantChanged() {
    work.photoGrantUsable.requested()
    work.uploaderChange.deliver(UploaderChange.GRANT_CHANGED)
}
