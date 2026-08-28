package app.snapsync.ports

import app.snapsync.model.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Selection snapshots under a **partial** photo grant (capability `limited-photo-access`): the full
 * current selection, as resources, emitted once when observation begins (the cold-launch baseline
 * read) and once per selection change (the in-app picker, a Settings-side edit, iCloud sync — the
 * platform's change observer does not distinguish, and neither does this seam).
 *
 * Emission contract:
 * - The source observes **only while the grant is partial** — under a full grant it emits nothing
 *   (the autonomous walks own liveness there, and an observer would add redundant reads).
 * - Each emission is the **whole current selection** (a snapshot, not a delta): platform change
 *   details are unreliable for bulk changes (measured — a batched create reports no itemized
 *   inserts), so consumers reload-and-dedup. The **ledger** is the dedup: an already-known asset in
 *   a snapshot costs one lookup, never a re-upload.
 * - Every read behind an emission is **in-flow** (the change callback / the launch read) — never an
 *   autonomous walk. That is the entire alert-safety argument, measured on device.
 *
 * The iOS adapter wraps `PHPhotoLibraryChangeObserver` (app-only surface); tests and the world use an
 * in-memory source. [PhotoSelectionChangeSource.None] is the inert default for compositions that
 * never see a partial grant (the world by default, the desktop harnesses).
 */
interface PhotoSelectionChangeSource {

    val snapshots: Flow<List<Resource>>

    companion object {
        /** The inert source: never emits (for compositions that never see a partial grant). */
        val None: PhotoSelectionChangeSource = object : PhotoSelectionChangeSource {
            override val snapshots: Flow<List<Resource>> = emptyFlow()
        }
    }
}
