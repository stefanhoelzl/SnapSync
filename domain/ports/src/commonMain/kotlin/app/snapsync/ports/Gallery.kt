package app.snapsync.ports

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionSnapshot
import app.snapsync.model.WriteOutcome

/**
 * What either process uses of the device's photo library (`docs/architecture.md`): read its assets, their
 * resources and its albums, and file assets into an album. **Thin**: it answers what the platform shows and
 * decides nothing. Whether a read is authoritative, which grant may answer a presence question, which albums
 * are denylisted and what a partial grant's selection means are all decided above it, in `:domain:services`
 * and `:domain:feature`.
 *
 * The upload extension gets this and nothing more; the app gets [Gallery], which extends it.
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
interface GalleryReader {

    /** The grant as the platform reports it right now. Cheap, synchronous, and never raises a dialog. */
    fun access(): GalleryAccess

    /**
     * The assets [policy] may admit — facts only, no resources. The implementation narrows its native query by
     * whichever of the policy's rules it can express and ignores the rest: it MAY return assets the policy
     * rejects (the caller's admission is authoritative), and MUST NOT omit one it admits, because what comes
     * back is also a walk's presence set (capability `photo-sharing`).
     */
    suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>>

    /** The assets among [ids] the library still holds — facts only. A missing id is simply not returned. */
    suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>>

    /**
     * The assets among [ids] with every platform resource each carries, in one request. A missing id is simply
     * not returned; an asset whose resources carry no uploadable role is returned with them as they are.
     */
    suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>>

    /** Every user-created album (never a system smart album, whose titles are localized). */
    suspend fun albums(): GalleryRead<List<AlbumRecord>>

    /** The albums among [ids] that still resolve (the member may have deleted one). */
    suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>>

    /**
     * The assets in [album] captured at or after [since], or every member when [since] is null. An unresolvable
     * album has no members.
     */
    suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>>

    /**
     * Create an album titled [title] and return its id, or `null` when the platform refused — whatever the
     * cause. Only the app creates albums (`AlbumCoordinator`).
     */
    suspend fun createAlbum(title: String): AlbumId?

    /**
     * File [assets] into [album]. An id the library no longer holds is skipped, and adding an asset already in
     * the album is a no-op. An album that no longer resolves is [WriteOutcome.Failed].
     */
    suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome
}

/**
 * What the app's gallery tells the core (`docs/architecture.md`, the handler table). Each runs on the gallery's
 * delivering thread and must return promptly.
 */
class GalleryHandlers(
    /**
     * The whole selection under a partial grant, once when observation begins (the cold-launch baseline) and once
     * per change — only while [Gallery.observeChanges] is on and the grant is partial. **Conflated**: only the latest
     * snapshot matters, so a handler hands it on rather than doing the work in place.
     */
    val onChanged: (SelectionSnapshot) -> Unit,
    /**
     * The asset an import is creating, called INSIDE the platform's change block — before the asset can be
     * observed — so the marker that keeps it from being uploaded back lands first (capability `receiving-photos`).
     * The write is synchronous, on the delivering thread. If a change block runs again, the last call wins.
     */
    val onImportPlaceholder: (AssetRef, AssetId) -> Unit,
    /**
     * An import's outcome, once, from the platform's completion — which runs even when the requester is gone, so
     * the outcome is persisted here, inline, and never only returned (`docs/architecture.md`, "A delivery the
     * platform makes once is persisted before the entry point returns").
     */
    val onImportSettled: (AssetRef, ImportResult) -> Unit,
)

/**
 * Rebuilding one foreign asset in the gallery (capability `receiving-photos`) — the one [Gallery] member the
 * download feature needs, on its own so the feature names nothing else of the app's gallery.
 */
interface GalleryImport {
    /**
     * Create one asset from [request]'s staged resources in one platform transaction. The placeholder and the
     * outcome reach the registered [GalleryHandlers] (`onImportPlaceholder` inside the change, `onImportSettled` on
     * the completion); this returns the same outcome, only after `onImportSettled` has **returned**. Nothing bounds
     * the wait: an import the platform never reports never returns (capability `receiving-photos`).
     */
    suspend fun import(request: ImportRequest): ImportResult
}

/**
 * The app's gallery: the [GalleryReader] plus what only the foreground process may do — ask for access, hand
 * the member the platform's selection picker, observe a partial grant's selection, import foreign photos, and read
 * the library's change token for the walk memo. What it observes arrives through [listen].
 */
interface Gallery : GalleryReader, LibraryChangeTokenRead, GalleryImport, Listenable<GalleryHandlers> {

    /**
     * Open ([enabled]) or close the selection observer. Open, it observes **only while the grant is partial** — a
     * baseline snapshot when observation begins and one per change after — and closes by itself when the grant
     * moves away. Called only from host assembly, so a background wake that never builds the screen reads nothing.
     */
    fun observeChanges(enabled: Boolean)

    /**
     * Ask the member for access and answer the grant that results. With no visible screen to ask on it asks
     * nothing and answers [access]. Asking again after the member answered changes nothing.
     */
    suspend fun requestAccess(): GalleryAccess

    /**
     * Present the platform's surface for revising a **partial** grant's selection (capability `photo-access`)
     * and answer the grant afterwards. The selection the member picks arrives through the selection observer,
     * never as this answer. The app suppresses the platform's own automatic prompt, so this is the member's one
     * route to widen what the app sees.
     */
    suspend fun widenSelection(): GalleryAccess
}

/**
 * The one [Gallery] member the walk memo needs, on its own so the memo names nothing else of the app's gallery.
 */
interface LibraryChangeTokenRead {
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
