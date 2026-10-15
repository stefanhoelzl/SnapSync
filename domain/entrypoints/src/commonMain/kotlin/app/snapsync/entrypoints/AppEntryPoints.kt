package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.ForgetMembership
import app.snapsync.application.trust.RefreshAttestation
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.feature.links.readmodel.OpenedLinks
import app.snapsync.feature.membership.ReceivedPhotoAdoption
import app.snapsync.feature.status.ForegroundCounts
import app.snapsync.feature.status.ForegroundWatches
import app.snapsync.feature.status.StatusRefresh
import app.snapsync.feature.upload.AppUploadEngine
import app.snapsync.feature.upload.OwnUploads
import app.snapsync.feature.upload.PushTailGuard
import app.snapsync.feature.upload.StoredUploadSettle
import app.snapsync.feature.upload.WakeRearm
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.model.CycleResult
import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.model.UnionTrigger
import app.snapsync.model.WakeId
import app.snapsync.ports.CompletedDownload
import app.snapsync.ports.FinishedDownload
import app.snapsync.ports.FinishedTransfer
import app.snapsync.ports.ImportPlaceholder
import app.snapsync.ports.ImportSettled
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.SessionDrain
import app.snapsync.ports.SessionRelaunch
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.GrantedEntryPoint
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Pass
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Sequence
import app.snapsync.scheduling.steps
import app.snapsync.services.crash.FootprintSampler
import app.snapsync.services.crash.ProcessAccount
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.downloads.ImportMarkers
import app.snapsync.services.foreground.AppForeground
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.links.LinkOpening
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.push.PushTokenRequest
import app.snapsync.services.push.PushTokenSource
import app.snapsync.services.settings.MobileDataSetting
import app.snapsync.services.transfer.SessionDrains
import app.snapsync.services.upload.SessionTransfer
import app.snapsync.services.wake.Heartbeat

// ── What happens on each trigger of the app process ─────────────────────────────────────────────────────────────────
//
// One entry point per operating-system trigger (`docs/patterns.md`, "Entry points"), named for it. Each `run` orders
// steps and decides nothing: what a step does is the call it makes. Read top to bottom, this file is what the app does
// when the operating system wakes it, hands it something, or shows it.

/** The app came to the front: its own work, then the wake's. */
class OnForeground(
    runtime: Runtime,
    private val foreground: AppForeground,
    private val pushTokens: PushTokenRequest,
    private val prelude: Prelude,
    private val host: HostAssembly,
    private val work: ForegroundWork,
    private val wake: WakeWork,
) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit) = steps {
        required { foreground.entered() }
        required { pushTokens.request() }
        // The membership before the screen: a launch before the phone's first unlock since it restarted read it
        // unreadable, and the first frame is drawn from what the device holds, never from that read.
        isolated("membership") { prelude.membership() }
        required { host.assemble() }
        isolated("own work") { work.run() }
        required { wake.full(WakeKind.FOREGROUND) }
    }
}

/** The app left the front, a transient interruption included: what only the screen needed stops. */
class OnBackground(
    runtime: Runtime,
    private val foreground: AppForeground,
    private val watches: ForegroundWatches,
    private val footprints: FootprintSampler,
) : InboundEntryPoint<Unit>(runtime) {
    private val log = Log("OnBackground")

    override fun run(arg: Unit) {
        foreground.left()
        watches.stop()
        log.unlabelled.i("app entering background")
        footprints.record("entering background")
    }
}

/** The network came back while the app was in front: what waited for it runs now. */
class OnNetworkReturned(runtime: Runtime, private val work: ForegroundWork, private val wake: WakeWork) :
    BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit) = steps {
        isolated("own work") { work.run() }
        required { wake.full(WakeKind.NETWORK) }
    }
}

/**
 * A silent push: the event changed. Its own work is the foreign photos it announces; the push's completion is answered
 * then, and the wake's work — for the active event only — goes on under the process's background time.
 */
class OnSilentPush(
    runtime: Runtime,
    private val prelude: Prelude,
    private val receiver: DownloadPushReceiver,
    private val guard: PushTailGuard,
    private val wake: WakeWork,
) : BackgroundEntryPoint<PushMessage>(runtime) {
    override suspend fun run(arg: PushMessage) = steps {
        required { prelude.run() }
        isolated("receive") { receiver.onSilentPush(arg.payload) }
        when (guard.joinsTail(arg.payload)) {
            true -> detached { wake.full(WakeKind.SILENT_PUSH) }
            false -> Unit
        }
    }
}

