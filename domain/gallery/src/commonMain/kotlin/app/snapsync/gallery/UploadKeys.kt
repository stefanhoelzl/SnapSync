package app.snapsync.gallery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The generic, platform-neutral **role** of an asset resource (capability `asset-manifest`). The role
 * carries the resource's place in its asset, never the platform resource-type name or the media kind
 * (that is `contentType`'s job): [PRIMARY] is the single original primary medium (a still image, a
 * video, or an audio track) and [LIVE] the original paired video of a Live Photo. [wire] is the
 * lowercase token used in object keys and serialized in the manifest (`primary`/`live`).
 */
@Serializable
enum class ResourceRole(val wire: String) {
    @SerialName("primary")
    PRIMARY("primary"),

    @SerialName("live")
    LIVE("live"),
}

/**
 * Map a `PHAssetResourceType` raw value (its raw values are a stable ABI) to the generic [ResourceRole]
 * we upload, or `null` when the resource is **dropped** — a non-original edit artifact (full-size
 * renders, adjustment data, adjustment-base media), the RAW `alternatePhoto`, or a proxy. Only the
 * originals are kept, so an asset's resource set is fixed at capture and never grows: `photo`/`video`/
 * `audio` → [PRIMARY], `pairedVideo` → [LIVE].
 */
fun resourceRole(resourceType: Long): ResourceRole? = when (resourceType) {
    1L, 2L, 3L -> ResourceRole.PRIMARY // photo, video, audio (the asset's single original primary)
    9L -> ResourceRole.LIVE // pairedVideo (the Live Photo's original paired video)
    else -> null // 4 alternatePhoto(RAW), 5/6 fullSize*, 7 adjustmentData, 8 adjustmentBase*, 10 …, proxies
}

/**
 * Normalize a raw PHAsset `localIdentifier` into the `assetId` used in keys and the suppression match:
 * `/`→`_` so the identifier is a single slash-free path segment (the edge endpoint rejects a decoded
 * `/`). This is the **single** definition of that transform on the discovery/enumeration side — the
 * upload producer and the join enumerator normalize through it — and it is **load-bearing** for
 * echo-suppression: the download importer normalizes the imported asset's `createdLocalId` the *same*
 * way (`IosPhotoLibraryImporter`), so a discovered `assetId` meets its stored `createdLocalId` and the
 * asset is suppressed. Keep the two transforms identical or the echo re-uploads.
 */
fun normalizeAssetId(rawLocalIdentifier: String): String = rawLocalIdentifier.replace('/', '_')

/**
 * Recover the raw PHAsset `localIdentifier` from a normalized [assetId] — the inverse of
 * [normalizeAssetId] — for the event-album upload-add path (capability `event-album`), which must
 * `PHAsset.fetchAssetsWithLocalIdentifiers` a completed upload whose ledger key only carries the
 * normalized id. The reversal (`_`→`/`) is **exact** for real identifiers: a `localIdentifier` is
 * `{UUID}/L0/NNN` (UUID = hex + hyphen, never `_`), so the only underscores present are the ones that
 * were slashes — the same `_`-free invariant the whole key round-trip (`assetIdFromUploadKey`) already
 * relies on. A hypothetical native `_` would fetch nothing and the add is best-effort (skips), so this
 * never mis-adds.
 */
fun denormalizeAssetId(assetId: String): String = assetId.replace('_', '/')

/**
 * Pure construction of an asset resource's ledger key / object name — the single place the role-based
 * `"<assetId>-<role>.<ext>"` layout lives, where `assetId` is the PHAsset's `localIdentifier` (v1,
 * single-device) with `/`→`_` (via [normalizeAssetId]). Kept platform-free so the layout is unit-tested on the simulator
 * instead of trapped inside the PhotoKit adapter; the adapter (and the re-join seed) only supply the
 * raw fields. Shared by the upload producer (`:app:ios:photokit-extension`) and the manifest synthesis
 * so a manifest's `filename` is byte-identical to what the producer uploads under.
 */
fun uploadKey(assetId: String, role: ResourceRole, originalFilename: String): String =
    "$assetId-${role.wire}.${fileExtension(originalFilename)}"

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
    return ResourceRole.entries.firstOrNull { it.wire == wire } ?: ResourceRole.PRIMARY
}

/**
 * Recover the `assetId` from an upload-key [filename] (`"<assetId>-<role>.<ext>"`): drop the extension,
 * then take everything before the **final** `-` (the role token `primary`/`live` carries no `-`, though
 * an `assetId` may). The exact inverse of [uploadKey] — `assetIdFromUploadKey(uploadKey(id, role, name))
 * == id` — and the single shared implementation of that parse, so the upload-job reconstruction and the
 * re-join reconciler recover the same identity from a key (the parse is load-bearing at the record
 * path). Kept here in `:domain:gallery` next to [uploadKey], never duplicated per-consumer.
 */
fun assetIdFromUploadKey(filename: String): String =
    filename.substringBeforeLast('.').substringBeforeLast('-')

