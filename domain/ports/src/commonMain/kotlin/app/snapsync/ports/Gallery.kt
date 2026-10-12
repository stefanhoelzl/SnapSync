package app.snapsync.ports

import app.snapsync.model.AssetRef
import app.snapsync.model.ImportRequest
import app.snapsync.model.WriteOutcome
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * What either process uses of the device's photo library (`docs/architecture.md`): read its assets, their
 * resources and its albums, and file assets into an album. **Thin**: it answers what the platform shows and
 * decides nothing. Whether a read is authoritative, which grant may answer a presence question, which albums
 * are denylisted and what a partial grant's selection means are all decided above it, in `:domain:services`
 * and `:domain:feature`.
 *
 * The upload extension gets this and nothing more; the app gets [Gallery], which extends it.
 *
 * **Every read is scoped to the member's default gallery**, which the platform defines:
 * on iOS the whole library, on Android `DCIM` and its subfolders — where a subfolder is also what [albums] lists. An
 * asset outside it is never returned, by any read, so no consumer can count, list or upload one.
 *
 * **Reads answer [GalleryRead.NotReadable] when no grant lets this process see the library** (undetermined or
 * refused) — without touching the platform, whose empty answer there is not an empty library, and whose album
 * fetch under an undetermined grant raises the permission dialog. Under a partial grant a read answers what
 * the platform shows: the member's selection. Writes are not gated: creating an album and filing into one are
 * permitted under a partial grant.
 *
 * Every read hops off the caller's lane itself: each platform call is a synchronous round-trip into the photo
 * service, which no timeout can abandon.
 */
interface GalleryReader : Port {

    /**
     * The grant as the platform reports it right now — a status read, never a request, so it raises no dialog. Cheap
     * and synchronous. The upload extension decides its own admission from it at every gate: it has no long-lived
     * grant to observe, and its grant is read in its own process.
     */
    fun access(): LibraryGrant

    /**
     * The library's assets in [filter]'s range — facts only, no resources. [filter] is a hint (see [AssetFilter]): the
     * answer may hold assets outside it and never leaves out one inside it, because it is also a walk's presence set.
     */
    suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>>

    /** The assets among [ids] the library still holds — facts only. A missing id is simply not returned. */
    suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>>

    /**
     * The assets among [ids] with their original resources, in one request. A missing id is simply not returned; an
     * asset with no original resource of a [ResourceKind] is returned with none.
     */
    suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>>

    /**
     * Every user-created album (never a system smart album, whose titles are localized). On Android an album is a
     * folder of the default gallery, titled by the folder's own name.
     */
    suspend fun albums(): GalleryRead<List<GalleryAlbum>>

    /** The albums among [ids] that still resolve (the member may have deleted one). */
    suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>>

    /**
     * The assets in [album] captured at or after [createdFrom], or every member when it is null — a hint as
     * [AssetFilter]'s bounds are: an earlier member may come back, a later one never stays out. An unresolvable album
     * has no members.
     */
    suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?): GalleryRead<Set<PlatformAssetId>>

    /**
     * How this library holds an album: a [LibraryAlbumKind.COLLECTION] an asset is added to, or the
     * [LibraryAlbumKind.FOLDER] a file lives in. A fact of the platform, fixed for the adapter's life.
     */
    val albumKind: LibraryAlbumKind

    /**
     * Create an album titled [title] and return its id, or `null` when the platform refused — whatever the
     * cause. Only the app creates albums (`AlbumCoordinator`). A [LibraryAlbumKind.FOLDER] album is a folder of its
     * own: two albums of one title are two folders, and one does not [albumsById]-resolve until it holds a photo.
     */
    suspend fun createAlbum(title: String): PlatformAlbumId?

    /**
     * File [assets] into [album]. An id the library no longer holds is skipped, and adding an asset already in
     * the album is a no-op. Under [LibraryAlbumKind.COLLECTION] an album that no longer resolves is
     * [WriteOutcome.Failed]. Under [LibraryAlbumKind.FOLDER] filing MOVES the file into the album's folder, keeping its
     * id, and an album this library created takes photos while still empty; an asset the platform will not let this
     * app move (one it did not save itself) is skipped, never asked about.
     */
    suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome

    /**
     * Write [resource]'s bytes to the platform file [to] (a `Files.locate` answer), replacing whatever it held — for an
     * uploader that sends from a file. A handle this library did not hand out, or one it can no longer produce, is
     * [WriteOutcome.Failed]; nothing is left at [to] then.
     */
    suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome
}

/**
 * What the app's gallery tells the core (`docs/architecture.md`, the handler table). Each runs on the gallery's
 * delivering thread and must return promptly.
 */