/** The operating system's scheduled wake — the heartbeat, or a change to the library: the wake is the work. */
class OnScheduledWake(runtime: Runtime, private val wake: WakeWork) : GrantedEntryPoint<WakeId>(runtime) {
    override suspend fun run(arg: WakeId) = steps {
        required { wake.afterPrelude(WakeKind.SCHEDULED) }
    }
}

/**
 * The operating system relaunched the app for the upload session's events: once they are drained — the session reported
 * so — the relaunch is answered, and the wake's work follows under the process's background time.
 */
class OnUploadSessionEvents(runtime: Runtime, private val drains: SessionDrains, private val wake: WakeWork) :
    BackgroundEntryPoint<SessionRelaunch>(runtime) {
    override suspend fun run(arg: SessionRelaunch) = steps {
        required { drains.awaitAfter(arg.drainsBefore) }
        detached { wake.afterPrelude(WakeKind.UPLOAD_SESSION) }
    }
}

/**
 * The operating system relaunched the app for the download session's events: once they are drained and what they staged
 * is recorded, the relaunch is answered, and the wake's work follows under the process's background time.
 */
class OnDownloadSessionEvents(
    runtime: Runtime,
    private val drains: SessionDrains,
    private val jobs: DownloadJobs,
    private val wake: WakeWork,
) : BackgroundEntryPoint<SessionRelaunch>(runtime) {
    override suspend fun run(arg: SessionRelaunch) = steps {
        required { drains.awaitAfter(arg.drainsBefore) }
        required { jobs.awaitOutstandingStagings() }
        detached { wake.afterPrelude(WakeKind.DOWNLOAD_SESSION) }
    }
}

/** A transfer session delivered every event it held: a relaunch waiting for them goes on. */
class OnSessionDrained(runtime: Runtime, private val drains: SessionDrains) : InboundEntryPoint<SessionDrain>(runtime) {
    override fun run(arg: SessionDrain) = drains.reported(arg.drains)
}

/** An upload ended: recorded at once — the platform tells it once — and its freed slot filled. */
class OnUploadFinished(
    private val runtime: Runtime,
    private val transfer: SessionTransfer,
    private val uploads: OwnUploads,
    private val topUp: TopUp,
) : InboundEntryPoint<FinishedTransfer>(runtime) {
    override fun run(arg: FinishedTransfer) {
        transfer.recordFinished(arg.tag, arg.failure)
        when (uploads.mayFillSlot()) {
            true -> runtime.detached("OnUploadFinished") { steps { run(topUp) } }
            false -> Unit
        }
    }
}

/** A download's bytes arrived: moved to staging before the platform deletes them. */
class OnDownloadFinished(runtime: Runtime, private val jobs: DownloadJobs) : InboundEntryPoint<FinishedDownload>(
    runtime,
) {
    override fun run(arg: FinishedDownload) = jobs.onFinished(arg.tag, arg.facts, arg.tempPath)
}

/** A download ended — finished, failed or cancelled: its slot is free. */
class OnDownloadCompleted(runtime: Runtime, private val jobs: DownloadJobs) : InboundEntryPoint<CompletedDownload>(
    runtime,
) {
    override fun run(arg: CompletedDownload) = jobs.onCompleted(arg.tag, arg.error)
}

/** A received photo was staged in a running process: it is imported, and nothing else is due for it. */
class OnDownloadStaged(private val runtime: Runtime, private val importReceived: ImportReceived) {
    fun requested() = runtime.detached("OnDownloadStaged") { steps { run(importReceived) } }
}

/** A lost event key was kept from a reopened invite: sharing and receiving resume without waiting for the next wake. */
class OnKeyRestored(private val runtime: Runtime, private val work: ForegroundWork, private val wake: WakeWork) {
    fun requested() = runtime.detached("OnKeyRestored") {
        steps {
            isolated("own work") { work.run() }
            required { wake.full(WakeKind.FOREGROUND) }
        }
    }
}

/** The library is creating an import's asset, inside its change block: the marker that keeps it from going back up. */
class OnImportPlaceholder(runtime: Runtime, private val markers: ImportMarkers) :
    InboundEntryPoint<ImportPlaceholder>(runtime) {
    override fun run(arg: ImportPlaceholder) = markers.placeholder(arg.correlation, arg.created)
}

