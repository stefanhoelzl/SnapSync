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
 * The real [SyncStatusSource]. Completeness and classification are **own-device storage truth** — the
 * complete-asset set ([CompletedAssetsSource] — gallery enumeration × the per-device file listing),
 * permission, and the live gallery size. The **in-flight caption** ([InFlightSource]) is a separate,
 * read-only peek at the ledger's "uploading now" count; it feeds `pending` only and never drives
 * classification. The source reads **no device manifest**, and reads the ledger **only** through the
 * in-flight count.
 *
 * Like any source backed by an asynchronous first read, the factory does NOT suspend: it seeds
 * [SyncStatus.Loading] and, on [scope], collects the four inputs combined, emitting [SyncStatus.Ready]
 * once the completed-assets count, permission, **and** gallery size have each produced a first value
 * (the in-flight count seeds `0` and never gates the first `Ready`) and on every change after. Each
 * minted [SyncProgress] sets `completed` = the complete-asset count, `total` = the gallery size,
 * `pending` = `min(inFlight, total − completed)` — the in-flight count **clamped to remaining** so a
 * stale/over-counting ledger never reads above the remaining count — `active = (permission ==
 * GRANTED)`, `failed = 0`, and `estimatedRemaining = null`. Liveness is event-driven: the iOS
 * composition root refreshes the completed source **and** the in-flight source on foreground entry.
 */
fun ListingSyncStatusSource(
    completed: CompletedAssetsSource,
    permission: PermissionStatusSource,
    gallery: GalleryStatusSource,
    inFlight: InFlightSource,
    scope: CoroutineScope,
): SyncStatusSource {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    scope.launch {
        combine(
            completed.completed,
            permission.permission,
            gallery.size,
            inFlight.inFlight,
        ) { complete, perm, total, inflightCount ->
            val completedCount = complete.size
            val remaining = (total - completedCount).coerceAtLeast(0)
            SyncProgress(
                // Real in-flight from the ledger, clamped to remaining (display-only — see SyncProgress).
                pending = minOf(inflightCount, remaining),
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
