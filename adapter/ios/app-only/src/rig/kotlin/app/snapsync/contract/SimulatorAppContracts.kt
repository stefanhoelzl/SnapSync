@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.gallery.IosGallery
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.ports.GalleryReader
import app.snapsync.background.IosBackgroundTime
import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.Binding
import app.snapsync.contracts.DownloadTransportContract
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.MarkerState
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.GalleryImportContract
import app.snapsync.contracts.GalleryImportState
import app.snapsync.contracts.ProtectedStorageContract
import app.snapsync.contracts.ProtectedStorageState
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.simulatorAppContract
import app.snapsync.model.ImportResult
import app.snapsync.ports.GalleryHandlers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ResourceRole
import app.snapsync.model.denormalizeAssetId
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.model.AssetRef
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.ProtectedStorage
import app.snapsync.protection.IosProtectedStorage
import app.snapsync.model.StagedResource
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
 * The simulator app's live bindings of the photo-library contracts (`docs/architecture.md`), and the
 * registry the `ios-contracts` job runs on every push — which also runs the hand-off contracts, whose bindings
 * live in `HandoffContracts.kt` and need no photo grant.
 *
 * This is where PhotoKit runs under a real full grant: the app bundle is the only simulator process
 * `applesimutils` can grant photo access to. Every binding declares the `GRANTED` states only, and every entry
 * refuses its whole run in any other process or grant (`docs/architecture.md`, "An authorization the process
 * cannot give itself is a precondition of the run"). The no-grant states run on the simulator's test executable
 * instead.
 *
 * **Seeding never deletes.** Each clause seeds into its own capture-date window ([PhotoLibrary.window]) through
 * PhotoKit's own creation API. Deleting would raise a system confirmation someone has to tap, and the CI job
 * starts every run from a fresh simulator.
 */
fun simulatorAppContracts(): List<InAppContract> = listOf(
    simulatorAppContract(GalleryReaderContract, SimAppGalleryReaderBinding(), ::refusal),
    simulatorAppContract(GalleryContract, SimAppGalleryBinding(), ::refusal),
    simulatorAppContract(PhotoAccessContract, SimAppPhotoAccessBinding(), ::refusal),
    simulatorAppContract(GalleryImportContract, SimAppImporterBinding(), ::refusal),
    simulatorAppContract(ProtectedStorageContract, SimAppProtectedStorageBinding(), ::hostRefusal),
    simulatorAppContract(LinkOpenerContract, SimAppLinkOpenerBinding(), ::hostRefusal),
    simulatorAppContract(SharePresenterContract, SimAppSharePresenterBinding(), ::hostRefusal),
    simulatorAppContract(BackgroundTransferContract, SimAppBackgroundTransferBinding(), ::refusal),
    simulatorAppContract(DownloadTransportContract, SimAppDownloadTransportBinding(), ::hostRefusal),
    simulatorAppContract(BackgroundTimeContract, SimAppBackgroundTimeBinding(), ::hostRefusal),
)

/** Why this process is not the simulator app, or `null` when it is — for bindings that need no photo grant. */
private fun hostRefusal(): String? = if (currentHost != Host.IOS_SIM_APP) {
    "this process is $currentHost, not ${Host.IOS_SIM_APP}; the simulator app's bindings run only there"
} else {
    null
}

/** Why this process cannot run the simulator app's bindings, or `null` when it can. */
private fun refusal(): String? = hostRefusal() ?: when {
    currentPhotoPermission() != GalleryAccess.GRANTED ->
        "the simulator app holds ${currentPhotoPermission()}, not GRANTED; grant photo access with applesimutils " +
            "before launch (simctl privacy writes a TCC row PhotoKit does not consult)"
    else -> null
}

private const val UNREACHABLE_NO_GRANT = "the simulator app runs under the full grant; the no-grant state runs on the test executable"

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

/** The app's real gallery, as a contract run builds it: its own scope, never the app's. */
private fun contractGallery(): IosGallery =
    IosGallery(IosGalleryReader(Logger.withTag("contract")), PhotoLibraryPermission(), CoroutineScope(Dispatchers.Default))

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

class SimAppGalleryReaderBinding : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(GalleryReaderState.GRANTED_SEEDED, GalleryReaderState.GRANTED_EMPTY_WINDOW)

    override fun create(state: GalleryReaderState, clauseId: String): Entered<SeededLibrary<GalleryReader>> {
        val seeded = when (state) {
            GalleryReaderState.NO_GRANT -> return Entered.Unreachable(UNREACHABLE_NO_GRANT)
            GalleryReaderState.GRANTED_SEEDED -> seedPhotos(PhotoLibrary.window(GalleryReaderContract.name, clauseId).seedDate)
            GalleryReaderState.GRANTED_EMPTY_WINDOW -> emptyList()
        }
        return Entered.Ready(SeededLibrary(IosGalleryReader(Logger.withTag("contract")), seeded))
    }
}

/**
 * The app's gallery under the full grant — the only grant the walk memo reads a token under. The change a clause
 * makes is one photo created through PhotoKit in its own capture window, which is a change made by this process:
 * the external-change case (a Camera photo) is the device check no host here can reach.
 */
class SimAppGalleryBinding : Binding<GalleryState, GalleryChange> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(GalleryState.GRANTED)

    override fun create(state: GalleryState, clauseId: String): Entered<GalleryChange> {
        val seedDate = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate
        return Entered.Ready(GalleryChange(contractGallery()) { seedPhotos(seedDate) })
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

class SimAppImporterBinding : Binding<GalleryImportState, StagedImport> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(
        GalleryImportState.GRANTED_VALID_STAGED,
        GalleryImportState.GRANTED_INVALID_STAGED,
    )

    override fun create(state: GalleryImportState, clauseId: String): Entered<StagedImport> {
        val bytes = when (state) {
            GalleryImportState.GRANTED_VALID_STAGED -> PhotoLibrary.jpeg
            GalleryImportState.GRANTED_INVALID_STAGED -> PhotoLibrary.notAnImage
        }
        val markers = mutableMapOf<AssetRef, MarkerState>()
        val importer = contractGallery().apply {
            listen(
                GalleryHandlers(
                    onChanged = {},
                    onImportPlaceholder = { ref, _ -> markers[ref] = MarkerState.RECORDED },
                    onImportSettled = { ref, outcome ->
                        when (outcome) {
                            is ImportResult.Imported -> markers[ref] = MarkerState.CONFIRMED
                            is ImportResult.Failed -> if (outcome.placeholder != null) markers[ref] = MarkerState.CLEARED
                        }
                    },
                ),
            )
        }
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

/**
 * The real [IosBackgroundTime] in the simulator app — the one CI host with a `UIApplication` (a test executable has
 * none). The rig drives the app in the foreground, so its time is not up and every hold is granted; "time is up" is
 * a state no host presents to a binding, which is why `BackgroundTimeContract` has no expiry clause.
 */
class SimAppBackgroundTimeBinding : Binding<BackgroundTimeState, BackgroundTime> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(BackgroundTimeState.TIME_REMAINS)

    override fun create(state: BackgroundTimeState, clauseId: String): Entered<BackgroundTime> =
        Entered.Ready(IosBackgroundTime(Logger.withTag("contract")))
}
