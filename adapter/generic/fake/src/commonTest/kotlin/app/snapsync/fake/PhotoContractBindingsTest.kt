package app.snapsync.fake

import app.snapsync.compose.PermissionAwareAssetPresence
import app.snapsync.compose.PermissionAwareCandidateSource
import app.snapsync.contracts.AlbumManagerContract
import app.snapsync.contracts.AlbumManagerState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CandidateSourceContract
import app.snapsync.contracts.CandidateSourceState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ImportedAssetPresenceContract
import app.snapsync.contracts.ImportedAssetPresenceState
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.MarkerState
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.PhotoLibraryImporterContract
import app.snapsync.contracts.PhotoLibraryImporterState
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.contracts.Seeded
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.UploadDiscoveryContract
import app.snapsync.contracts.UploadDiscoveryState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AssetRef
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UploadDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test

/**
 * The honest photo-library fakes, held to the contracts the PhotoKit adapters satisfy (capability
 * `port-contracts`). Where production composes a grant-aware layer over the library read, so do these
 * bindings, over the in-memory library instead of PhotoKit.
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

    private fun grant(granted: Boolean): StateFlow<PermissionStatus> =
        MutableStateFlow(if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_DETERMINED)

    private val noSelection: StateFlow<List<Resource>?> = MutableStateFlow(null)

    private val candidateSource = object : Binding<CandidateSourceState, Seeded<CandidateSource>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            CandidateSourceState.NO_GRANT,
            CandidateSourceState.GRANTED_SEEDED,
            CandidateSourceState.GRANTED_EMPTY_WINDOW,
        )

        override fun create(state: CandidateSourceState, clauseId: String): Entered<Seeded<CandidateSource>> {
            val library = when (state) {
                CandidateSourceState.GRANTED_SEEDED -> seededLibrary(CandidateSourceContract.name, clauseId)
                else -> MutableStateFlow(emptyList())
            }
            val source = PermissionAwareCandidateSource(
                permission = grant(state != CandidateSourceState.NO_GRANT),
                walk = inMemoryCandidateSource(library),
                selection = noSelection,
            )
            return Entered.Ready(Seeded(source, library.value.map { it.assetId }))
        }
    }

    private val uploadDiscovery = object : Binding<UploadDiscoveryState, Seeded<UploadDiscovery>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(UploadDiscoveryState.NO_GRANT, UploadDiscoveryState.GRANTED_SEEDED)

        override fun create(state: UploadDiscoveryState, clauseId: String): Entered<Seeded<UploadDiscovery>> {
            val granted = state == UploadDiscoveryState.GRANTED_SEEDED
            val library = if (granted) seededLibrary(UploadDiscoveryContract.name, clauseId) else MutableStateFlow(emptyList())
            val grant = grant(granted)
            return Entered.Ready(
                Seeded(
                    inMemoryUploadDiscovery(inMemoryCandidateSource(library), library) { grant.value },
                    library.value.map { it.assetId },
                ),
            )
        }
    }

    private val presence = object : Binding<ImportedAssetPresenceState, Seeded<ImportedAssetPresence>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(ImportedAssetPresenceState.NO_GRANT, ImportedAssetPresenceState.GRANTED_SEEDED)

        override fun create(
            state: ImportedAssetPresenceState,
            clauseId: String,
        ): Entered<Seeded<ImportedAssetPresence>> {
            val library = when (state) {
                ImportedAssetPresenceState.GRANTED_SEEDED -> seededLibrary(ImportedAssetPresenceContract.name, clauseId)
                ImportedAssetPresenceState.NO_GRANT -> MutableStateFlow(emptyList())
            }
            val composed = PermissionAwareAssetPresence(
                permission = grant(state == ImportedAssetPresenceState.GRANTED_SEEDED),
                library = inMemoryLibraryPresence(library, MutableStateFlow(true)),
                selection = noSelection,
            )
            return Entered.Ready(Seeded(composed, library.value.map { it.assetId }))
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PhotoAccessState.NO_GRANT, PhotoAccessState.GRANTED)

        override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
            val cell = MutableStateFlow(
                if (state == PhotoAccessState.GRANTED) PermissionStatus.GRANTED else PermissionStatus.NOT_DETERMINED,
            )
            val (status, requester) = inMemoryPhotoAccess(cell)
            return Entered.Ready(PhotoAccess(status, requester))
        }
    }

    private val albumManager = object : Binding<AlbumManagerState, Seeded<AlbumManager>> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(AlbumManagerState.GRANTED_SEEDED)

        override fun create(state: AlbumManagerState, clauseId: String): Entered<Seeded<AlbumManager>> {
            val library = seededLibrary(AlbumManagerContract.name, clauseId)
            return Entered.Ready(Seeded(inMemoryAlbumManager(library), library.value.map { it.assetId }))
        }
    }

    private val importer = object : Binding<PhotoLibraryImporterState, StagedImport> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(PhotoLibraryImporterState.GRANTED_VALID_STAGED)

        override fun create(state: PhotoLibraryImporterState, clauseId: String): Entered<StagedImport> {
            if (state == PhotoLibraryImporterState.GRANTED_INVALID_STAGED) {
                return Entered.Unreachable("an in-memory library holds no bytes, so it cannot find one undecodable")
            }
            val library = MutableStateFlow<List<RawAsset>>(emptyList())
            val markers = mutableMapOf<AssetRef, MarkerState>()
            val importer = inMemoryPhotoLibraryImporter(
                library = library,
                recordCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.RECORDED; true },
                clearCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.CLEARED },
                confirmCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.CONFIRMED },
            )
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

    @Test
    fun `the in-memory candidate source satisfies the CandidateSource contract`() =
        verify(CandidateSourceContract, candidateSource)

    @Test
    fun `the in-memory upload discovery satisfies the UploadDiscovery contract`() =
        verify(UploadDiscoveryContract, uploadDiscovery)

    @Test
    fun `the in-memory presence satisfies the ImportedAssetPresence contract`() =
        verify(ImportedAssetPresenceContract, presence)

    @Test
    fun `the in-memory photo access satisfies the PhotoAccess contract`() =
        verify(PhotoAccessContract, photoAccess)

    @Test
    fun `the in-memory album manager satisfies the AlbumManager contract`() =
        verify(AlbumManagerContract, albumManager)

    @Test
    fun `the in-memory importer satisfies the PhotoLibraryImporter contract`() =
        verify(PhotoLibraryImporterContract, importer)
}
