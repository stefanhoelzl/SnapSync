package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.MarkerState
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.GalleryImportContract
import app.snapsync.contracts.GalleryImportState
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.AssetRef
import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryReader
import app.snapsync.model.ImportResult
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The honest photo-library fakes, held to the contracts the PhotoKit adapters satisfy (`docs/architecture.md`).
 */
class PhotoContractBindingsTest {

    /** A library cell holding [SEED_COUNT] ordinary photos in [clauseId]'s window of [contract]. */
    private fun seededLibrary(contract: String, clauseId: String): MutableStateFlow<List<RawAsset>> {
        val date = PhotoLibrary.window(contract, clauseId).seedDate
        return MutableStateFlow(
            (1..SEED_COUNT).map { n ->
                RawAsset(
                    assetId = "contract-$clauseId-$n",
                    creationDate = date,
                    rawResources = listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG_000$n.JPG", Unit)),
                )
            },
        )
    }

    private fun access(granted: Boolean): MutableStateFlow<GalleryAccess> =
        MutableStateFlow(if (granted) GalleryAccess.GRANTED else GalleryAccess.NOT_DETERMINED)

    private val galleryReader = object : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            GalleryReaderState.NO_GRANT,
            GalleryReaderState.GRANTED_SEEDED,
            GalleryReaderState.GRANTED_EMPTY_WINDOW,
        )

        override fun create(state: GalleryReaderState, clauseId: String): Entered<SeededLibrary<GalleryReader>> {
            val library = when (state) {
                GalleryReaderState.GRANTED_SEEDED -> seededLibrary(GalleryReaderContract.name, clauseId)
                else -> MutableStateFlow(emptyList())
            }
            val gallery = inMemoryGallery(library, access(state != GalleryReaderState.NO_GRANT))
            return Entered.Ready(SeededLibrary(gallery, library.value.map { it.assetId }))
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PhotoAccessState.NO_GRANT, PhotoAccessState.GRANTED)

        override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
            val cell = MutableStateFlow(
                if (state == PhotoAccessState.GRANTED) GalleryAccess.GRANTED else GalleryAccess.NOT_DETERMINED,
            )
            return Entered.Ready(PhotoAccess(inMemoryPhotoAccess(cell)))
        }
    }

    private val importer = object : Binding<GalleryImportState, StagedImport> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(GalleryImportState.GRANTED_VALID_STAGED)

        override fun create(state: GalleryImportState, clauseId: String): Entered<StagedImport> {
            if (state == GalleryImportState.GRANTED_INVALID_STAGED) {
                return Entered.Unreachable("an in-memory library holds no bytes, so it cannot find one undecodable")
            }
            val library = MutableStateFlow<List<RawAsset>>(emptyList())
            val markers = mutableMapOf<AssetRef, MarkerState>()
            val importer = inMemoryGallery(library).apply { listen(markerHandlers(markers)) }
            val observed = object : ImportedLibrary {
                override suspend fun captureDate(id: String): String? =
                    library.value.firstOrNull { it.facts.assetId == id }?.creationDate

                override fun marker(ref: AssetRef): MarkerState = markers[ref] ?: MarkerState.NONE
            }
            val staged = {
                listOf(
                    StagedResource(
                    resourceKey = "contract-$clauseId-primary.jpg",
                    role = ResourceRole.PRIMARY.wire,
                    contentType = "image/jpeg",
                    originalFilename = "IMG_0001.JPG",
                        stagedPath = "staged:/contract-$clauseId-primary.jpg",
                    ),
                )
            }
            return Entered.Ready(StagedImport(importer, staged, observed))
        }
    }

    /** Import handlers that record where each ref's marker stands — what a clause observes. */
    private fun markerHandlers(markers: MutableMap<AssetRef, MarkerState>) = GalleryHandlers(
        onChanged = {},
        onImportPlaceholder = { ref, _ -> markers[ref] = MarkerState.RECORDED },
        onImportSettled = { ref, outcome ->
            when (outcome) {
                is ImportResult.Imported -> markers[ref] = MarkerState.CONFIRMED
                is ImportResult.Failed -> if (outcome.placeholder != null) markers[ref] = MarkerState.CLEARED
            }
        },
    )

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(GalleryState.GRANTED)

        override fun create(state: GalleryState, clauseId: String): Entered<GalleryChange> {
            val library = seededLibrary(GalleryContract.name, clauseId)
            val added = RawAsset(
                assetId = "contract-$clauseId-change",
                creationDate = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate,
                rawResources = listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG_0009.JPG", Unit)),
            )
            return Entered.Ready(GalleryChange(inMemoryGallery(library, access(granted = true))) { library.value += added })
        }
    }

    @Test
    fun `the in-memory gallery satisfies the GalleryReader contract`() =
        verify(GalleryReaderContract, galleryReader)

    @Test
    fun `the in-memory gallery satisfies the Gallery contract`() =
        verify(GalleryContract, gallery)

    @Test
    fun `the in-memory photo access satisfies the PhotoAccess contract`() =
        verify(PhotoAccessContract, photoAccess)

    @Test
    fun `the in-memory gallery satisfies the GalleryImport contract`() =
        verify(GalleryImportContract, importer)
}
