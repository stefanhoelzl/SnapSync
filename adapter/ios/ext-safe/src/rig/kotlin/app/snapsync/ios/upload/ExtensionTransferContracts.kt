@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.config.bakedUploadBase
import app.snapsync.contract.extension.landedAt
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
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.contracts.runEntry
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.GalleryAccess
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.destinationPathOf
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHFetchOptions

/*
 * `UploadContract`'s PhotoKit bindings (`docs/architecture.md`): the real `IosPhotoKitUploadPlatform` recorded INSIDE
 * the upload extension on a device — the process production calls the job API from — and replayed on every CI build on
 * the simulator's test executable.
 *
 * A job the extension creates is uploaded only after its `process()` call returns (measured, SE2, iOS 26.6: the
 * receiver saw every PUT only after the call ended). So a presented state is PREPARED across calls — create in one,
 * retry in the next, where the state needs it — and its clause runs in the call after the last preparation. The
 * preparation's calls are recorded into the clause's block, in call order, and a replay makes them in the same order
 * before the clause, in one go.
 *
 * The port is thin since phase 11f, so a binding needs no ledger: a job is found by the destination it was created
 * with, exactly as the upload services find its row.
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
internal fun callsFor(state: UploadState): Int = when (state) {
    UploadState.PRESENTED_SUCCEEDED, UploadState.PRESENTED_REFUSED_ONCE -> 2
    UploadState.PRESENTED_RETRY_SPENT -> 3
    else -> 1
}

/** The target a presented state's prepared transfer is created (and retried) with. */
private fun preparedTarget(clauseId: String, state: UploadState) =
    UploadTarget(CONTRACT_UPLOAD_BASE + UploadContract.preparedRoute(clauseId, state), mapOf("Content-Type" to UploadContract.CONTENT_TYPE))

/**
 * Preparation call [call] (1-based, below [callsFor]) of [state]'s transfer, over [api]: call 1 creates it; call 2 of a
 * retry-spent state re-points its free retry to the identical destination, as production does.
 */
internal fun prepareCall(state: UploadState, clauseId: String, call: Int, api: UploadJobApi, photo: Any) = runEntry {
    val upload = IosPhotoKitUploadPlatform(Logger.withTag("contract"), api)
    val target = preparedTarget(clauseId, state)
    when (call) {
        1 -> check(upload.create(UploadSource.Resource(photo), target, UploadContract.key(clauseId)) == UploadCreateOutcome.CREATED) {
            "preparing $clauseId: the transfer was not created"
        }
        else -> {
            val destination = destinationPathOf(target.url)
            val offered = checkNotNull(upload.jobs(UploadJobSet.RETRY_OFFERED).firstOrNull { it.destinationPath == destination }) {
                "preparing $clauseId: the refused transfer was not offered for its free retry"
            }
            upload.retry(offered, target)
        }
    }
}

/** The subject for [state]'s clause: the queue over [api]. */
internal fun photoKitUploadInState(
    state: UploadState,
    api: UploadJobApi,
    objects: FixtureObjects,
    photo: Any,
    afterDispose: () -> Unit = {},
): Entered<UploadUnderTest> {
    if (state !in UploadContract.PRESENTED) return Entered.Unreachable(EXTENSION_ONLY_PRESENTED)
    return Entered.Ready(
        UploadUnderTest(
            upload = IosPhotoKitUploadPlatform(Logger.withTag("contract"), api),
            base = CONTRACT_UPLOAD_BASE,
            usable = { UploadSource.Resource(photo) },
            // A file, which the PhotoKit queue does not take: it uploads a library resource.
            unusable = { key -> UploadSource.File("/not/a/photo/$key") },
            ended = { emptyList<UploadJob>() },
            objects = objects,
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
 * The real PhotoKit queue inside the upload extension on a device, for the clause's LAST call: its preparation calls
 * were recorded by earlier calls, so the block is resumed, not opened. Replayed by `IosPhotoKitUploadReplayContractTest`.
 */
internal class ExtensionPhotoKitUploadBinding(private val recorder: Recorder) : Binding<UploadState, UploadUnderTest> {
    override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
    override val kind = BindingKind.Live
    override val reaches = setOf(UploadState.PRESENTED_SUCCEEDED, UploadState.PRESENTED_REFUSED_ONCE, UploadState.PRESENTED_RETRY_SPENT)

    override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
        recorder.resume(clauseId)
        return photoKitUploadInState(
            state = state,
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
 * One operating-system call of the run of [clauseId], inside the extension: call [call] of the clause's [callsFor].
 * [tape] is what earlier calls recorded. Answers the next step; refuses without a full grant, under an upload base
 * other than [CONTRACT_UPLOAD_BASE], or for a clause the extension does not record.
 */
internal fun transferRunStep(clauseId: String, call: Int, tape: String?): Step {
    val clause = UploadContract.clauses.firstOrNull { it.id == clauseId && it.state in UploadContract.PRESENTED }
    val refused = when {
        clause == null -> "no presented-state clause $clauseId in ${UploadContract.name}"
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
    val results = run(single(clause), ExtensionPhotoKitUploadBinding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to UploadContract.name,
        "host" to Host.IOS_DEVICE_PHOTOKIT_EXT.name,
        "file" to "${UploadContract.name}@${Host.IOS_DEVICE_PHOTOKIT_EXT.name}.rec",
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return Step.Done(recorder.recording(header).render())
}

/** The contract reduced to [clause] — a staged run records one clause, alone in the OS queue. */
private fun single(clause: Clause<UploadState, UploadUnderTest>) =
    object : Contract<UploadState, UploadUnderTest>(UploadContract.name) {
        override val clauses = listOf(clause)
    }

/** The clauses the extension records, one run each: every presented-state clause. */
internal fun extensionTransferClauses(): List<String> =
    UploadContract.clauses.filter { it.state in UploadContract.PRESENTED }.map { it.id }