/** An import came out: persisted here — the library reports it once, even when its requester is gone. */
class OnImportSettled(runtime: Runtime, private val markers: ImportMarkers) : InboundEntryPoint<ImportSettled>(
    runtime,
) {
    override fun run(arg: ImportSettled) = markers.settled(arg.correlation, arg.outcome)
}

/** The platform issued, re-delivered or rotated this device's push token. */
class OnPushToken(runtime: Runtime, private val tokens: PushTokenSource) : InboundEntryPoint<PushToken>(runtime) {
    override fun run(arg: PushToken) = tokens.deliver(arg.value)
}

/** The platform could not issue a push token: no silent push arrives until it can. */
class OnPushTokenFailure(runtime: Runtime) : InboundEntryPoint<PlatformError>(runtime) {
    private val log = Log("OnPushTokenFailure")

    override fun run(arg: PlatformError) =
        log.unlabelled.w("push registration failed — no silent pushes will arrive", "error" of arg.description)
}

/** The app was opened with a link: kept until the screen opens it. */
class OnLink(runtime: Runtime, private val opening: LinkOpening, private val links: OpenedLinks) :
    InboundEntryPoint<LinkDelivery>(runtime) {
    override fun run(arg: LinkDelivery) = links.offer(opening.urlIn(arg))
}

/**
 * The control channel voids this device's durable sync state (a rig build only), so a build pointed at a different
 * backend starts from nothing: the ledger, the download rows that are not a suppression handle, the membership —
 * locally, telling no backend, which belongs to the build left behind — its key, and the mobile-data choice.
 */
class OnDevReset(
    runtime: Runtime,
    private val ledger: LedgerService,
    private val downloads: DownloadController,
    private val forget: ForgetMembership,
    private val keys: EventKeys,
    private val mobileData: MobileDataSetting,
) : BackgroundEntryPoint<Unit>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: Unit) = steps {
        isolated("ledger") { ledger.clear() }
        isolated("downloads") { downloads.onDurableStateReset() }
        isolated("membership") { forget() }
        isolated("key") { keys.forget() }
        isolated("mobile data") { mobileData.clear() }
    }
}

/** The member changed a partial grant's selection: the selection is walked, then the wake's work follows. */
class OnSelectionChanged(
    runtime: Runtime,
    private val adoption: ReceivedPhotoAdoption,
    private val config: MembershipProjection,
    private val statusRefresh: StatusRefresh,
    private val uploads: OwnUploads,
    private val wake: WakeWork,
) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit) = steps {
        required { adoption.afterSelectionPublished(config.config.value) }
        isolated("total") { statusRefresh.refreshTotal() }
        isolated("selection walk") { uploads.walkSelection() }
        required { wake.full(WakeKind.SELECTION_CHANGE) }
    }
}

/** A process-metric report: accounted for before the platform's callback returns — it may be delivered once. */
class OnMetricReport(runtime: Runtime, private val account: ProcessAccount) :
    InboundEntryPoint<ProcessMetricPayload>(runtime) {
    override fun run(arg: ProcessMetricPayload) = account.handle(arg)
}

// ── What the entry points share ──────────────────────────────────────────────────────────────────────────────────────

/** Assembling the status host: a person reached the app. The host zone's; the foreground asks for it first. */
interface HostAssembly {
    fun assemble()
}

/** The uploads' arm, for a membership transition: the wake's work, started inside the process. */
class UploadEngine(
    private val runtime: Runtime,
    private val wake: WakeWork,
    private val heartbeat: Heartbeat,
    private val transfer: SessionTransfer,
) : AppUploadEngine {
    override suspend fun arm() = runtime.detached("OnArm") { wake.full(WakeKind.ARM) }

    override suspend fun disarm() = heartbeat.cancel()

    override suspend fun cancelTransfers() = transfer.cancelAll()
}

/**
 * What the re-read before a wake's own work covers: the membership, which another process may have changed, and the
 * token.
 */
class Prelude(private val membership: MembershipRepository, private val refreshAttestation: RefreshAttestation) {
    @OptIn(EntryPointCall::class)
    suspend fun run() = steps {
        isolated("membership") { membership() }
        isolated("attestation") { refreshAttestation() }
    }

    /** The membership alone, re-read: what a screen about to be drawn needs before the token's network round trip. */
    fun membership() = membership.reload()
}

