package app.snapsync.rig

import app.snapsync.data.sharing.DenylistedAlbums
import app.snapsync.data.sharing.GalleryWalk
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.levers.CensusView
import app.snapsync.levers.GalleryView
import app.snapsync.levers.SeedKind
import app.snapsync.levers.SeedOutcome
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.uploadKey
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.rig.gallery.GalleryReport
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
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
        photos = mockOwnPhotos(),
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

/**
 * The app's own photos over the photo library's mock, built per request as the rest of this reader is: the same read
 * plan, walk and policy the app takes, its selection the person's current one.
 */
private suspend fun MockWorld.mockOwnPhotos(): OwnPhotos {
    val library = device.library
    val clock = WallClock(device.clock.port())
    val selection = SelectionSnapshots()
    selection.published(flowOf(library.selection.value?.let(::SelectionSnapshot)), beforePublish = {}).collect()
    return OwnPhotos(
        library.port(),
        GalleryWalk(library.port(), clock),
        selection,
        DenylistedAlbums(library.port()),
        clock,
    )
}

/** A fellow member with a primary resource per asset, captured on [filename]. */
internal fun foreignAsset(
    assetId: String,
    filename: String = "IMG.HEIC",
    contentType: String = "image/heic",
): DeviceManifestAsset {
    val key = uploadKey(AssetId.parse(assetId)!!, ResourceRole.PRIMARY, filename)
    return DeviceManifestAsset(
        assetId = AssetId.parse(assetId)!!,
        creationDate = LibraryAssets.DEFAULT_DATE,
        resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
    )
}

/** The app host's bulk-seed date: 2001-09-09, before any event can start. */
private const val BULK_DATE = "2001-09-09T01:46:40Z"