class GalleryHandlers(
    /**
     * The asset an import is creating, called INSIDE the platform's change block — before the asset can be
     * observed — so the marker that keeps it from being uploaded back lands first.
     * The write is synchronous, on the delivering thread. If a change block runs again, the last call wins.
     */
    val onImportPlaceholder: (AssetRef, PlatformAssetId) -> Unit,
    /**
     * An import's outcome, once, from the platform's completion — which runs even when the requester is gone, so
     * the outcome is persisted here, inline, and never only returned (`docs/architecture.md`, "A delivery the
     * platform makes once is persisted before the entry point returns").
     */
    val onImportSettled: (AssetRef, ImportOutcome) -> Unit,
)

/**
 * Rebuilding one foreign asset in the gallery — the one [Gallery] member the
 * download feature needs, on its own so the feature names nothing else of the app's gallery.
 */
interface GalleryImport : Port {
    /**
     * Create one asset from [request]'s staged resources in one platform transaction. The placeholder and the
     * outcome reach the registered [GalleryHandlers] (`onImportPlaceholder` inside the change, `onImportSettled` on
     * the completion); this returns the same outcome, only after `onImportSettled` has **returned**. Nothing bounds
     * the wait: an import the platform never reports never returns.
     */
    suspend fun import(request: ImportRequest): ImportOutcome
}

/**
 * The app's gallery: the [GalleryReader] plus what only the foreground process may do — ask for access, hand
 * the member the platform's selection picker, observe a partial grant's selection, import foreign photos, and read
 * the library's change token for the walk memo. What it observes arrives through [listen].
 */
interface Gallery : GalleryReader, LibraryChangeTokenRead, GalleryImport, Listenable<GalleryHandlers> {

    /**
     * The grant as last read. Neither PhotoKit nor MediaStore calls back when it changes, so a change is
     * **synthetic**: a re-read on every foreground, the answers of [requestAccess] and [widenSelection], and every
     * [access] call — each publishes what it read here, so this never holds an older answer than [access] gave.
     */
    val grant: StateFlow<LibraryGrant>

    /**
     * The whole selection under a partial grant, as last read: the baseline when observation begins, then one per
     * change — only while [observeChanges] is on and the grant is partial. `null` until the first one is read; it then
     * keeps the latest. **Conflated**: only the latest snapshot matters.
     */
    val selection: StateFlow<SelectionSnapshot?>

    /**
     * Open ([enabled]) or close the selection observer. Open, it observes **only while the grant is partial** — a
     * baseline snapshot when observation begins and one per change after — and closes by itself when the grant
     * moves away. Called on composition, on every start — a background one included, so a wake that builds no screen
     * still learns the selection it may upload from.
     */
    fun observeChanges(enabled: Boolean)

    /**
     * Ask the member for access and answer the grant that results. With no visible screen to ask on it asks
     * nothing and answers [access]. Asking again after the member answered changes nothing.
     */
    suspend fun requestAccess(): LibraryGrant

    /**
     * Present the platform's surface for revising a **partial** grant's selection and answer the grant afterwards. The
     * selection the member picks arrives through the selection observer, never as this answer. The app suppresses the
     * platform's own automatic prompt, so this is the member's one route to widen what the app sees.
     */
    suspend fun widenSelection(): LibraryGrant
}

/**
 * The one [Gallery] member the walk memo needs, on its own so the memo names nothing else of the app's gallery.
 */
interface LibraryChangeTokenRead : Port {
    /**
     * The library's change token now, or `null` when the platform gave none — which a caller treats as "cannot
     * tell", never as "unchanged". Read **before** the walk it is stored with, so a change landing while the walk
     * runs leaves the stored token stale and the next walk enumerates afresh.
     *
     * Measured (SE2, iOS 26.6.2): a read plus a comparison costs about 2 ms in the background role, against
     * 1.3–2.0 s for a walk of 4.5k–6.3k assets; the token held across idle periods and relaunches, moved on
     * every asset creation and album add the app made, was never equal across a library change, and kept moving
     * for 1–9 s of trailing changes after any write; a change made **outside** the process (a Camera photo)
     * moved it too (`changes/own-work-per-wake`, task 7.4).
     */
    suspend fun changeToken(): LibraryChangeToken?
}

/**
 * One reading of the library's change token: opaque, comparable, and held only in memory. Not the discovery
 * cursor `changes/archive/2026-09-21-always-full-enumerate` removed: nothing archives, persists or feeds one to
 * a change query.
 */
interface LibraryChangeToken {
    /**
     * Whether [other] was read while the library stood exactly as it did when this one was read. Compared by
     * **value** — two reads of an unchanged library are distinct platform objects that compare equal — never by
     * identity, which would never match and so would make the memo walk every time.
     */
    fun sameLibraryAs(other: LibraryChangeToken): Boolean
}
