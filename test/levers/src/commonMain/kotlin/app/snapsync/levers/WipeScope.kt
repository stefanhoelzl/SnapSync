package app.snapsync.levers

import kotlinx.serialization.Serializable

/**
 * What a wipe may delete.
 *
 * It was a sealed `WipeRequest` parsed from a launch variable, then a query string a host parsed; now it is the
 * type of the call, so a mistyped scope is refused by the caller's own decoding (the `rig` CLI names what is
 * accepted) before anything reaches a host. That matters more here than anywhere: the wipe **cannot be undone**.
 */
@Serializable
enum class WipeScope(val includesAssets: Boolean, val includesAlbums: Boolean) {
    ALL(includesAssets = true, includesAlbums = true),
    ASSETS(includesAssets = true, includesAlbums = false),
    ALBUMS(includesAssets = false, includesAlbums = true),
}

/**
 * A sub-range of the matched assets to delete, instead of all of them.
 *
 * **This exists to bisect a delete that never completes**, and it is worth stating why bisecting is the
 * right tool rather than a shot in the dark. Every Apple report of this symptom (see the iOS wiper, `wipeGallery`) names a
 * *specific asset* whose deletion hangs, not a library-wide state — so the question "does deleting ANY of
 * these work?" and "which ONE of these poisons the batch?" are both answerable by submitting fewer assets,
 * and by nothing else. `limit=1` answers the first; walking `offset` answers the second.
 *
 * It may also be the **fix** rather than only the probe. One `PHAssetChangeRequest.deleteAssets` over a
 * whole library is one transaction, and a transaction that never completes takes every asset in it down —
 * so if one asset is poison, chunking is what lets the other 95 through. The wipe that worked on 2026-08-08
 * pushed 9525 assets in a single transaction, so chunking has never been exercised on this device.
 *
 * Absent, the whole matched `PHFetchResult` is submitted exactly as before — the lazy handle, not a
 * materialized copy. That distinction is deliberate: it keeps the un-windowed path byte-identical to the
 * one that has been measured, so a window can never be blamed for a result it did not change.
 */
@Serializable
data class WipeWindow(val offset: Long, val limit: Long?)

/** What a wipe did. */
@Serializable
data class WipeOutcome(
    val scope: WipeScope,
    val matchedAssets: Long,
    val matchedAlbums: Long,
    val matchedFolders: Long,
    val grant: String,
    /**
     * How many matched assets the app is actually ALLOWED to delete
     * (`canPerformEditOperation(PHAssetEditOperationDelete)`).
     *
     * Asked before submitting, because PhotoKit does not refuse an impossible delete — it accepts the
     * request and never completes it. An asset synced from iTunes or shared from iCloud is read-only to
     * an app, so a library made of those produces exactly the symptom measured here: `assetsd` receives
     * the request and nothing further ever happens.
     */
    val deletable: Long,
    /** Matched assets by `PHAssetSourceType`, so a zero [deletable] says WHICH kind blocked it. */
    val bySource: Map<String, Long>,
    /** How many assets this run actually submitted for deletion — see [WipeWindow]. */
    val selected: Long,
    /** The window that produced [selected]; `null` when the whole match was submitted. */
    val window: WipeWindow?,
    val committed: Boolean,
    val errorCode: Long?,
    val errorDescription: String?,
)
