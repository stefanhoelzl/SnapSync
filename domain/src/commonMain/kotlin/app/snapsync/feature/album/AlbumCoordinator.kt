package app.snapsync.feature.album

import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AlbumMapStore
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
     * Ensure [eventId]'s album exists and return its `localIdentifier` (or `null` on a creation failure
     * — or when the membership never asked for one). Callers call **unconditionally**: the membership's
     * opt-in gate is the leading guard here, so no caller can forget it — [saveToAlbum] off (or an
     * empty [name], which cannot title an album) is a silent no-op, exactly the rule the app shell's
     * `ensureAlbumIfOptedIn` helper used to hold (migration step 8 C3). [granted] joined that guard at
     * the migration finale: an album can only be ensured with photo access fully granted, so the
     * Provision flow passes the access fact instead of branching on it (the flow coordinates, the
     * feature decides); it defaults to `true` for the paths that run *because* access was granted
     * (the compose-installed grant subscription).
     * Reuses the stored album if it still resolves (so a re-join keeps the prior membership's photos);
     * recreates and overwrites the map if the stored id is dangling (the user deleted the album).
     * **App-only** — the sole-creator invariant that removes the cross-process create race (design D3).
     */
    suspend fun ensureAlbum(eventId: String, name: String, saveToAlbum: Boolean, granted: Boolean = true): String? {
        if (!granted || !saveToAlbum || name.isEmpty()) return null
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
     * The album `localIdentifier` the current membership's adds go to, or `null` when it opted out
     * ([saveToAlbum] off) or no album was ever created — the same opt-in rule [ensureAlbum] gates on,
     * exposed synchronously because the download importer reads it **inside** its atomic PhotoKit
     * change block (capability `event-album`, design D5/D9: the import-time add borrows the map
     * lookup, it does not route through [place]).
     */
    fun albumIdFor(eventId: String, saveToAlbum: Boolean): String? =
        if (saveToAlbum) store.get(eventId) else null

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
