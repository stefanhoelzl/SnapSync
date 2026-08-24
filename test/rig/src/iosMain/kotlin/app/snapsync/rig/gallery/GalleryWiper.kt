package app.snapsync.rig.gallery

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSThread
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import platform.Foundation.NSError
import platform.Foundation.NSFastEnumerationProtocol
import platform.Foundation.NSMutableArray
import platform.Photos.PHAsset
import platform.Photos.PHAssetEditOperationDelete
import platform.Photos.PHAssetSourceTypeCloudShared
import platform.Photos.PHAssetSourceTypeUserLibrary
import platform.Photos.PHAssetSourceTypeiTunesSynced
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHCollectionList
import platform.Photos.PHCollectionListChangeRequest
import platform.Photos.PHCollectionListSubtypeAny
import platform.Photos.PHCollectionListTypeFolder
import platform.Photos.PHPhotoLibrary

/**
 * What a wipe may delete.
 *
 * This was a sealed `WipeRequest` in `:domain` `model/`, parsed from `SNAPSYNC_WIPE_GALLERY` and tested
 * there, because a launch variable has nowhere else to be validated. Over HTTP an unrecognized scope is a
 * **bad request**, and a `400` naming the accepted values is a louder refusal than the log line the launch
 * form could manage — so the grammar is this enum and the refusal is the status code.
 *
 * The safety property is unchanged and is the reason this is value-checked at all while its siblings are
 * presence-checked: the wipe **cannot be undone**, so a stale or mistyped scope must refuse rather than
 * delete something.
 */
enum class WipeScope(val includesAssets: Boolean, val includesAlbums: Boolean) {
    ALL(includesAssets = true, includesAlbums = true),
    ASSETS(includesAssets = true, includesAlbums = false),
    ALBUMS(includesAssets = false, includesAlbums = true),
    ;