/** The foreground's own work — and a returned network's, which is the same. */
class ForegroundWork(
    private val prelude: Prelude,
    private val config: MembershipProjection,
    private val watches: ForegroundWatches,
    private val transfer: SessionTransfer,
    private val settle: StoredUploadSettle,
    private val statusRefresh: StatusRefresh,
    private val downloads: DownloadController,
    private val stateRefresh: StateRefresh,
    private val leftovers: SweepLeftovers,
) {
    suspend fun run() = steps {
        required { prelude.run() }
        required { watches.start() }
        concurrently {
            child("staging") { transfer.releaseUnclaimedStaging() }
            child("settle") { settle.settleJoined(config.activeEventId()) }
            child("status") { statusRefresh.run() }
            child("reconcile") { downloads.reconcileJoined(config.activeEventId(), UnionTrigger.FOREGROUND) }
            child("reclaim") { downloads.releaseSettledBytes() }
            child("membership") { stateRefresh.run() }
            child("leftovers") { leftovers.run() }
        }
    }
}

/**
 * What a wake hands on after its own work: the imports, this device's uploads, then the end of the wake — and, however
 * those ended, the re-arm the next wake depends on.
 */
class WakeWork(
    private val prelude: Prelude,
    private val importReceived: ImportReceived,
    private val uploadOwn: UploadOwn,
    private val rearm: WakeRearm,
    private val end: EndOfWake,
    private val footprints: FootprintSampler,
) {
    suspend fun full(kind: WakeKind) = steps {
        var uploads: Pass<CycleResult> = Pass.Cut
        ensure("re-arm") { rearm.after(uploads) }
        ensure("footprint") { footprints.record("after the ${kind.name.lowercase()} wake") }
        run(importReceived)
        uploads = run(uploadOwn)
        isolated("end of wake") { end.run(kind) }
    }

    /** [full], after the re-read every wake's own work starts with — for a wake that has no other. */
    suspend fun afterPrelude(kind: WakeKind) = steps {
        required { prelude.run() }
        required { full(kind) }
    }
}

// ── The sequences a wake runs, single-flight across the process ──────────────────────────────────────────────────────

/**
 * Import what other members sent and is staged here. Yields to a join: an import can wait on a photo-library
 * transaction that never reports, and a new request must not wait behind it — the stalled import runs on, claimed, and
 * the next pass imports the rest.
 */
class ImportReceived(private val downloads: DownloadController, private val counts: ForegroundCounts) :
    Sequence<Unit>("import-received", yieldsToJoin = true) {
    override suspend fun pass() = steps {
        required { downloads.importReady() }
        isolated("counts") { counts.refresh() }
    }
}

/** Fill the upload slots from the ledger — what a finished upload's freed slot needs, and nothing more. */
class TopUp(private val uploads: OwnUploads, private val counts: ForegroundCounts) : Sequence<CycleResult>("top-up") {
    override suspend fun pass() = steps {
        val created = required { uploads.topUp() }
        isolated("counts") { counts.refresh() }
        created
    }
}

/**
 * This device's own photos: top up first, so the photos already known move before a walk the operating system may cut;
 * then walk and publish, under a full grant; then top up again for what the walk recorded.
 */
class UploadOwn(private val uploads: OwnUploads, private val counts: ForegroundCounts) :
    Sequence<CycleResult>("upload-own") {
    override suspend fun pass() = steps {
        val first = required { uploads.topUp() }
        isolated("counts") { counts.refresh() }
        val walked = required { uploads.walk() }
        val again = required { uploads.topUpAfter(walked) }
        isolated("counts") { counts.refresh() }
        uploads.outcome(first, walked?.result, again)
    }
}

/**
 * What a wake was for, as its end reads it (decision record `changes/timely-background-receiving`, D4–D5).
 * [checksPhotos]: the end runs the bounded photo check — every wake but a membership transition's, which read the union
 * in its own work. [boundsEventRead]: the end's read of the event's state is bounded to once an hour — every wake but
 * one whose own reason is to ask the event now (a push, an opening, a returned network, a join).
 */
enum class WakeKind(val checksPhotos: Boolean, val boundsEventRead: Boolean) {
    ARM(checksPhotos = false, boundsEventRead = false),
    FOREGROUND(checksPhotos = true, boundsEventRead = false),
    NETWORK(checksPhotos = true, boundsEventRead = false),
    SILENT_PUSH(checksPhotos = true, boundsEventRead = false),
    SELECTION_CHANGE(checksPhotos = true, boundsEventRead = true),
    SCHEDULED(checksPhotos = true, boundsEventRead = true),
    UPLOAD_SESSION(checksPhotos = true, boundsEventRead = true),
    DOWNLOAD_SESSION(checksPhotos = true, boundsEventRead = true),
}
