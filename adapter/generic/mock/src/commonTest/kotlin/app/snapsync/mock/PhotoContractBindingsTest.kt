package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FolderAlbumContract
import app.snapsync.contracts.FolderAlbumState
import app.snapsync.contracts.FolderAlbums
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryImportContract
import app.snapsync.contracts.GalleryImportState
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.ImportDeliveries
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.AlbumKind
import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.ports.GalleryReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

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
                    assetId = AssetId("contract-$clauseId-$n"),
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
            GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS,
        )

        override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> {
            if (state == GalleryReaderState.GRANTED_SEEDED_EXPORTING) {
                return Entered.Unreachable("an in-memory library holds no bytes to export")
            }
            if (state == GalleryReaderState.REFUSING_WRITES) {
                return Entered.Unreachable("the double takes every write")
            }
            val library = when (state) {
                GalleryReaderState.GRANTED_SEEDED, GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS ->
                    seededLibrary(GalleryReaderContract.name, clauseId)
                GalleryReaderState.GRANTED_SEEDED_IN_A_FOLDER, GalleryReaderState.GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY ->
                    return Entered.Unreachable("the in-memory library has no folders: all of it is the default gallery")
                GalleryReaderState.NEVER_ASKED, GalleryReaderState.REFUSED ->
                    return Entered.Unreachable("the double keeps no record of having asked")
                GalleryReaderState.NO_GRANT, GalleryReaderState.GRANTED_EMPTY_WINDOW, GalleryReaderState.REFUSING_WRITES -> MutableStateFlow(
                    emptyList(),
                )
            }
            val gallery = inMemoryGallery(library, access(state != GalleryReaderState.NO_GRANT)).recorded(log)
            return Entered.Ready(SeededLibrary(gallery, library.value.mapTo(linkedSetOf()) { it.assetId }))
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PhotoAccessState.NO_GRANT, PhotoAccessState.GRANTED)

        override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> {
            if (state !in reaches) return Entered.Unreachable("the double plays no partial grant here")
            val cell = MutableStateFlow(
                if (state == PhotoAccessState.GRANTED) GalleryAccess.GRANTED else GalleryAccess.NOT_DETERMINED,
            )
            return Entered.Ready(PhotoAccess(inMemoryPhotoAccess(cell).recorded(log)))
        }
    }

    private val importer = object : Binding<GalleryImportState, StagedImport> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(GalleryImportState.GRANTED_VALID_STAGED)

        override fun create(state: GalleryImportState, clauseId: String, log: CallLog): Entered<StagedImport> {
            if (state == GalleryImportState.GRANTED_INVALID_STAGED) {
                return Entered.Unreachable("an in-memory library holds no bytes, so it cannot find one undecodable")
            }
            val library = MutableStateFlow<List<RawAsset>>(emptyList())
            val deliveries = ImportDeliveries()
            val importer = inMemoryGallery(library).recorded(log).apply { listen(deliveries.handlers) }
            val observed = object : ImportedLibrary {
                override suspend fun captureDate(id: AssetId): String? =
                    library.value.firstOrNull { it.facts.assetId == id }?.creationDate

                override val deliveries = deliveries

                override suspend fun primaryFilename(id: AssetId): String? =
                    library.value.firstOrNull { it.facts.assetId == id }
                        ?.rawResources?.firstOrNull { it.role == ResourceRole.PRIMARY }?.originalFilename
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

    /** The library mock playing an Android library, whose albums are the folders its photos live in. */
    private val folderAlbums = object : Binding<FolderAlbumState, FolderAlbums> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED)

        override fun create(state: FolderAlbumState, clauseId: String, log: CallLog): Entered<FolderAlbums> {
            val library = seededLibrary(FolderAlbumContract.name, clauseId)
            val mock = PhotoLibraryMock().apply {
                operator.albumKind = AlbumKind.FOLDER
                operator.set(library.value)
            }
            val seeded = library.value.mapTo(linkedSetOf()) { it.assetId }
            // The seeded photos are this app's own saves, as the emulator binding's are: the ones it may move.
            mock.state.ownImports += seeded
            val gallery = mock.port().recorded(log).apply { listen(ImportDeliveries().handlers) }
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
            return Entered.Ready(FolderAlbums(gallery, seeded, staged))
        }
    }

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(GalleryState.GRANTED)

        override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
            if (state !in reaches) return Entered.Unreachable("the double plays the full grant here")
            val library = seededLibrary(GalleryContract.name, clauseId)
            val added = RawAsset(
                assetId = AssetId("contract-$clauseId-change"),
                creationDate = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate,
                rawResources = listOf(RawResource(ResourceRole.PRIMARY, "image/jpeg", "IMG_0009.JPG", Unit)),
            )
            return Entered.Ready(
                GalleryChange(
                    inMemoryGallery(library, access(granted = true)).recorded(log),
                ) { library.value += added },
            )
        }
    }

    @Test
    fun `the in-memory gallery satisfies the GalleryReader contract`() =
        verify(GalleryReaderContract, galleryReader)

    @Test
    fun `the library mock with folder albums satisfies the FolderAlbum contract`() =
        verify(FolderAlbumContract, folderAlbums)

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
