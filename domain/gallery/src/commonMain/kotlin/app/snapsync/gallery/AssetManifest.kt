package app.snapsync.gallery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The asset-manifest schema version this build writes (capability `asset-manifest`, v1). */
const val ASSET_MANIFEST_VERSION: Int = 1

/** The manifest object's name within the event dir: `<assetId>.manifest.json`. */
fun manifestObjectName(assetId: String): String = "$assetId.manifest.json"

/**
 * One resource entry inside an [AssetManifest]: a generic [role], the resource's MIME [contentType],
 * its [filename] (the object name within the event — its storage key minus the `<eventId>/` prefix,
 * byte-identical to what the producer uploads under, see [uploadKey]), and the human [originalFilename]
 * as captured. v1 carries no other fields.
 */
@Serializable
class ManifestResource(
    val role: ResourceRole,
    val contentType: String,
    val filename: String,
    val originalFilename: String,
)

/**
 * The per-asset manifest (capability `asset-manifest`): the authoritative, platform-neutral
 * declaration of an asset's **complete** original resource set, uploaded once per asset to
 * `<eventId>/<assetId>.manifest.json`. A consumer learns the full expected set from this alone, and an
 * asset is **complete** only when every [resources] `filename` is present in storage.
 *
 * v1 is intentionally minimal — [version], [assetId], [creationDate] (ISO-8601 capture timestamp), and
 * a non-empty [resources] — with no location, flags, subtypes, or dimensions: everything needed to
 * reconstruct the asset is intrinsic to the original bytes. Because only originals are listed and
 * originals never change, a manifest is fixed at capture and never revised.
 */
@Serializable
class AssetManifest(
    val version: Int,
    val assetId: String,
    val creationDate: String,
    val resources: List<ManifestResource>,
)

/** Strict JSON for the manifest — declared fields only, so an unexpected field round-trips faithfully. */
private val manifestJson = Json { encodeDefaults = true }

/** Serialize the manifest to its `<assetId>.manifest.json` JSON bytes (as a UTF-8 string). */
fun AssetManifest.encodeToJson(): String = manifestJson.encodeToString(AssetManifest.serializer(), this)

/** Parse a manifest from its JSON text; throws on malformed JSON or a schema mismatch. */
fun assetManifestFromJson(text: String): AssetManifest =
    manifestJson.decodeFromString(AssetManifest.serializer(), text)
