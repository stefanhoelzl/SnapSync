package app.snapsync.ios.discovery

import app.snapsync.ports.LibraryChangeToken
import platform.Photos.PHPersistentChangeToken

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
