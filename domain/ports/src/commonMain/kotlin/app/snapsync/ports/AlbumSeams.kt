package app.snapsync.ports

import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionCalibration

/**
 * The album operations the event album and the denylist need (capabilities `event-album`, `photo-sharing`).
 * Implemented by `GalleryAlbums` over the [GalleryReader]; the orchestration lives in [AlbumCoordinator].
 *
 * All operations are **best-effort** and only ever mutate collections — never the assets themselves.
 */
interface AlbumManager {
    /**
     * Create a new album titled [name] and return its id, or `null` if creation failed. **Only the app**
     * calls this (it is the sole creator; see [AlbumCoordinator]).
     *
     * Absence: null means creation failed, whatever the cause, and the membership then files nothing
     * into an album the member explicitly opted into — silently. That is the ONE verdict in this
     * inventory recorded as unsatisfying rather than safe: the causes share a consequence, so the
     * collapse is legal, but whether the consequence itself is acceptable under an explicit
     * `saveToAlbum` opt-in is an open product question (decision record:
     * `changes/archive/…-absence-is-never-silent`, Open Questions).
     */
    suspend fun ensureCreated(name: String): String?

    /** Whether an album with [albumLocalId] still resolves (the user may have deleted it). */
    suspend fun exists(albumLocalId: String): Boolean

    /**
     * Add the library assets [assetIds] (the gallery's asset ids, as the ledger and the download store carry
     * them) to the album [albumLocalId]. Best-effort: a missing asset is skipped, adding an already-present
     * asset is a no-op.
     */
    suspend fun add(albumLocalId: String, assetIds: List<String>)

    /**
     * The asset ids of every asset in a **user album** the [calibration] denies, captured at or after [since].
     *
     * Matching is on **user albums by title only**. A smart album's title is system-localized ("Screenshots"
     * / "Bildschirmfotos"), so title-matching one is meaningless — screenshots are excluded by their own fact.
     * Cost is proportional to the number of albums, **not** the number of assets; it must never become a
     * per-asset membership test.
     */
    suspend fun assetIdsInAlbums(calibration: SelectionCalibration, since: CaptureCutoff): Set<String>
}

/**
 * The persisted `eventId → albumLocalId` map (capability `event-album`). It lives in a **shared** store
 * (App-Group / shared Keychain) readable and writable by both the app and the upload extension, and it
 * **survives `LeaveEvent.leave()`** (unlike the event config) so a re-join reuses the same album. The
 * iOS impl is `AlbumMapService` over the App-Group preferences; a fake/in-memory impl backs the tests.
 */
interface AlbumMapStore {
    /**
     * The stored album `localIdentifier` for [eventId], or `null` if none was ever created.
     *
     * Absence: null covers "never created" and "map unreadable" alike — both send the coordinator
     * down the ensure-then-remember path, which is why this map is described as a self-healing
     * cache. A wrong null costs one redundant lookup, never a lost photo.
     */
    fun get(eventId: String): String?

    /** Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). */
    fun put(eventId: String, albumLocalId: String)
}
