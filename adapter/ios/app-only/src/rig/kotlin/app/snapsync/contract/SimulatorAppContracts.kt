@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

import app.snapsync.album.IosAlbumManager
import app.snapsync.compose.PermissionAwareAssetPresence
import app.snapsync.compose.PermissionAwareCandidateSource
import app.snapsync.contracts.AlbumManagerContract
import app.snapsync.contracts.AlbumManagerState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CandidateSourceContract
import app.snapsync.contracts.CandidateSourceState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ImportedAssetPresenceContract
import app.snapsync.contracts.ImportedAssetPresenceState
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.MarkerState
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.PhotoLibraryImporterContract
import app.snapsync.contracts.PhotoLibraryImporterState
import app.snapsync.contracts.ProtectedStorageContract
import app.snapsync.contracts.ProtectedStorageState
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.UploadDiscoveryContract
import app.snapsync.contracts.UploadDiscoveryState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.simulatorAppContract
import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.download.PhotoKitAssetPresence
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.denormalizeAssetId
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AssetRef
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.ProtectedStorage
import app.snapsync.protection.IosProtectedStorage
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHPhotoLibrary

/**
 * The simulator app's live bindings of the photo-library contracts (capability `port-contracts`), and the
 * registry the `ios-contracts` job runs on every push.
 *
 * This is where PhotoKit runs under a real full grant: the app bundle is the only simulator process
 * `applesimutils` can grant photo access to. Every binding declares the `GRANTED` states only, and every entry
 * refuses its whole run in any other process or grant (capability `port-contracts`, "An authorization the process
 * cannot give itself is a precondition of the run"). The no-grant states run on the simulator's test executable
 * instead.
 *
 * **Seeding never deletes.** Each clause seeds into its own capture-date window ([PhotoLibrary.window]) through
 * PhotoKit's own creation API. Deleting would raise a system confirmation someone has to tap, and the CI job
 * starts every run from a fresh simulator.
 */
fun simulatorAppContracts(): List<InAppContract> = listOf(
    simulatorAppContract(CandidateSourceContract, SimAppCandidateSourceBinding(), ::refusal),
    simulatorAppContract(UploadDiscoveryContract, SimAppUploadDiscoveryBinding(), ::refusal),
    simulatorAppContract(ImportedAssetPresenceContract, SimAppAssetPresenceBinding(), ::refusal),
    simulatorAppContract(PhotoAccessContract, SimAppPhotoAccessBinding(), ::refusal),
    simulatorAppContract(AlbumManagerContract, SimAppAlbumManagerBinding(), ::refusal),
    simulatorAppContract(PhotoLibraryImporterContract, SimAppImporterBinding(), ::refusal),
    simulatorAppContract(ProtectedStorageContract, SimAppProtectedStorageBinding(), ::hostRefusal),
)

/** Why this process is not the simulator app, or `null` when it is — for bindings that need no photo grant. */
private fun hostRefusal(): String? = if (currentHost != Host.IOS_SIM_APP) {
    "this process is $currentHost, not ${Host.IOS_SIM_APP}; the simulator app's bindings run only there"
} else {
    null
}

/** Why this process cannot run the simulator app's bindings, or `null` when it can. */
private fun refusal(): String? = hostRefusal() ?: when {
    currentPhotoPermission() != PermissionStatus.GRANTED ->
        "the simulator app holds ${currentPhotoPermission()}, not GRANTED; grant photo access with applesimutils " +
            "before launch (simctl privacy writes a TCC row PhotoKit does not consult)"
    else -> null
}

private const val UNREACHABLE_NO_GRANT = "the simulator app runs under the full grant; the no-grant state runs on the test executable"

private val noSelection = MutableStateFlow<List<Resource>?>(null)

/** Creates [SEED_COUNT] ordinary photos captured at [seedDate]; answers their raw `localIdentifier`s. */
private fun seedPhotos(seedDate: String): List<String> = memScoped {
    val created = mutableListOf<String>()
    val date = NSISO8601DateFormatter().dateFromString(seedDate) ?: error("unparseable seed date $seedDate")
    val error = alloc<ObjCObjectVar<NSError?>>()
    PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
        changeBlock = {
            repeat(SEED_COUNT) {
                val request = PHAssetCreationRequest.creationRequestForAsset()
                request.addResourceWithType(PHAssetResourceTypePhoto, data = PhotoLibrary.jpeg.toNSData(), options = null)
                request.setCreationDate(date)
                request.placeholderForCreatedAsset?.localIdentifier?.let(created::add)
            }
        },
        error = error.ptr,
    )
    error.value?.let { error("seeding failed: ${it.domain} ${it.code} ${it.localizedDescription}") }
    check(created.size == SEED_COUNT) { "seeding created ${created.size} of $SEED_COUNT photos" }
    created
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

class SimAppCandidateSourceBinding : Binding<CandidateSourceState, SeededLibrary<CandidateSource>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(CandidateSourceState.GRANTED_SEEDED, CandidateSourceState.GRANTED_EMPTY_WINDOW)

    override fun create(state: CandidateSourceState, clauseId: String): Entered<SeededLibrary<CandidateSource>> {
        val seeded = when (state) {
            CandidateSourceState.NO_GRANT -> return Entered.Unreachable(UNREACHABLE_NO_GRANT)
            CandidateSourceState.GRANTED_SEEDED ->
                seedPhotos(PhotoLibrary.window(CandidateSourceContract.name, clauseId).seedDate)
            CandidateSourceState.GRANTED_EMPTY_WINDOW -> emptyList()
        }
        val source = PermissionAwareCandidateSource(
            permission = MutableStateFlow(currentPhotoPermission()),
            walk = PhotoKitCandidateSource(),
            selection = noSelection,
        )
        return Entered.Ready(SeededLibrary(source, seeded))
    }
}

