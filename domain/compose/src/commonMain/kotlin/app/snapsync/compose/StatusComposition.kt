package app.snapsync.compose

import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.store.ChangedElsewhere
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.query.sharing.SharingProgressQuery
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

    /**
     * [sharing] in the shape the status host reads until it reads the query itself — a bridge this phase's vocabulary
     * step deletes.
     */
    val syncStatus: SyncStatusSource by lazy {
        object : SyncStatusSource {
            override val status: StateFlow<SyncStatus> =
                combine(sharing.state, core.galleryAccess.grant) { read, grant ->
                    when (read) {
                        SharingProgressRead.NotYetRead -> SyncStatus.Loading
                        is SharingProgressRead.Read -> SyncStatus.Ready(
                            SyncProgress(
                                pending = read.progress.handedOver,
                                completed = read.progress.shared,
                                total = read.progress.total,
                                failed = 0,
                                active = grant.grantsPhotoAccess,
                                estimatedRemaining = null,
                            ),
                        )
                    }
                }.stateIn(core.scope, SharingStarted.WhileSubscribed(), SyncStatus.Loading)
        }
    }
}
