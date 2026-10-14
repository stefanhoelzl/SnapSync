package app.snapsync.rig

import app.snapsync.domain.asset.AssetId
import app.snapsync.levers.CensusView
import app.snapsync.levers.GalleryView
import app.snapsync.levers.SeedKind
import app.snapsync.levers.SeedOutcome
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.rig.gallery.GalleryReport
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.services.gallery.PermissionAwareCandidateSource
import app.snapsync.services.gallery.resourcesFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// The photo library's mock as the gallery service reads and seeds it, and a fellow member's assets as the backend's
// public surface writes them.

/**
 * `gallery.seed` over the photo library's mock: [n] photos of the seed kind, dated as the app host dates
 * its PhotoKit seed relative to its event window. `BULK` is dated long before any event (the walk-cost seed); `POLICY`
 * alternates above and below the image floor inside the default event window, so only the floor separates them.
 * `NOISE` exists to put bytes on the wire, which the mock does not carry, so it is refused rather than silently seeded
 * as something else.
 */
@OptIn(ExperimentalUuidApi::class)
fun MockWorld.seedMockLibrary(n: Int, kind: SeedKind): SeedOutcome {
    if (kind == SeedKind.NOISE) {
        refused("the mocked photos carry no bytes, so a seed for bytes on the wire would measure nothing")
    }
    val library = device.library
    repeat(n) { i ->
        val id = Uuid.random().toString()
        library.add(
            when {
                kind == SeedKind.BULK -> LibraryAssets.photo(id, creationDate = BULK_DATE)
                i % 2 == 0 -> LibraryAssets.photo(id)
                else -> LibraryAssets.lowResPhoto(id)
            },
        )
    }
    return SeedOutcome(requested = n, created = n, kind = kind, failedAtChunk = null)
}

/**
 * The gallery read over the photo library's mock — what it answers a reader under the person's grant, through the
 * selection policy: the library under a full grant, the person's selection under a partial one, nothing without a
 * grant. Read off the mock, never the composed app — the same candidate reads and policy the app uses, over the
 * operating system's own state.
 */
fun MockWorld.mockGalleryReader(): suspend (
    String?,
    Boolean,
    Boolean,
) -> GalleryView = { cutoff, resources, includesUpload ->
    val library = device.library
    val reader = GalleryReport(
        candidates = PermissionAwareCandidateSource(
            permission = GalleryAccessState(library.grant).grant,
            walk = GalleryCandidateSource(library.port()),
            // Read per request, as the rest of this reader is.
            selection = MutableStateFlow(library.selection.value?.let(::resourcesFrom)),
        ),
        grant = { library.grant.value.name },
        census = {
            val facts = library.contents.value.map { it.asset }
            CensusView(
                total = facts.size.toLong(),
                screenshots = facts.count { it.isScreenshot }.toLong(),
                screenRecordings = facts.count { it.isScreenRecording }.toLong(),
            )
        },
    )
    reader.read(cutoff, resources, includesUpload)
}

/** A fellow member with a primary resource per asset, captured on [filename]. */
internal fun foreignAsset(
    assetId: String,
    filename: String = "IMG.HEIC",
    contentType: String = "image/heic",
): DeviceManifestAsset {
    val key = uploadKey(AssetId(assetId), ResourceRole.PRIMARY, filename)
    return DeviceManifestAsset(
        assetId = AssetId(assetId),
        creationDate = LibraryAssets.DEFAULT_DATE,
        resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
    )
}

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"