class SimAppUploadDiscoveryBinding : Binding<UploadDiscoveryState, SeededLibrary<UploadDiscovery>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(UploadDiscoveryState.GRANTED_SEEDED)

    override fun create(state: UploadDiscoveryState, clauseId: String): Entered<SeededLibrary<UploadDiscovery>> {
        if (state == UploadDiscoveryState.NO_GRANT) return Entered.Unreachable(UNREACHABLE_NO_GRANT)
        val seeded = seedPhotos(PhotoLibrary.window(UploadDiscoveryContract.name, clauseId).seedDate)
        return Entered.Ready(SeededLibrary(IosDiscovery(Logger.withTag("contract"), PhotoKitCandidateSource()), seeded))
    }
}

class SimAppAssetPresenceBinding : Binding<ImportedAssetPresenceState, SeededLibrary<ImportedAssetPresence>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(ImportedAssetPresenceState.GRANTED_SEEDED)

    override fun create(state: ImportedAssetPresenceState, clauseId: String): Entered<SeededLibrary<ImportedAssetPresence>> {
        if (state == ImportedAssetPresenceState.NO_GRANT) return Entered.Unreachable(UNREACHABLE_NO_GRANT)
        val seeded = seedPhotos(PhotoLibrary.window(ImportedAssetPresenceContract.name, clauseId).seedDate)
        val presence = PermissionAwareAssetPresence(
            permission = MutableStateFlow(currentPhotoPermission()),
            library = PhotoKitAssetPresence(),
            selection = noSelection,
        )
        return Entered.Ready(SeededLibrary(presence, seeded))
    }
}

class SimAppPhotoAccessBinding : Binding<PhotoAccessState, PhotoAccess> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(PhotoAccessState.GRANTED)

    override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
        if (state == PhotoAccessState.NO_GRANT) return Entered.Unreachable(UNREACHABLE_NO_GRANT)
        val adapter = PhotoLibraryPermission()
        return Entered.Ready(PhotoAccess(adapter, adapter))
    }
}

class SimAppAlbumManagerBinding : Binding<AlbumManagerState, SeededLibrary<AlbumManager>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(AlbumManagerState.GRANTED_SEEDED)

    override fun create(state: AlbumManagerState, clauseId: String): Entered<SeededLibrary<AlbumManager>> {
        val seeded = seedPhotos(PhotoLibrary.window(AlbumManagerContract.name, clauseId).seedDate)
        return Entered.Ready(SeededLibrary(IosAlbumManager(), seeded))
    }
}

class SimAppImporterBinding : Binding<PhotoLibraryImporterState, StagedImport> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(
        PhotoLibraryImporterState.GRANTED_VALID_STAGED,
        PhotoLibraryImporterState.GRANTED_INVALID_STAGED,
    )

    override fun create(state: PhotoLibraryImporterState, clauseId: String): Entered<StagedImport> {
        val bytes = when (state) {
            PhotoLibraryImporterState.GRANTED_VALID_STAGED -> PhotoLibrary.jpeg
            PhotoLibraryImporterState.GRANTED_INVALID_STAGED -> PhotoLibrary.notAnImage
        }
        val markers = mutableMapOf<AssetRef, MarkerState>()
        val importer = IosPhotoLibraryImporter(
            recordCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.RECORDED; true },
            clearCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.CLEARED },
            confirmCreatedLocalId = { ref, _ -> markers[ref] = MarkerState.CONFIRMED },
            log = Logger.withTag("contract"),
        )
        var staged = 0
        val stage = {
            staged++
            val key = "contract-$clauseId-$staged-primary.jpg"
            val path = NSTemporaryDirectory() + key
            check(bytes.toNSData().writeToFile(path, atomically = true)) { "could not stage $path" }
            listOf(
                StagedResource(
                    resourceKey = key,
                    role = ResourceRole.PRIMARY.wire,
                    contentType = "image/jpeg",
                    originalFilename = "IMG_0001.JPG",
                    stagedPath = path,
                ),
            )
        }
        val library = object : ImportedLibrary {
            override suspend fun captureDate(id: String): String? {
                val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(denormalizeAssetId(id)), null)
                    .firstObject() as? PHAsset ?: return null
                return asset.creationDate?.let { NSISO8601DateFormatter().stringFromDate(it) }
            }

            override fun marker(ref: AssetRef): MarkerState = markers[ref] ?: MarkerState.NONE
        }
        return Entered.Ready(StagedImport(importer, stage, library))
    }
}

/**
 * `UIApplication.isProtectedDataAvailable` in a running app — the one host with a `UIApplication` a CI job
 * reaches (a test executable has none). The app is running and the simulator implements no data protection, so
 * this host presents only `UNLOCKED`; no host presents the locked state at all (`ProtectedStorageContract`).
 */
class SimAppProtectedStorageBinding : Binding<ProtectedStorageState, ProtectedStorage> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(ProtectedStorageState.UNLOCKED)

    override fun create(state: ProtectedStorageState, clauseId: String): Entered<ProtectedStorage> =
        Entered.Ready(IosProtectedStorage())
}
