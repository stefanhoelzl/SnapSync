@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.config.bakedUploadBase
import app.snapsync.contract.extension.landedAt
import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.BackgroundTransferState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Clause
import app.snapsync.contracts.Contract
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Recording
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.contracts.runEntry
import app.snapsync.databases.IosDatabases
import app.snapsync.services.ledger.LedgerService
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.LedgerState
import app.snapsync.gallery.photoKitResourceRole
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ResourceRole
import app.snapsync.model.normalizeAssetId
import app.snapsync.model.uploadKey
import app.snapsync.model.Resource
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.model.toLedgerRow
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.model.CreateResult
import app.snapsync.ports.LedgerStore
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
 * `BackgroundTransferContract`'s PhotoKit bindings (`docs/architecture.md`): the real `IosPhotoKitUploadPlatform`
 * recorded INSIDE the upload extension on a device — the process production calls the job API from — and replayed on
 * every CI build on the simulator's test executable.
 *
 * A job the extension creates is uploaded only after its `process()` call returns (measured, SE2, iOS 26.6: the
 * receiver saw every PUT only after the call ended). So a presented state is PREPARED across calls — create in one,
 * retry in the next, where the state needs it — and its clause runs in the call after the last preparation. The
 * preparation's calls are recorded into the clause's block, in call order, and a replay makes them in the same order
 * before the clause, in one go.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise.
 */

/**
 * The upload base the rig build bakes, which the recording's calls name. A device run under any other base is
 * refused: its recording would not replay against this constant.
 */
internal const val CONTRACT_UPLOAD_BASE: String = "http://127.0.0.1:18099/api/v2"

internal const val EXTENSION_ONLY_PRESENTED =
    "inside the upload extension a job is uploaded only after the process() call that created it returns, so a " +
        "clause that creates a transfer and awaits it cannot pass within one call; the extension records the " +
        "PRESENTED states, prepared across calls, and the simulator app's URLSession binding covers these"

/** The operating-system calls a presented state takes: its preparation calls, then the clause's own. */
internal fun callsFor(state: BackgroundTransferState): Int = when (state) {
    BackgroundTransferState.PRESENTED_SUCCEEDED, BackgroundTransferState.PRESENTED_REFUSED_ONCE -> 2
    BackgroundTransferState.PRESENTED_RETRY_SPENT -> 3
    else -> 1
}

/** A fresh, empty directory under the process's temporary directory. */
private fun scratch(name: String): String {
    val dir = NSTemporaryDirectory() + "contracts/${BackgroundTransferContract.name}/$name"
    NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return dir
}

/**
 * The key a prepared transfer of [photo] is created under: on a device the photo's own — as production keys it, so a
 * re-creation can fetch the photo the key names — and on replay, where no photo exists, the contract's derivation. The
 * key is masked in the one recorded call that names it ([UploadJobApi.liveResource]), so both replay alike.
 */
internal fun presentedKeyOf(clauseId: String, photo: Any): String =
    (photo as? PHAssetResource)?.let { resource ->
        uploadKey(
            normalizeAssetId(resource.assetLocalIdentifier),
            photoKitResourceRole(resource.type) ?: ResourceRole.PRIMARY,
            resource.originalFilename,
        )
    } ?: BackgroundTransferContract.key(clauseId)

/** The transfer a presented state prepares, as the contract derives it from the clause id. */
private class Prepared(clauseId: String, state: BackgroundTransferState, photo: Any) {
    val key = presentedKeyOf(clauseId, photo)
    val url = CONTRACT_UPLOAD_BASE + BackgroundTransferContract.preparedRoute(clauseId, state)
    val resource = Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), photo)
    val request = UploadRequest(url, mapOf("Content-Type" to "image/jpeg"), resource)
}

/** A fresh ledger holding the prepared transfer's row, `REQUESTED` with the destination it was created with. */
private suspend fun seededLedger(name: String, prepared: Prepared): LedgerStore =
    LedgerService(IosDatabases(scratch(name))).also {
        it.recordUnlessSettled(prepared.resource.toLedgerRow(LedgerState.REQUESTED, destinationPath = destinationPathOf(prepared.url)))
    }

/**
 * Preparation call [call] (1-based, below [callsFor]) of [state]'s transfer, over [api]: call 1 creates it; call 2 of a
 * retry-spent state re-points its free retry to the identical destination, as production does.
 */
internal fun prepareCall(state: BackgroundTransferState, clauseId: String, call: Int, api: UploadJobApi, photo: Any) = runEntry {
    val prepared = Prepared(clauseId, state, photo)
    val ledger = seededLedger("$clauseId-prep$call", prepared)
    val transfer: BackgroundTransfer = IosPhotoKitUploadPlatform(Logger.withTag("contract"), ledger, api)
    when (call) {
        1 -> check(transfer.createJob(prepared.request, prepared.resource) == CreateResult.CREATED) {
            "preparing $clauseId: the transfer was not created"
        }
        else -> {
            val offered = checkNotNull(transfer.fetchRetryJobs().firstOrNull { it.key == prepared.key }) {
                "preparing $clauseId: the refused transfer was not offered for its free retry"
            }
            transfer.retryJob(offered, prepared.request)
        }
    }
}

