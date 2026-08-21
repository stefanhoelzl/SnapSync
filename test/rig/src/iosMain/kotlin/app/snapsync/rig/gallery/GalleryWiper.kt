package app.snapsync.rig.gallery

import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.PhotoAccessStatusSource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.first
import platform.Foundation.NSError
import platform.Photos.PHAsset
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

/** What a wipe did, for the command's response. */
class WipeOutcome(
    val scope: WipeScope,
    val matchedAssets: Long,
    val matchedAlbums: Long,
    val matchedFolders: Long,
    val grant: String,
    val committed: Boolean,
    val errorCode: Long?,
    val errorDescription: String?,
)

/**
 * **Empty this device's photo library.** Irreversible.
 *
 * **It is not headless, and cannot be made so.** The system raises its own confirmation, which needs a tap
 * on the device — that alert, showing the real count, IS this command's safety mechanism, and the reason
 * the app builds no dialog of its own. The command therefore blocks until the operator answers, and reports
 * what happened; a `202` would not make it more headless, it would only hide the wait.
 *
 * **Measured** (SE2, iOS 26.6, 2026-08-08): one `performChangesAndWait` deleting 9525 assets **and** 5
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
 * Blocking (`performChangesAndWait`), so this must not run on the main thread — and it stays blocked for as
 * long as the alert goes unanswered.
 */
suspend fun wipeGallery(
    log: Logger,
    scope: WipeScope,
    requester: PhotoAccessRequester,
    status: PhotoAccessStatusSource,
): WipeOutcome {
    // Ask for access first. Without it the fetch returns an empty result and the command looks like it did
    // nothing at all — the one failure mode a wipe cannot afford, since the operator's next move would be
    // to run it again. Already-granted is a no-op callback, denied returns immediately.
    requester.request()
    val grant = status.permission.first { it != PermissionStatus.NOT_DETERMINED }
    log.i { "wipe: photo access = $grant (LIMITED scopes the wipe to the hand-picked selection)" }
    return performWipe(log, scope, grant.name)
}

/**
 * Delete everything in scope in **one** change block. That is one transaction, not one prompt: the platform
 * raises a confirmation **per kind** (measured — see the file header), so an `all` wipe asks twice.
 *
 * The matched counts are read *before* the alert on purpose: the operator is about to be asked to approve a
 * number, and a run they cancel must still report what it would have deleted.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun performWipe(log: Logger, scope: WipeScope, grant: String): WipeOutcome {
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

    log.i {
        "wipe: matched ${assets?.count ?: 0uL} asset(s), ${albums?.count ?: 0uL} album(s), " +
            "${folders?.count ?: 0uL} folder(s) — awaiting the system confirmation"
    }

    return memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val committed = PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                assets?.takeIf { it.count > 0uL }?.let { PHAssetChangeRequest.deleteAssets(it) }
                albums?.takeIf { it.count > 0uL }?.let { PHAssetCollectionChangeRequest.deleteAssetCollections(it) }
                folders?.takeIf { it.count > 0uL }?.let { PHCollectionListChangeRequest.deleteCollectionLists(it) }
            },
            error = errorVar.ptr,
        )
        val error = errorVar.value
        // A tapped "Cancel" arrives as a failure carrying `PHPhotosError.userCancelled` (3072) — named here
        // because "committed=false" alone reads as a bug, and a cancel is the operator answering.
        log.i {
            "wipe: committed=$committed" +
                (error?.let { " error=${it.code} ${it.localizedDescription}" } ?: "")
        }
        WipeOutcome(
            scope = scope,
            matchedAssets = (assets?.count ?: 0uL).toLong(),
            matchedAlbums = (albums?.count ?: 0uL).toLong(),
            matchedFolders = (folders?.count ?: 0uL).toLong(),
            grant = grant,
            committed = committed,
            errorCode = error?.code,
            errorDescription = error?.localizedDescription,
        )
    }
}
