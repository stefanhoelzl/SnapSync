@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.config.IosBuildInfo
import app.snapsync.config.bakedUploadBase
import app.snapsync.contract.extension.landedAt
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Clause
import app.snapsync.contracts.Contract
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.OsUploadQueueContract
import app.snapsync.contracts.OsUploadQueueState
import app.snapsync.contracts.QueueUnderTest
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Recording
import app.snapsync.contracts.UPLOAD_ACCEPT
import app.snapsync.contracts.UPLOAD_CONTENT_TYPE
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.uploadTarget
import app.snapsync.gallery.PhotoKitResource
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadTarget
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceUploadJob
import platform.Photos.PHFetchOptions

/*
 * `OsUploadQueueContract`'s PhotoKit bindings (`docs/architecture.md`): the real `IosPhotoKitUploadPlatform` recorded INSIDE
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
 * refused: its recording would not replay against this constant. Its port is `:test:rig`'s `DEFAULT_RIG_PORT`, which
 * this module cannot depend on; the simulator's job-queue binding reads this constant rather than repeating it.
 */
internal const val CONTRACT_UPLOAD_BASE: String = "http://127.0.0.1:18099/api/v2"

/**
 * The states the extension records: a creation, at and below the limit, in one call each, and the PRESENTED states,
 * prepared across calls. [OsUploadQueueState.ANY] asks the system nothing and runs in the simulator's test executable.
 */
internal val EXTENSION_RECORDED: Set<OsUploadQueueState> =
    OsUploadQueueContract.PRESENTED + OsUploadQueueState.IDLE + OsUploadQueueState.AT_CAP

internal const val NOT_RECORDED_IN_THE_EXTENSION =
    "a queue that asks the system nothing runs in the simulator's test executable, not inside the extension"

/**
 * How many jobs entering [OsUploadQueueState.AT_CAP] makes before giving up on PhotoKit refusing one: its limit is
 * declared nowhere, and found here by reaching it.
 */
private const val CAP_SEARCH_BOUND = 500

/** The operating-system calls a presented state takes: its preparation calls, then the clause's own. */
internal fun callsFor(state: OsUploadQueueState): Int = when (state) {
    OsUploadQueueState.PRESENTED_SUCCEEDED, OsUploadQueueState.PRESENTED_REFUSED_ONCE -> 2
    OsUploadQueueState.PRESENTED_RETRY_SPENT -> 3
    else -> 1
}

/** The target a presented state's prepared transfer is created (and retried) with. */
private fun preparedTarget(clauseId: String, state: OsUploadQueueState) =
    UploadTarget(
        CONTRACT_UPLOAD_BASE + OsUploadQueueContract.preparedRoute(clauseId, state),
        mapOf("Content-Type" to UPLOAD_CONTENT_TYPE),
        TransferNetwork.ANY,
    )

/**
 * Preparation call [call] (1-based, below [callsFor]) of [state]'s transfer, over [api]: call 1 creates it; call 2 of a
 * retry-spent state re-points its free retry to the identical destination, as production does.
 */
internal fun prepareCall(
    state: OsUploadQueueState,
    clauseId: String,
    call: Int,
    api: UploadJobApi,
    photo: GalleryResourceHandle,
) = runEntry {
    val queue = IosPhotoKitUploadPlatform(Logger.withTag("contract"), api)
    val target = preparedTarget(clauseId, state)
    when (call) {
        1 -> check(queue.create(photo, target) == CreateOutcome.CREATED) {
            "preparing $clauseId: the transfer was not created"
        }
        else -> {
            val destination = destinationPathOf(target.url)
            val offered =
                checkNotNull(
                    queue.retryOffered().firstOrNull { it.destinationUrl?.let(::destinationPathOf) == destination },
                ) {
                    "preparing $clauseId: the refused transfer was not offered for its free retry"
                }
            queue.retry(offered, target)
        }
    }
}

/** The subject for [state]'s clause: the queue over [api], handed to the clause through its recording proxy over [log]. */
internal fun photoKitUploadInState(
    state: OsUploadQueueState,
    api: UploadJobApi,
    objects: FixtureObjects,
    photo: GalleryResourceHandle,
    log: CallLog,
    jobLimit: () -> Int = { error("only the at-cap state reads the cap") },
    afterDispose: () -> Unit = {},
): Entered<QueueUnderTest> {
    if (state !in EXTENSION_RECORDED) return Entered.Unreachable(NOT_RECORDED_IN_THE_EXTENSION)
    if (state == OsUploadQueueState.AT_CAP) {
        val made = fillToTheLimit(IosPhotoKitUploadPlatform(Logger.withTag("contract"), api), photo)
            ?: return Entered.Unreachable("PhotoKit made $CAP_SEARCH_BOUND jobs without refusing one: no limit reached")
        Logger.withTag("contract").i { "PhotoKit's in-flight limit reached after $made job(s)" }
    }
    return Entered.Ready(
        QueueUnderTest(
            queue = IosPhotoKitUploadPlatform(Logger.withTag("contract"), api).recorded(log),
            base = CONTRACT_UPLOAD_BASE,
            usable = { photo },
            // Not a PhotoKit resource, which is all the PhotoKit queue takes.
            unusable = { object : GalleryResourceHandle {} },
            objects = objects,
        ),
        dispose = afterDispose,
    )
}