/** The subject for [state]'s clause: the tier over [api], with the prepared transfer's row seeded. */
internal fun photoKitTransferInState(
    state: BackgroundTransferState,
    clauseId: String,
    api: UploadJobApi,
    objects: FixtureObjects,
    photo: Any,
    afterDispose: () -> Unit = {},
): Entered<TransferUnderTest> {
    if (state !in BackgroundTransferContract.PRESENTED) return Entered.Unreachable(EXTENSION_ONLY_PRESENTED)
    val prepared = Prepared(clauseId, state, photo)
    lateinit var ledger: LedgerStore
    runEntry { ledger = seededLedger(clauseId, prepared) }
    return Entered.Ready(
        TransferUnderTest(
            transfer = IosPhotoKitUploadPlatform(Logger.withTag("contract"), ledger, api),
            base = CONTRACT_UPLOAD_BASE,
            usable = { key -> Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), photo) },
            unusable = { key -> Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), "not a photo") },
            ledger = ledger,
            objects = objects,
            presentedKey = { id -> presentedKeyOf(id, photo) },
        ),
        dispose = afterDispose,
    )
}

/** The newest photo in the library, as the resource an upload job sends — reused, so a run seeds nothing. */
internal fun newestPhotoResource(): PHAssetResource {
    val options = PHFetchOptions().apply { sortDescriptors = listOf(NSSortDescriptor(key = "creationDate", ascending = false)) }
    val asset = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, options).firstObject() as? PHAsset
        ?: error("the library holds no photo to upload — add one and re-run")
    return PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
        ?: error("the newest photo has no resource")
}

/**
 * The real PhotoKit tier inside the upload extension on a device, for the clause's LAST call: its preparation calls
 * were recorded by earlier calls, so the block is resumed, not opened. Replayed by `IosPhotoKitUploadReplayContractTest`.
 */
internal class ExtensionPhotoKitTransferBinding(private val recorder: Recorder) :
    Binding<BackgroundTransferState, TransferUnderTest> {
    override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
    override val kind = BindingKind.Live
    override val reaches = setOf(
        BackgroundTransferState.PRESENTED_SUCCEEDED,
        BackgroundTransferState.PRESENTED_REFUSED_ONCE,
        BackgroundTransferState.PRESENTED_RETRY_SPENT,
    )

    override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
        recorder.resume(clauseId)
        return photoKitTransferInState(
            state = state,
            clauseId = clauseId,
            api = RecordingUploadJobApi(SystemUploadJobApi(Logger.withTag("contract")), recorder),
            objects = recordingFixtureObjects(recorder) { route -> landedAt(route)?.let { Landed(it.ifEmpty { null }) } },
            photo = newestPhotoResource(),
        )
    }
}

/** What one call of a staged clause run answers: another call is needed, or the run is done with [body]. */
internal sealed interface Step {
    /** The partial recording to keep for the next call. */
    class Continue(val tape: String) : Step

    class Done(val body: String) : Step
}

/**
 * One operating-system call of the run of [clauseId], inside the extension: call [call] of the clause's
 * [callsFor]. [tape] is what earlier calls recorded. Answers the next step; refuses without a full grant, under an
 * upload base other than [CONTRACT_UPLOAD_BASE], or for a clause the extension does not record.
 */
internal fun transferRunStep(clauseId: String, call: Int, tape: String?): Step {
    val clause = BackgroundTransferContract.clauses.firstOrNull { it.id == clauseId && it.state in BackgroundTransferContract.PRESENTED }
    val refused = when {
        clause == null -> "no presented-state clause $clauseId in ${BackgroundTransferContract.name}"
        currentPhotoPermission() != GalleryAccess.GRANTED ->
            "the upload-job contract records under a full photo grant; this process holds ${currentPhotoPermission()}"
        bakedUploadBase() != CONTRACT_UPLOAD_BASE ->
            "this build uploads to ${bakedUploadBase()}; the recording names $CONTRACT_UPLOAD_BASE (the rig build's local deployment)"
        else -> null
    }
    if (refused != null || clause == null) return Step.Done("$CONTRACT_REFUSED$refused\n")
    val recorder = Recorder(tape?.let(Recording::parse))
    if (call < callsFor(clause.state)) {
        if (call == 1) recorder.open(clauseId) else recorder.resume(clauseId)
        val api = RecordingUploadJobApi(SystemUploadJobApi(Logger.withTag("contract")), recorder)
        prepareCall(clause.state, clauseId, call, api, newestPhotoResource())
        return Step.Continue(recorder.recording(emptyList()).render())
    }
    val results = run(single(clause), ExtensionPhotoKitTransferBinding(recorder))
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
    return Step.Done(recorder.recording(header).render())
}

/** The contract reduced to [clause] — a staged run records one clause, alone in the OS queue. */
private fun single(clause: Clause<BackgroundTransferState, TransferUnderTest>) =
    object : Contract<BackgroundTransferState, TransferUnderTest>(BackgroundTransferContract.name) {
        override val clauses = listOf(clause)
    }

/** The clauses the extension records, one run each: every presented-state clause. */
internal fun extensionTransferClauses(): List<String> =
    BackgroundTransferContract.clauses.filter { it.state in BackgroundTransferContract.PRESENTED }.map { it.id }
