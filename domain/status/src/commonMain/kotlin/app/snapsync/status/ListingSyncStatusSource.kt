package app.snapsync.status

import app.snapsync.gallery.GalleryStatusSource
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The real [SyncStatusSource], derived from **storage truth** rather than the ledger: the
 * completeness listing ([CompletedAssetsSource]), the on-disk in-flight manifests
 * ([PendingManifestsSource]), permission, and the live gallery size — minted into snapshots. It reads
 * **no ledger**.
 *
 * Like any source backed by an asynchronous first read, the factory does NOT suspend: it seeds
 * [SyncStatus.Loading] and, on [scope], collects the four inputs combined, emitting
 * [SyncStatus.Ready] once the completed-assets count, permission, **and** gallery size have each
 * produced a first value (`combine` waits for every input) and on every change after. Each minted
 * [SyncProgress] sets `completed` = the listing's complete-asset count, `pending` = the in-flight
 * manifest count, `total` = the gallery size, `active = (permission == GRANTED)`, `failed = 0`, and
 * `estimatedRemaining = null`. Liveness is event-driven: the iOS composition root refreshes the
 * completed/pending sources on foreground entry and on each manifest `URLSession` completion.
 */
fun ListingSyncStatusSource(
    completed: CompletedAssetsSource,
    pending: PendingManifestsSource,
    permission: PermissionStatusSource,
    gallery: GalleryStatusSource,
    scope: CoroutineScope,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(
            completed.completed,
            pending.inFlight,
            permission.permission,
            gallery.size,
        ) { complete, inFlight, perm, total ->
            SyncProgress(
                pending = inFlight.size,
                completed = complete.size,
                total = total,
                failed = 0,
                active = perm == PermissionStatus.GRANTED,
                estimatedRemaining = null,
            )
        }.collect { status.value = SyncStatus.Ready(it) }
    }
    return object : SyncStatusSource {
        override val status: StateFlow<SyncStatus> = status
    }
}