/**
 * Make jobs of [photo] to accepting routes until PhotoKit refuses one for its limit; answers how many it made, or
 * `null` when it never refused within [CAP_SEARCH_BOUND]. The jobs upload once the call returns, and the next upload
 * cycle acknowledges them as routes no row maps to.
 */
private fun fillToTheLimit(queue: IosPhotoKitUploadPlatform, photo: GalleryResourceHandle): Int? {
    var made = 0
    var refused = false
    runEntry {
        while (!refused && made < CAP_SEARCH_BOUND) {
            val route = OsUploadQueueContract.path("AT_CAP_DEFERS", UPLOAD_ACCEPT, n = made + 1)
            when (queue.create(photo, uploadTarget(CONTRACT_UPLOAD_BASE, route))) {
                CreateOutcome.CREATED -> made++
                CreateOutcome.LIMIT_EXCEEDED -> refused = true
                CreateOutcome.FAILED -> error("filling PhotoKit's limit: job ${made + 1} was refused outright")
            }
        }
    }
    return made.takeIf { refused }
}

/** The newest photo in the library, as the resource an upload job sends — reused, so a run seeds nothing. */
internal fun newestPhotoResource(): PHAssetResource {
    val options = PHFetchOptions().apply {
        sortDescriptors = listOf(NSSortDescriptor(key = "creationDate", ascending = false))
    }
    val asset = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, options).firstObject() as? PHAsset
        ?: error("the library holds no photo to upload — add one and re-run")
    return PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
        ?: error("the newest photo has no resource")
}

/**
 * The real PhotoKit queue inside the upload extension on a device, for the clause's LAST call: its preparation calls
 * were recorded by earlier calls, so the block is resumed, not opened. Replayed by `IosPhotoKitUploadReplayContractTest`.
 */
internal class ExtensionPhotoKitUploadBinding(
    private val recorder: Recorder,
) : Binding<OsUploadQueueState, QueueUnderTest> {
    override val host = Host.IOS_DEVICE_PHOTOKIT_EXT
    override val kind = BindingKind.Live
    override val adapter = IosPhotoKitUploadPlatform::class
    override val reaches = setOf(
        OsUploadQueueState.IDLE,
        OsUploadQueueState.AT_CAP,
        OsUploadQueueState.PRESENTED_SUCCEEDED,
        OsUploadQueueState.PRESENTED_REFUSED_ONCE,
        OsUploadQueueState.PRESENTED_RETRY_SPENT,
    )

    override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
        // A state this host does not present: another platform's fact, or one no binding here enters.
        if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
        if (callsFor(state) == 1) recorder.open(clauseId) else recorder.resume(clauseId)
        return photoKitUploadInState(
            state = state,
            api = RecordingUploadJobApi(SystemUploadJobApi(Logger.withTag("contract")), recorder),
            objects = recordingFixtureObjects(
                recorder,
            ) { route -> landedAt(route)?.let { Landed(it.ifEmpty { null }) } },
            photo = PhotoKitResource(newestPhotoResource()),
            log = log,
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
    val clause = OsUploadQueueContract.clauses.firstOrNull {
        it.id == clauseId && it.state in EXTENSION_RECORDED
    }
    val refused = when {
        clause == null -> "no presented-state clause $clauseId in ${OsUploadQueueContract.name}"
        currentPhotoPermission() != LibraryGrant.GRANTED ->
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
        prepareCall(clause.state, clauseId, call, api, PhotoKitResource(newestPhotoResource()))
        return Step.Continue(recorder.recording(emptyList()).render())
    }
    val results = run(single(clause), ExtensionPhotoKitUploadBinding(recorder))
    val env = IosBuildInfo()
    val header = listOf(
        "contract" to OsUploadQueueContract.name,
        "host" to Host.IOS_DEVICE_PHOTOKIT_EXT.name,
        "file" to "${OsUploadQueueContract.name}@${Host.IOS_DEVICE_PHOTOKIT_EXT.name}.rec",
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return Step.Done(recorder.recording(header).render())
}

/** The contract reduced to [clause] — a staged run records one clause, alone in the OS queue. */
private fun single(clause: Clause<OsUploadQueueState, QueueUnderTest>) =
    object : Contract<OsUploadQueueState, QueueUnderTest>(OsUploadQueueContract.name) {
        override val clauses = listOf(clause)
    }

/** The clauses the extension records, one run each: every clause of a state it records. */
internal fun extensionTransferClauses(): List<String> =
    OsUploadQueueContract.clauses.filter { it.state in EXTENSION_RECORDED }.map { it.id }
