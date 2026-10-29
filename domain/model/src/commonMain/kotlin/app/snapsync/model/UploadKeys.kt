package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.format.ResourceRoleText

// A platform resource-type value is read by the platform, in `:adapter:ios:ext-safe`'s
// `PhotoKitResourceKind.kt`, and what each kind is to the upload is the gallery services'. It used to sit here as a `when` over the raw
// `PHAssetResourceType` integers — an ABI table written in bare literals, which no import- or
// token-based gate can see and which a second platform's integers would silently collide with. The
// adapter now reports the role itself, which is the platform-independent fact (spec
// `docs/architecture.md`, "Ports are the I/O boundary named for the need").

/**
 * Pure construction of an asset resource's ledger key / object name — the single place the role-based
 * `"<assetId>-<role>.<ext>"` layout lives, `assetId` being the canonical [AssetId]. Kept platform-free so
 * the layout is unit-tested in `commonTest` instead of trapped inside the PhotoKit adapter; the adapter (and
 * the device listing's seam) only supply the raw fields. Shared by the upload producer (`:app:ios:extension`) and the manifest synthesis
 * so a manifest's `filename` is byte-identical to what the producer uploads under.
 */
fun uploadKey(assetId: AssetId, role: ResourceRole, originalFilename: String): String =
    "$assetId-${ResourceRoleText.of(role)}.${fileExtension(originalFilename)}"

/** The lowercased filename extension, or `bin` when the original filename carries none. */
fun fileExtension(originalFilename: String): String =
    originalFilename.substringAfterLast('.', "").ifEmpty { "bin" }.lowercase()

/**
 * Keys under which the iOS resource enumerator stashes the per-asset manifest detail in
 * [app.snapsync.engine.Resource.metadata] (opaque to the engine), so the device-manifest producer can
 * build its entries from the **cycle's existing discovery** instead of a second PhotoKit enumeration.
 */
const val RESOURCE_META_CREATION_DATE: String = "creationDate"
const val RESOURCE_META_ORIGINAL_FILENAME: String = "originalFilename"
const val RESOURCE_META_MIME: String = "mimeContentType"

/**
 * Recover the [ResourceRole] from an upload-key [filename] (`"<assetId>-<role>.<ext>"`): the role token
 * is the segment after the **last** `-` and before the `.` (`primary`/`live`; the role token carries
 * no `-`, though an `assetId` may). Defaults to [ResourceRole.PRIMARY] for an unrecognised token (never
 * produced by [uploadKey]).
 */
fun roleFromUploadKey(filename: String): ResourceRole {
    val wire = filename.substringBeforeLast('.').substringAfterLast('-')
    return ResourceRoleText.parse(wire) ?: ResourceRole.PRIMARY
}

/**
 * Recover the `assetId` from an upload-key [filename] (`"<assetId>-<role>.<ext>"`): drop the extension,
 * then take everything before the **final** `-` (the role token `primary`/`live` carries no `-`, though
 * an `assetId` may). The exact inverse of [uploadKey] — `assetIdFromUploadKey(uploadKey(id, role, name))
 * == id` — and the single shared implementation of that parse, so every site holding only a key (the
 * upload-job reconstruction, the discovery's key-to-asset lookup) recovers the same identity (the parse
 * is load-bearing at the record path). The join-time load is not one of them: it seeds the `assetId`
 * the backend's listing reports. Kept here next to [uploadKey], never duplicated per-consumer.
 */
fun assetIdFromUploadKey(filename: String): AssetId =
    requireNotNull(AssetId.parse(filename.substringBeforeLast('.').substringBeforeLast('-'))) {
        "not a canonical asset id"
    }