    companion object {
        /** `null` for anything unrecognized — the caller answers `400` and names what is accepted. */
        fun parse(raw: String?): WipeScope? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * A sub-range of the matched assets to delete, instead of all of them.
 *
 * **This exists to bisect a delete that never completes**, and it is worth stating why bisecting is the
 * right tool rather than a shot in the dark. Every Apple report of this symptom (see [wipeGallery]) names a
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
class WipeWindow(val offset: Long, val limit: Long?)

/** What a wipe did, for the command's response. */
class WipeOutcome(
    val scope: WipeScope,
    val matchedAssets: Long,
    val matchedAlbums: Long,
    val matchedFolders: Long,
    val grant: String,
    /**
     * Whether the platform ANSWERED at all within [WIPE_ANSWER_DEADLINE].
     *
     * This is the field the first implementation could not have. It waited on
     * `performChangesAndWait`, which blocks until the user taps — with no bound, and therefore no way to
     * distinguish "not tapped yet" from "this confirmation is never going to be presented". Measured on
     * an SE2 (iOS 26.6), the second case is real and reachable: see the header for the runs, including the
     * empty change block that commits in 48 ms while a 96-asset delete beside it never answers at all.
     *
     * `false` means "nobody answered YET" — it does NOT mean nothing was deleted. The alert outlives the
     * deadline (measured; see [performWipe]), so the caller should look at the phone and then re-read the
     * gallery, rather than treat this as a completed no-op.
     */
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
    val answered: Boolean,
    val committed: Boolean,
    val errorCode: Long?,
    val errorDescription: String?,
)

/**
 * How long to wait for the operator to answer the platform's confirmation.
 *
 * A bound exists here and nowhere else in this channel deliberately. `RigServer` sets no request timeout
 * on purpose — bounding a trigger below its OS receipt's own deadline would make a transport timeout
 * indistinguishable from an expired receipt. That reasoning holds for a receipt, which the OS always
 * fires. It does not hold for an alert, which may never be presented at all, and where an unbounded wait
 * collapses two very different answers into one silence.
 */
val WIPE_ANSWER_DEADLINE: Duration = 120.seconds

/**
 * **Empty this device's photo library.** Irreversible.
 *
 * **It is not headless, and cannot be made so.** The system raises its own confirmation, which needs a tap
 * on the device — that alert, showing the real count, IS this command's safety mechanism, and the reason
 * the app builds no dialog of its own. The command therefore waits for the operator's answer and reports
 * what happened; a `202` would not make it more headless, it would only hide the wait. It waits by
 * SUSPENDING, not by blocking a thread, and it waits with a deadline — see [performWipe].
 *
 * **Measured** (SE2, iOS 26.6, 2026-08-08): one change block deleting 9525 assets **and** 5
 * albums raised **TWO** confirmations, one per kind, both requiring a tap — so collection deletion prompts
 * too, and batching into a single change block does **not** collapse the prompts to one. An `albums`-only
 * wipe therefore also waits for a tap. ⏰ Re-measure at the next iOS major; evidence is one device, one
 * point release.
 *
 * Three scope facts worth stating before someone widens this:
 *
 * - the asset fetch is unfiltered — photos *and* videos, whatever the library holds. Under a **LIMITED**
 *   grant it returns the user's hand-picked selection and nothing else, because that is all PhotoKit will
 *   return, so the wipe is scoped to exactly the set that grant exposes (the grant is reported for that
 *   reason);
 * - albums are fetched as `.albumRegular` — user-created albums. **Smart albums** (Recents, Screenshots,
 *   Favourites) are system-owned and cannot be deleted at all, and **iCloud Shared Albums** are
 *   deliberately excluded: deleting one removes it for every subscriber, i.e. off other people's devices,
 *   which is a blast radius no dev command on this phone should have;
 * - deleting a collection never deletes its members, so `albums` leaves every photo in place.
 *
 * ⚠️ **If the confirmation never appears, RESTART THE DEVICE. That is the whole fix.**
 *
 * Measured (SE2, iOS 26.6, 2026-08-24). For an evening this command reached `matched N asset(s)` and then
 * nothing — no alert, no completion — through every combination of scope, count, lane, launch style and
 * ordering. After a `diagnostics restart`, with NOTHING else changed — same build, same 96 assets, same
 * code path — the alert appeared in under ten seconds and the library went 96 → 0.
 *
 * It is transient state in the OS's photo library daemon, not this code and not the assets. Apple's own
 * reports describe it precisely: the delete path stops presenting its alert, survives app kills and
 * reinstalls, and clears on a device restart —
 * [840122](https://developer.apple.com/forums/thread/840122) (an Apple engineer confirms a fix for one
 * trigger in iOS 27.0 beta 3), [806349](https://developer.apple.com/forums/thread/806349),
 * [732820](https://developer.apple.com/forums/thread/732820).
 *
 * **The cheap discriminator, if it happens again**: run this with `scope=albums` against a library with no
 * albums, or any window selecting nothing. That submits an EMPTY change block — which needs no
 * confirmation — and returned `committed=true` in **48 ms** while a one-asset delete beside it never
 * answered at all. So it separates "the change pipeline is dead" from "only the alert path is dead", which
 * look identical from outside and need opposite responses (the first is not something a restart fixes).
 *
 * Ruled out by measurement, from a COLD process with the wipe as the first PhotoKit call — worth listing
 * only because an earlier round "ruled out" the same things against a library already carrying a failed
 * request, which could not have answered differently: asset permission, ownership and source (96/96
 * `canPerformEditOperation`, all `userLibrary`); a blocked main thread; channel-lane starvation; a stale or
 * locked screen; a scene-less tooling launch; the blocking API; background-thread submission; running after
 * other PhotoKit work; and the asset set itself (`limit=1`, one deletable asset, failed identically).
 *
 * What the deadline below buys is that none of this is a hang any more. It is an answer in two minutes —
 * but read what [WipeOutcome.answered] actually claims: the alert outlives the deadline, so a wipe that
 * reported nothing can still delete afterwards. The gallery read, not this outcome, is the truth.
 */
suspend fun wipeGallery(
    log: Logger,
    scope: WipeScope,
    requester: PhotoAccessRequester,
    status: PhotoAccessStatusSource,
    deadline: Duration = WIPE_ANSWER_DEADLINE,
    window: WipeWindow? = null,
): WipeOutcome {
    // Ask for access first. Without it the fetch returns an empty result and the command looks like it did
    // nothing at all — the one failure mode a wipe cannot afford, since the operator's next move would be
    // to run it again. Already-granted is a no-op callback, denied returns immediately.
    requester.request()
    val grant = status.permission.first { it != PermissionStatus.NOT_DETERMINED }
    log.i { "wipe: photo access = $grant (LIMITED scopes the wipe to the hand-picked selection)" }
    return performWipe(log, scope, grant.name, deadline, window)
}

/**
 * Delete everything in scope in **one** change block, **without blocking a thread**.
 *
 * This uses `performChanges` with a completion handler rather than `performChangesAndWait`, and the
 * difference is not stylistic. The `AndWait` variant blocks its calling thread until the user answers the
 * platform's confirmation — an unbounded wait on human input, inside a `suspend` function, which is
 * precisely what suspension exists to avoid. Here the caller suspends and its thread is returned to the
 * pool; nothing is held while the alert is on screen.
 *
 * It is also the shape that can be bounded. A blocked thread has no deadline to apply; a suspended
 * coroutine does, so [deadline] can turn "nobody answered" into a stated outcome rather than a hang.
 *
 * One transaction is still one transaction, not one prompt: the platform raises a confirmation **per
 * kind** (measured — see the file header), so an `all` wipe asks twice.
 *
 * The matched counts are read *before* the alert on purpose: the operator is about to be asked to approve
 * a number, and a run they cancel must still report what it would have deleted.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun performWipe(
    log: Logger,
    scope: WipeScope,
    grant: String,
    deadline: Duration,
    window: WipeWindow?,
): WipeOutcome {
    // PHFetchResult is lazy — these are cheap handles, not enumerations, and each conforms to
    // NSFastEnumeration, which is what a change request takes.
    val assets = PHAsset.fetchAssetsWithOptions(null).takeIf { scope.includesAssets }
    val albums = PHAssetCollection.fetchAssetCollectionsWithType(
        PHAssetCollectionTypeAlbum,
        subtype = PHAssetCollectionSubtypeAlbumRegular,
        options = null,
    ).takeIf { scope.includesAlbums }
    val folders = PHCollectionList.fetchCollectionListsWithType(
        PHCollectionListTypeFolder,
        subtype = PHCollectionListSubtypeAny,
        options = null,
    ).takeIf { scope.includesAlbums }

    val matchedAssets = (assets?.count ?: 0uL).toLong()

    // The window, resolved against what actually matched. Clamped rather than refused: a bisect walks
    // `offset` past the end by construction, and "selected 0" is the honest answer there, not an error.
    val start = (window?.offset ?: 0L).coerceIn(0L, matchedAssets)
    val end = window?.limit?.let { (start + it).coerceAtMost(matchedAssets) } ?: matchedAssets
    val picked = mutableListOf<PHAsset>()
    if (window != null) {
        assets?.let { result -> for (i in start until end) picked.add(result.objectAtIndex(i.toULong()) as PHAsset) }
    }
    val selected = if (window == null) matchedAssets else picked.size.toLong()

    // What the app may actually delete, and where the rest came from — over the SUBMITTED set, since that
    // is the set whose deletability decides this run. Cheap: both are in-memory `PHAsset` properties.
    var deletable = 0L
    val bySource = mutableMapOf<String, Long>()
    val censusOf: (PHAsset) -> Unit = { asset ->
        if (asset.canPerformEditOperation(PHAssetEditOperationDelete)) deletable++
        val source = when (asset.sourceType) {
            PHAssetSourceTypeUserLibrary -> "userLibrary"
            PHAssetSourceTypeCloudShared -> "cloudShared"
            PHAssetSourceTypeiTunesSynced -> "iTunesSynced"
            else -> "other(${asset.sourceType})"
        }
        bySource[source] = (bySource[source] ?: 0L) + 1L
    }
    if (window != null) {
        picked.forEach(censusOf)
    } else {
        assets?.let { result -> for (i in 0uL until result.count) censusOf(result.objectAtIndex(i) as PHAsset) }
    }

    // The argument the change request receives. Un-windowed keeps the lazy `PHFetchResult` the measured
    // runs used; windowed materializes an NSArray, which conforms to NSFastEnumeration just the same.
    val assetsToDelete: NSFastEnumerationProtocol? = when {
        assets == null -> null
        window == null -> assets.takeIf { it.count > 0uL }
        picked.isEmpty() -> null
        else -> NSMutableArray().apply { picked.forEach { addObject(it) } }
    }
    val matchedAlbums = (albums?.count ?: 0uL).toLong()
    val matchedFolders = (folders?.count ?: 0uL).toLong()

    log.i {
        "wipe: matched $matchedAssets asset(s), submitting $selected" +
            (window?.let { " (window offset=${it.offset} limit=${it.limit})" } ?: "") +
            " ($deletable deletable, by source $bySource), $matchedAlbums album(s), $matchedFolders folder(s)"
    }

    // REFUSE rather than submit an impossible request. PhotoKit accepts a delete of assets the app may
    // not delete and then never completes it — no error, no presentation, no callback — which is the
    // measured symptom this command spent an evening chasing. Asking first turns that silence into a
    // stated answer, immediately, and it is the honest answer: nothing here was ever going to be deleted.
    if (selected > 0L && deletable == 0L) {
        log.e {
            "wipe: REFUSING — none of the $selected submitted asset(s) is deletable by this app " +
                "(by source: $bySource). Assets synced from iTunes or shared from iCloud are read-only " +
                "to an app; PhotoKit would accept the request and never complete it."
        }
        return WipeOutcome(
            scope = scope,
            matchedAssets = matchedAssets,
            matchedAlbums = matchedAlbums,
            matchedFolders = matchedFolders,
            grant = grant,
            deletable = deletable,
            bySource = bySource,
            selected = selected,
            window = window,
            answered = false,
            committed = false,
            errorCode = null,
            errorDescription = "no matched asset is deletable by this app; none of this could ever be deleted",
        )
    }

    log.i {
        "wipe: submitting the change on ${NSThread.currentThread.description}, then awaiting the " +
            "system confirmation for up to $deadline"
    }

    // `withTimeoutOrNull` returns null when the deadline passes: the coroutine resumes and the caller gets
    // a stated answer. What it does NOT do is cancel anything on the platform's side.
    //
    // ⚠️ **A timed-out wipe can still delete, minutes later.** Measured (SE2, iOS 26.6, 2026-08-24): the
    // system alert OUTLIVES this deadline — it stayed on screen after a `scope=all` run reported
    // `answered=false`, and a tap five minutes later deleted all 95 assets with nothing listening. So
    // `answered=false` means "no answer YET", never "nothing happened", and the caller must not be told
    // otherwise. Only a tap or `Don't Allow` ends that alert; abandoning the continuation does not.
    val result: Pair<Boolean, NSError?>? = withTimeoutOrNull(deadline) {
        suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                changeBlock = {
                    assetsToDelete?.let { PHAssetChangeRequest.deleteAssets(it) }
                    albums?.takeIf { it.count > 0uL }
                        ?.let { PHAssetCollectionChangeRequest.deleteAssetCollections(it) }
                    folders?.takeIf { it.count > 0uL }
                        ?.let { PHCollectionListChangeRequest.deleteCollectionLists(it) }
                },
                completionHandler = { success, error ->
                    // `resume` is safe from whatever queue PhotoKit answers on, and this continuation is
                    // resumed at most once by construction.
                    if (cont.isActive) cont.resume(success to error)
                },
            )
        }
    }

    if (result == null) {
        log.e {
            "wipe: NOBODY ANSWERED within $deadline. This does NOT mean nothing was deleted — the alert " +
                "outlives this deadline, so if it is still on screen a later tap will still delete the " +
                "$selected asset(s), and nothing will be listening. Either it is waiting for a tap or it " +
                "was never presented; those look identical from here, which is why this is bounded rather " +
                "than left to hang. LOOK AT THE PHONE, then re-read the gallery for the truth."
        }
        return WipeOutcome(
            scope = scope,
            matchedAssets = matchedAssets,
            matchedAlbums = matchedAlbums,
            matchedFolders = matchedFolders,
            grant = grant,
            deletable = deletable,
            bySource = bySource,
            selected = selected,
            window = window,
            answered = false,
            committed = false,
            errorCode = null,
            errorDescription = null,
        )
    }

    val (committed, error) = result
    // A tapped "Cancel" arrives as a failure carrying `PHPhotosError.userCancelled` (3072) — named here
    // because "committed=false" alone reads as a bug, and a cancel is the operator answering.
    log.i {
        "wipe: committed=$committed" + (error?.let { " error=${it.code} ${it.localizedDescription}" } ?: "")
    }
    return WipeOutcome(
        scope = scope,
        matchedAssets = matchedAssets,
        matchedAlbums = matchedAlbums,
        matchedFolders = matchedFolders,
        grant = grant,
        deletable = deletable,
        bySource = bySource,
        selected = selected,
        window = window,
        answered = true,
        committed = committed,
        errorCode = error?.code,
        errorDescription = error?.localizedDescription,
    )
}
