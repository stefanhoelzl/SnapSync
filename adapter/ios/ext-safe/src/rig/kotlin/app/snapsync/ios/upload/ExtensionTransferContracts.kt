@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.contract.extension.landedAt
import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.BackgroundTransferState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.Landed
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.config.bakedUploadBase
import app.snapsync.engine.iosLedgerStore
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.BackgroundTransfer
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSTemporaryDirectory
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions

/*
 * `BackgroundTransferContract`'s PhotoKit bindings (capability `port-contracts`): the real
 * `IosPhotoKitUploadPlatform` recorded INSIDE the upload extension on a device — the process production calls the
 * job API from — and replayed on every CI build on the simulator's test executable.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise.
 */

/**
 * The upload base the rig build bakes, which the recording's calls name. A device run under any other base is
 * refused: its recording would not replay against this constant.
 */
internal const val CONTRACT_UPLOAD_BASE: String = "http://127.0.0.1:18099/api/v2"

internal const val EXTENSION_UNREACHABLE_AT_CAP =
    "the PhotoKit tier's in-flight cap is not known, and filling it with jobs that never answer would outlast the " +
        "~60 s a process() call is given; AT_CAP_DEFERS is covered by the simulator app's URLSession binding"

/** A payload the PhotoKit tier cannot upload: not a `PHAssetResource`. */
private object NotAPhotoResource

/** A fresh, empty directory under the process's temporary directory, for one clause. */
private fun scratch(clauseId: String): String {
    val dir = NSTemporaryDirectory() + "contracts/${BackgroundTransferContract.name}/$clauseId"
    NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}

/**
 * The PhotoKit tier in [state] over [api], with a fresh SQLDelight ledger — never the app's own — and [objects] as
 * the fixture's read. [photo] is what a usable resource carries: a library photo's `PHAssetResource` on a device,
 * [ReplayPhoto] on replay.
 */
internal fun photoKitTransferInState(
    state: BackgroundTransferState,
    clauseId: String,
    api: UploadJobApi,
    objects: FixtureObjects,
    photo: () -> Any,
    afterDispose: () -> Unit = {},
): Entered<TransferUnderTest> {
    if (state == BackgroundTransferState.AT_CAP) return Entered.Unreachable(EXTENSION_UNREACHABLE_AT_CAP)
    val ledger = iosLedgerStore(scratch(clauseId))
    val transfer: BackgroundTransfer = IosPhotoKitUploadPlatform(Logger.withTag("contract"), ledger, api)
    return Entered.Ready(
        TransferUnderTest(
            transfer = transfer,
            base = CONTRACT_UPLOAD_BASE,
            usable = { key -> Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), photo()) },
            unusable = { key -> Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), NotAPhotoResource) },
            ledger = ledger,
            objects = objects,
        ),
        dispose = afterDispose,
    )
}

/** The newest photo in the library, as the resource an upload job sends — reused, so a run seeds nothing. */
private fun newestPhotoResource(): PHAssetResource {
    val options = PHFetchOptions().apply { sortDescriptors = listOf(NSSortDescriptor(key = "creationDate", ascending = false)) }
    val asset = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, options).firstObject() as? PHAsset
        ?: error("the library holds no photo to upload — add one and re-run")
    return PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
        ?: error("the newest photo has no resource")
}

/**
 * The real PhotoKit tier inside the upload extension on a device, recording every job-API call and every fixture read.
 * Replayed on every CI build by `IosPhotoKitUploadReplayContractTest`.
 */
internal class ExtensionPhotoKitTransferBinding(private val recorder: Recorder) :
    Binding<BackgroundTransferState, TransferUnderTest> {
    override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
    override val kind = BindingKind.Live
    override val reaches = setOf(BackgroundTransferState.IDLE, BackgroundTransferState.SINGLE_FREE_RETRY)

    override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
        recorder.open(clauseId)
        val log = Logger.withTag("contract")
        return photoKitTransferInState(
            state = state,
            clauseId = clauseId,
            api = RecordingUploadJobApi(SystemUploadJobApi(log), recorder),
            objects = recordingFixtureObjects(recorder) { route -> landedAt(route)?.let { Landed(it.ifEmpty { null }) } },
            photo = ::newestPhotoResource,
        )
    }
}

/**
 * Runs `BackgroundTransferContract` inside this extension process and renders the recording to commit verbatim at
 * `test/contracts/recordings/BackgroundTransfer@IOS_DEVICE_PHOTOKIT_EXT.rec`. Refuses without a full grant, or under a
 * build baked to any upload base but [CONTRACT_UPLOAD_BASE].
 */
internal fun recordTransferInExtension(): String {
    val grant = currentPhotoPermission()
    val base = bakedUploadBase()
    val refused = when {
        grant != PermissionStatus.GRANTED -> "the upload-job contract records under a full photo grant; this process holds $grant"
        base != CONTRACT_UPLOAD_BASE -> "this build uploads to $base; the contract's recording names $CONTRACT_UPLOAD_BASE (the rig build's local deployment)"
        else -> null
    }
    if (refused != null) return "$CONTRACT_REFUSED$refused\n"
    val recorder = Recorder()
    val results = run(BackgroundTransferContract, ExtensionPhotoKitTransferBinding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to BackgroundTransferContract.name,
        "host" to Host.IOS_DEVICE_PHOTOKIT_EXT.name,
        "file" to "${BackgroundTransferContract.name}@${Host.IOS_DEVICE_PHOTOKIT_EXT.name}.rec",
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return recorder.recording(header).render()
}

/** The upload-job contract, as the extension's registry of contracts it records. */
internal fun extensionTransferContract(): InAppContract =
    InAppContract(BackgroundTransferContract.name, Host.IOS_DEVICE_PHOTOKIT_EXT) { recordTransferInExtension() }
