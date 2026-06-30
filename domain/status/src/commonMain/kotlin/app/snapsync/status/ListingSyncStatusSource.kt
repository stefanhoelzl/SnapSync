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
 * The real [SyncStatusSource], derived from **own-device storage truth** rather than the ledger: the
 * complete-asset set ([CompletedAssetsSource] — gallery enumeration × the per-device file listing),
 * permission, and the live gallery size — minted into snapshots. It reads **no ledger** and **no
 * device manifest**.
 *
 * Like any source backed by an asynchronous first read, the factory does NOT suspend: it seeds
 * [SyncStatus.Loading] and, on [scope], collects the three inputs combined, emitting
 * [SyncStatus.Ready] once the completed-assets count, permission, **and** gallery size have each
 * produced a first value (`combine` waits for every input) and on every change after. Each minted
 * [SyncProgress] sets `completed` = the complete-asset count, `pending` = `total − completed` (the
 * qualifying assets not yet fully stored, clamped at 0), `total` = the gallery size, `active =
 * (permission == GRANTED)`, `failed = 0`, and `estimatedRemaining = null`. Liveness is event-driven:
 * the iOS composition root refreshes the completed source on foreground entry.
 */
fun ListingSyncStatusSource(
    completed: CompletedAssetsSource,
    permission: PermissionStatusSource,
    gallery: GalleryStatusSource,
    scope: CoroutineScope,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(
            completed.completed,
            permission.permission,
            gallery.size,
        ) { complete, perm, total ->
            val completedCount = complete.size
            SyncProgress(
                pending = (total - completedCount).coerceAtLeast(0),
                completed = completedCount,
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
