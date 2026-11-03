package app.snapsync.domain.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole

/**
 * One resource's identity, in the upload ledger and in the byte store: the asset it belongs to, its role there, and the
 * extension its bytes are named with. Two resources of one photo (a Live Photo's still and motion) share the asset and
 * differ by role. Its text — `<assetId>-<role>.<ext>` — is the format's (`UploadKeyText`), written once, so the key a
 * row is stored under and the object name the bytes land under cannot drift.
 */
@ConsistentCopyVisibility
data class UploadKey private constructor(val asset: AssetId, val role: ResourceRole, val extension: String) {
    companion object {
        /** The key of [asset]'s [role] resource whose original file is named [originalFilename]. */
        fun of(asset: AssetId, role: ResourceRole, originalFilename: String): UploadKey =
            UploadKey(asset, role, originalFilename.substringAfterLast('.', "").ifEmpty { NO_EXTENSION }.lowercase())

        /** A key read back from its parts, as its text spelled them. */
        fun read(asset: AssetId, role: ResourceRole, extension: String): UploadKey = UploadKey(asset, role, extension)

        /** The extension of a file whose name carries none. */
        private const val NO_EXTENSION = "bin"
    }
}
