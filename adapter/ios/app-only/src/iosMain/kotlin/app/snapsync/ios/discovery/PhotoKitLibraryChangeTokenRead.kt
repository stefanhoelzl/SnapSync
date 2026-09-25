package app.snapsync.ios.discovery

import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import kotlinx.coroutines.withContext
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary

/**
 * The PhotoKit [LibraryChangeTokenRead]: `PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken`, compared with
 * `isEqual` (capability `photo-sharing`, "An unchanged library is answered from the walk memo").
 *
 * **App process only**, and placed by linkage to say so: this module is one the upload extension never links, so
 * the extension cannot hold a token or a memo even by accident (capability `background-upload` — its 32 MB limit
 * leaves no room for a held walk). Read only under a full grant: the memo passes every other grant straight to the
 * walk without asking for a token.
 *
 * Not a cursor. The token is never archived, stored, or handed to `fetchPersistentChanges(since:)` — it lives in
 * memory beside the walk it was read before, and dies with the process.
 *
 * On [photoKitReadLane] for the reason `IosDiscovery` gives: the read is a synchronous XPC round-trip into
 * `assetsd`, and the lane's pinned QoS is what it is answered at. Measured about 2 ms in darwinbg (SE2, iOS 26.6.2).
 *
 * Held to `LibraryChangeTokenContract` in the simulator app under a full grant.
 */
class PhotoKitLibraryChangeTokenRead : LibraryChangeTokenRead {
    override suspend fun current(): LibraryChangeToken? = withContext(photoKitReadLane) {
        libraryChangeTokenOf(PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken)
    }
}

/**
 * The one widening boundary for the token. The Photos klib declares `currentChangeToken` **non-null**; the SDK
 * makes no such promise in behaviour this project has measured (a no-grant process was never probed), and the
 * klib's non-null claim has been wrong before for PhotoKit objects (`PHAssetResourceUploadJob.destination` and
 * `.resource` — see `PhotoKitJobMapping`). A null check against a non-null-typed value may be elided; against this
 * nullable parameter it cannot, so an absent token reaches the memo as "cannot tell" rather than as a crash.
 * `PhotoKitLibraryChangeTokenTest` calls it with `null`, so narrowing the parameter stops compiling.
 */
internal fun libraryChangeTokenOf(token: PHPersistentChangeToken?): LibraryChangeToken? =
    token?.let(::PhotoKitLibraryChangeToken)

private class PhotoKitLibraryChangeToken(private val token: PHPersistentChangeToken) : LibraryChangeToken {
    override fun sameLibraryAs(other: LibraryChangeToken): Boolean =
        other is PhotoKitLibraryChangeToken && token.isEqual(other.token)
}
