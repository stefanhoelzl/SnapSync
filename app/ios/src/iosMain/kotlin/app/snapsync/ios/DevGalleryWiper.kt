package app.snapsync.ios

import app.snapsync.model.PermissionStatus
import app.snapsync.model.WipeRequest
import app.snapsync.permission.PhotoLibraryPermission
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
 * **Dev/test only.** `SNAPSYNC_WIPE_GALLERY=all|assets|albums` — empty this device's photo library
 * (capability `ios-app-shell`; the scope grammar is the tested [WipeRequest] in `:domain` `model/`).
 *
 * Read from the process environment, which is **only injectable via a developer launch**
 * (`pymobiledevice3 developer dvt launch --env …`) — SpringBoard and TestFlight launches carry a clean
 * environment, so this is inert in production with no compile-time guard, exactly as `SNAPSYNC_SEED_PHOTOS`
 * and `SNAPSYNC_EVENT_LINK` are.
 *
 * **It is not headless, and cannot be made so.** The system raises its own confirmation, which needs a
 * tap on the device — that alert, showing the real count, IS this trigger's safety mechanism, and the
 * reason the app builds no dialog of its own.
 *
 * **Measured** (SE2, iOS 26.6, 2026-08-08): one `performChangesAndWait` deleting 9525 assets **and** 5
 * albums raised **TWO** confirmations, one per kind, both requiring a tap — so collection deletion
 * prompts too, and batching into a single change block does **not** collapse the prompts to one. This
 * file previously claimed the opposite (assets prompt, collections do not); the device disproved it. An
 * `albums`-only wipe therefore also waits for a tap. ⏰ Re-measure at the next iOS major; evidence is one
 * device, one point release.
 *
 * Three scope facts worth stating before someone widens this:
 *
 * - the asset fetch is unfiltered — photos *and* videos, whatever the library holds. Under a **LIMITED**
 *   grant it returns the user's hand-picked selection and nothing else, because that is all PhotoKit will
 *   return, so the wipe is scoped to exactly the set that grant exposes (the grant is logged for that
 *   reason);
 * - albums are fetched as `.albumRegular` — user-created albums. **Smart albums** (Recents, Screenshots,
 *   Favourites) are system-owned and cannot be deleted at all, and **iCloud Shared Albums** are
 *   deliberately excluded: deleting one removes it for every subscriber, i.e. off other people's devices,
 *   which is a blast radius no dev trigger on this phone should have;
 * - deleting a collection never deletes its members, so `albums` leaves every photo in place.
 *
 * Blocking (`performChangesAndWait`), so this must not run on the main thread — and it stays blocked for
 * as long as the alert goes unanswered.
 */
// PINNED shell decision (spec `module-architecture`, "Shells are wiring only" — pinned forms; inventory
// gated by KotlinShellGuardTest). Forcing proof: the one branch is the gate in front of a PLATFORM SIDE
// EFFECT that must not happen unasked — `access.request()` raises the system photo-access alert, so
// running it unconditionally would prompt every production launch. It cannot move into the tested
// `WipeRequest` (which decides WHETHER, and does: `wipesAnything`) because what is forced here is the
// control flow that skips the platform call, not the decision behind it. Expiry: dies with the trigger.
@Suppress("CyclomaticComplexMethod")
suspend fun wipeGalleryFromLaunchEnv(
    log: Logger,
    request: WipeRequest,
    access: PhotoLibraryPermission,
) {
    // One line, whatever the answer: the request states its own plan, including why it is wiping nothing
    // (unset vs. a value that named no scope) — the shell branches on none of it.
    log.i { request.plan }
    if (!request.wipesAnything) return

    // Ask for access first. Without it the fetch returns an empty result and the launch looks like it did
    // nothing at all — the one failure mode this trigger cannot afford, since the operator's next move
    // would be to run it again. Already-granted is a no-op callback, denied returns immediately.
    access.request()
    val grant = access.permission.first { it != PermissionStatus.NOT_DETERMINED }
    log.i { "wipe: photo access = $grant (LIMITED scopes the wipe to the hand-picked selection)" }
    performWipe(log, request.includesAssets, request.includesAlbums)
}

/**
 * Delete everything requested in **one** change block. That is one transaction, not one prompt: the
 * platform raises a confirmation **per kind** (measured — see the file header), so an `all` wipe asks
 * twice.
 *
 * The matched counts are logged *before* the alert on purpose: the operator is about to be asked to
 * approve a number, and a run they cancel must still leave behind what it would have deleted.
 */
// PINNED shell decision (spec `module-architecture`, "Shells are wiring only" — pinned forms; inventory
// gated by KotlinShellGuardTest). Forcing proof: dev equipment that can only live in the app process —
// it deletes from the REAL photo library of the attached device from a launch-env trigger (injectable
// only via a developer launch, so inert in production), which no tested module can reach. The branches
// are the platform's shape: each of the three fetch results is guarded on both in-scope AND non-empty
// before it may be handed to a change request. The non-empty half is DEFENSIVE, not a measured
// necessity — PhotoKit does not document an empty deletion argument as a no-op, and this trigger's one
// failure mode we cannot afford is raising an exception inside the change block instead of the
// confirmation alert. The scope decision itself is NOT here — it is the tested `WipeRequest`, arriving
// as two booleans. Expiry: dies with the trigger.
@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun performWipe(log: Logger, wipeAssets: Boolean, wipeAlbums: Boolean) {
    // PHFetchResult is lazy — these are cheap handles, not enumerations, and each conforms to
    // NSFastEnumeration, which is what a change request takes.
    val assets = PHAsset.fetchAssetsWithOptions(null).takeIf { wipeAssets }
    val albums = PHAssetCollection.fetchAssetCollectionsWithType(
        PHAssetCollectionTypeAlbum,
        subtype = PHAssetCollectionSubtypeAlbumRegular,
        options = null,
    ).takeIf { wipeAlbums }
    val folders = PHCollectionList.fetchCollectionListsWithType(
        PHCollectionListTypeFolder,
        subtype = PHCollectionListSubtypeAny,
        options = null,
    ).takeIf { wipeAlbums }

    log.i {
        "wipe: matched ${assets?.count ?: 0uL} asset(s), ${albums?.count ?: 0uL} album(s), " +
            "${folders?.count ?: 0uL} folder(s) — awaiting the system confirmation"
    }

    memScoped {
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
        // A tapped "Cancel" arrives as a failure carrying `PHPhotosError.userCancelled` (3072) — named
        // here because "committed=false" alone reads as a bug, and a cancel is the operator answering.
        log.i {
            "wipe: committed=$committed" +
                (error?.let { " error=${it.code} ${it.localizedDescription}" } ?: "")
        }
    }
}
