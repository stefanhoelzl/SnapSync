package app.snapsync.ports

import kotlin.time.Instant

/**
 * One asset to create in the library from files already on this device: its [files], captured at [created] (`null`: the
 * platform's own default), filed into [album] in the same transaction (`null`: no album). [correlation] is opaque to the
 * platform and handed back with both of the import's deliveries ([GalleryHandlers]).
 */
class GalleryImportRequest(
    val correlation: String,
    val files: List<ImportFile>,
    val created: Instant?,
    val album: PlatformAlbumId?,
)

/** One file of an asset to import, at the platform [path] its bytes are in. */
class ImportFile(
    val path: String,
    val contentType: String,
    val kind: ResourceKind,
    /**
     * The name the library stores it under — final: an adapter that converts a file's format replaces only its
     * extension, and a file it derives from this one (a paired video taken out of a motion photo) takes this name with
     * its own extension.
     */
    val name: String,
)

/** What an import did, as the platform reported it. */
sealed interface ImportOutcome {
    /** The asset was created as [created]. */
    data class Imported(val created: PlatformAssetId) : ImportOutcome

    /**
     * The import did not complete — the platform reported the change failed. [consumedResources]: the platform took
     * the files before it failed (it ingests a resource before validating it), so nothing is left to retry from; an
     * adapter that cannot tell answers `false`. [placeholder]: the asset the change block had reported creating, or
     * `null` when it reported none.
     */
    data class Failed(
        val message: String,
        val consumedResources: Boolean = false,
        val placeholder: PlatformAssetId? = null,
    ) : ImportOutcome
}
