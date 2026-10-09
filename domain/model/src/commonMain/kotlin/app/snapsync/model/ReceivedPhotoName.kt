package app.snapsync.model

/**
 * The **filename** an imported foreign photo carries in the receiving device's library: the name the capturing device
 * gave it, with a SnapSync **mark** added before the extension — `IMG_4471.HEIC` arrives as
 * `IMG_4471.snapsync-k3f9x2qa7m.HEIC`.
 *
 * **Why a name at all.** The platform picks one whether or not we do. `PHAssetCreationRequest`'s
 * `addResource(with:fileURL:options:)` derives the resource's `originalFilename` from the **file URL's last path
 * component** when `options` is `nil` — and the file we hand it is staged under its storage object name,
 * `"<assetId>-<role>.<ext>"` (see [uploadKey]). So a downloaded photo used to land in the library named
 * `03C741F2-…_L0_001-primary.heic`: an internal key shown to the user as the photo's name. (Forcing proof:
 * PhotoKit's documented default for a `nil` options argument; expires only if that default changes.) The
 * sender's name rides the whole way from the uploader's manifest through the union into the download store, so the
 * import supplies it explicitly.
 *
 * **Why the mark.** The download store is the only record of which library photos came from SnapSync, and it is
 * deleted with the app. The photo library is not: a reinstalled app joining an event reads the marks of the photos
 * still in the library and recognises them as already received, so they are neither downloaded again nor shared
 * back as the member's own (decision record `changes/mark-received-photos`). The mark carries a [token] of the
 * source ref, never an id: it identifies WHICH shared photo this is without putting a device or asset id in a
 * name the user sees.
 *
 * **The token is a stored format.** Every photo marked by an earlier build is matched by recomputing [token] over
 * the union's refs, so changing the hash, its input string, its bit width or its alphabet silently stops every
 * existing mark from matching. `ReceivedPhotoNameTest` pins golden values for exactly that reason.
 *
 * Collisions of the human part are deliberately not resolved. Two devices both offering `IMG_0001.HEIC` is
 * ordinary, and the photo library keys assets by `localIdentifier`, not by name (Android appends ` (1)`, which
 * [tokenOf] tolerates).
 */
object ReceivedPhotoName {

    /** The token's length in base32 characters: 10 × 5 = 50 bits. */
    const val TOKEN_LENGTH: Int = 10

    private const val MARK = "snapsync-"
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val TOKEN_BITS = TOKEN_LENGTH * 5
    private const val FNV_OFFSET: ULong = 0xcbf29ce484222325uL
    private const val FNV_PRIME: ULong = 0x100000001b3uL

    /** A mark anywhere in a name: at its start (the no-sender-name form) or after a dot. */
    private val markPattern = Regex("(?:^|\\.)$MARK([a-z2-7]{$TOKEN_LENGTH})(?![a-z2-7])", RegexOption.IGNORE_CASE)

    /**
     * The mark's token for [ref]: FNV-1a 64 over the UTF-8 of `"<sourceDeviceId>/<sourceAssetId>"`, its low 50 bits
     * as lowercase RFC 4648 base32. Not a cryptographic hash, and it need not be one — a collision among one union's
     * refs only adopts one photo for the wrong ref.
     */
    fun token(ref: AssetRef): String {
        var hash = FNV_OFFSET
        for (byte in "${ref.sourceDeviceId}/${ref.sourceAssetId.value}".encodeToByteArray()) {
            hash = (hash xor (byte.toULong() and 0xffuL)) * FNV_PRIME
        }
        val bits = hash and ((1uL shl TOKEN_BITS) - 1uL)
        return buildString {
            for (group in TOKEN_LENGTH - 1 downTo 0) append(BASE32[((bits shr (group * 5)) and 0x1fuL).toInt()])
        }
    }

    /**
     * The marked name for a resource of [ref]: the sender's [originalFilename] with any mark it already carries
     * removed and this ref's mark added before its extension. [originalFilename] is `""` when the uploader's
     * manifest row was never enriched (one the join-time load seeded from a stored-file listing); the name is then
     * `snapsync-<token>.<ext>`, the extension taken from the storage [resourceKey]. Never empty.
     */
    fun mark(originalFilename: String, resourceKey: String, ref: AssetRef): String {
        val source = originalFilename.ifEmpty { resourceKey }
        val dot = source.lastIndexOf('.')
        val stem = if (originalFilename.isEmpty()) "" else if (dot > 0) source.substring(0, dot) else source
        val extension = if (dot > 0 && dot < source.length - 1) source.substring(dot) else ""
        val bare = stem.replace(markPattern, "")
        val mark = MARK + token(ref)
        return if (bare.isEmpty()) "$mark$extension" else "$bare.$mark$extension"
    }

    /** The token a marked [filename] carries (its rightmost mark, lowercased), or `null` for an unmarked name. */
    fun tokenOf(filename: String): String? =
        markPattern.findAll(filename).lastOrNull()?.let { it.groupValues[1].lowercase() }
}
