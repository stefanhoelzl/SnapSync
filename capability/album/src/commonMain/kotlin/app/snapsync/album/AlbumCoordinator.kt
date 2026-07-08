package app.snapsync.album

import co.touchlab.kermit.Logger

/**
 * The tested `commonMain` orchestration for the event album (capability `event-album`): resolve-or-create
 * the album (reuse across re-join, recreate a deleted one) and dispatch-or-skip an add. All album
 * *decisions* live here so `:test:world` can assert them without PhotoKit; the raw `PHAssetCollection`
 * calls live behind [AlbumManager] and the shared map behind [AlbumMapStore].
 *
 * Ownership: **the app is the sole creator** — only the app calls [ensureAlbum] (on the photo-permission
 * grant). Both the app and the extension call [place] (at upload completion / import), which only ever
 * *adds* to an already-created album. The download path does its add atomically inside the importer's own
 * commit (design D5/D9) and only borrows [AlbumMapStore.get]; it does not route through [place].
 */
class AlbumCoordinator(
    private val manager: AlbumManager,
    private val store: AlbumMapStore,
    private val log: Logger = Logger.withTag("AlbumCoordinator"),
) {

    /**
     * Ensure [eventId]'s album exists and return its `localIdentifier` (or `null` on a creation failure).
     * Reuses the stored album if it still resolves (so a re-join keeps the prior membership's photos);
     * recreates and overwrites the map if the stored id is dangling (the user deleted the album).
     * **App-only** — the sole-creator invariant that removes the cross-process create race (design D3).
     */
    suspend fun ensureAlbum(eventId: String, name: String): String? {
        store.get(eventId)?.let { existing ->
            if (manager.exists(existing)) {
                log.i { "ensureAlbum: reused album=$existing for event=$eventId" }
                return existing
            }
            log.i { "ensureAlbum: stored album for event=$eventId no longer resolves — recreating" }
        }
        val created = manager.ensureCreated(name)
        if (created == null) {
            log.w { "ensureAlbum: album creation failed for event=$eventId" }
            return null
        }
        store.put(eventId, created)
        log.i { "ensureAlbum: created album=$created named='$name' for event=$eventId" }
        return created
    }

    /**
     * Add [rawLocalIds] to [eventId]'s album, best-effort. If no album exists yet (the app has not created
     * it), the add is **skipped** (never created here) — the app's [ensureAlbum] on the permission grant
     * guarantees the album exists before sync in practice. A failure to add is logged, never thrown.
     */
    suspend fun place(eventId: String, rawLocalIds: List<String>) {
        if (rawLocalIds.isEmpty()) return
        val albumId = store.get(eventId)
        if (albumId == null) {
            log.i { "place: no album yet for event=$eventId — skipping ${rawLocalIds.size} asset(s)" }
            return
        }
        runCatching {
            manager.add(albumId, rawLocalIds)
            log.i { "place: added ${rawLocalIds.size} asset(s) to album=$albumId for event=$eventId" }
        }.onFailure { log.w(it) { "place: add to album failed for event=$eventId" } }
    }
}
