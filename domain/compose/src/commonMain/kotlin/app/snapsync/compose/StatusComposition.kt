package app.snapsync.compose

import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.store.ChangedElsewhere
import app.snapsync.query.sharing.SharingProgressQuery

/**
 * The joined screen's status over [core] (`docs/patterns.md`): the member's admitted photos and the use case that
 * counts them, the sharing progress followed from the shared database, and what another process's commit is announced
 * on — each built once, on first touch.
 */
internal class StatusLayers(private val core: AppCore) {
    /** The total `N`, as last counted. */
    val admitted: AdmittedPhotos = AdmittedPhotos()

    /** The count of the total, a step of the opening, a join, a settings change and a selection change. */
    val count: CountOwnPhotos by lazy {
        CountOwnPhotos(core.services.membership, core.receiving.received, core.ownPhotos, admitted)
    }

    /** How far the member's photos are shared. */
    val sharing: SharingProgressQuery by lazy {
        SharingProgressQuery(
            core.foreground,
            core.services.sharedDatabase.changes,
            core.services.membership,
            core.appUploader.progress,
            admitted,
            core.scope,
        )
    }

    /** The app's stores, as the `Databases` port names them when the upload extension committed to one. */
    val changedElsewhere: ChangedElsewhere by lazy {
        ChangedElsewhere(core.services.sharedDatabase, core.receiving.file)
    }
}
