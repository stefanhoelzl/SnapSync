package app.snapsync.album

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
     */
    suspend fun ensureCreated(name: String): String?

    /** Whether an album with [albumLocalId] still resolves (the user may have deleted it). */
    suspend fun exists(albumLocalId: String): Boolean

    /**
     * Add the library assets identified by [rawLocalIds] (raw PHAsset `localIdentifier`s) to the album
     * [albumLocalId]. Best-effort: a missing asset is skipped, adding an already-present asset is a no-op.
     */
    suspend fun add(albumLocalId: String, rawLocalIds: List<String>)
}

/**
 * The persisted `eventId → albumLocalId` map (capability `event-album`). It lives in a **shared** store
 * (App-Group / shared Keychain) readable and writable by both the app and the upload extension, and it
 * **survives `LeaveEvent.leave()`** (unlike the event config) so a re-join reuses the same album. The
 * iOS impl is `IosAlbumMapStore`; a fake/in-memory impl backs the tests.
 */
interface AlbumMapStore {
    /** The stored album `localIdentifier` for [eventId], or `null` if none was ever created. */
    fun get(eventId: String): String?

    /** Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). */
    fun put(eventId: String, albumLocalId: String)
}
