package app.snapsync.ports

/**
 * The platform seam for PhotoKit album (`PHAssetCollection`) operations (capability `event-album`).
 * Everything PhotoKit-specific lives behind it; the iOS impl (`IosAlbumManager`) is the only place that
 * touches `PHAssetCollectionChangeRequest`, so the orchestration in [AlbumCoordinator] stays pure and
 * testable on the simulator/JVM with a fake.
 *
 * All operations are **best-effort** and only ever mutate collections — never the assets themselves.
 */
interface AlbumManager {

    /**
     * Create a new album titled [name] and return its stable `localIdentifier`, or `null` if creation
     * failed. **Only the app** calls this (it is the sole creator; see [AlbumCoordinator]).
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
     * Add the library assets identified by [rawLocalIds] (raw PHAsset `localIdentifier`s) to the album
     * [albumLocalId]. Best-effort: a missing asset is skipped, adding an already-present asset is a no-op.
     */
    suspend fun add(albumLocalId: String, rawLocalIds: List<String>)

    /**
     * The **normalized** asset ids (`'/'→'_'`, as the ledger and upload keys carry them) of every asset that
     * belongs to a **user album** whose title matches one of [titles], captured at or after [since].
     *
     * **Decision-free** (capability `photo-selection-policy`): the titles to look for are a *parameter*. The
     * policy — which titles are denied — lives in `commonMain` ([DENYLISTED_ALBUM_TITLES]), never in this
     * untestable platform shell, per the same rule that keeps album *placement* decisions out of it.
     *
     * Matching is on **user albums by title only**. A smart album's title is system-localized ("Screenshots"
     * / "Bildschirmfotos"), so title-matching one is meaningless — smart albums are excluded by *subtype*
     * elsewhere, and screenshots do not need this seam at all.
     *
     * Cost is proportional to the number of albums, **not** the number of assets: one collection fetch per
     * album, with [since] pushed into the member fetch. It must never become a per-asset membership test —
     * that is the shape that made the whole-library walk expensive in the first place.
     */
    suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String>
}

/**
 * The persisted `eventId → albumLocalId` map (capability `event-album`). It lives in a **shared** store
 * (App-Group / shared Keychain) readable and writable by both the app and the upload extension, and it
 * **survives `LeaveEvent.leave()`** (unlike the event config) so a re-join reuses the same album. The
 * iOS impl is `IosAlbumMapStore`; a fake/in-memory impl backs the tests.
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
