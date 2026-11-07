package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.crash.DescribeReporter
import app.snapsync.application.encryption.RestoreEventKey
import app.snapsync.application.mobiledata.SetMobileData
import app.snapsync.application.process.AccountForProcess
import app.snapsync.application.process.RecordFootprint
import app.snapsync.application.push.RegisterPush
import app.snapsync.application.receiving.FreeTransfer
import app.snapsync.application.receiving.RecordOutcome
import app.snapsync.application.receiving.RecordStaged
import app.snapsync.application.receiving.StageDownload
import app.snapsync.application.receiving.StageOutcome
import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.application.trust.RefreshAttestation
import app.snapsync.application.upload.RecordFinishedUpload
import app.snapsync.application.upload.SettleStoredUploads
import app.snapsync.application.wake.RearmHeartbeat
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.links.LinkReading
import app.snapsync.data.links.OpenedInvites
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.network.NetworkStatus
import app.snapsync.data.process.MetricPayloadReading
import app.snapsync.data.push.PushTokens
import app.snapsync.data.receiving.ImportMarkerWrites
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.service.ServiceConditions
import app.snapsync.data.store.ChangedElsewhere
import app.snapsync.data.upload.SessionEnd
import app.snapsync.data.upload.StagingSweep
import app.snapsync.data.upload.UploaderAdmission
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.membership.RestoreAnswer
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.push.PushTailVerdict
import app.snapsync.domain.push.PushTrigger
import app.snapsync.domain.receiving.TransferFacts
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MembershipReadRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.upload.UploadAdmission
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.wake.UploadsLeft
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.model.PlatformError
import app.snapsync.model.pushEventId
import app.snapsync.model.pushSeq
import app.snapsync.ports.CompletedDownload
import app.snapsync.ports.FinishedDownload
import app.snapsync.ports.FinishedTransfer
import app.snapsync.ports.ImportPlaceholder
import app.snapsync.ports.ImportSettled
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.PushMessage
import app.snapsync.ports.PushToken
import app.snapsync.ports.SessionDrain
import app.snapsync.ports.SessionRelaunch
import app.snapsync.ports.WakeId
import app.snapsync.query.membership.MembershipQuery
import app.snapsync.query.membership.MembershipStatuses
import app.snapsync.query.mobiledata.MobileDataStatuses
import app.snapsync.query.push.PushTailLookup
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.Delivery
import app.snapsync.scheduling.GatedWatch
import app.snapsync.scheduling.GrantedEntryPoint
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Pass
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.Steps
import app.snapsync.scheduling.orIfCut
import app.snapsync.services.transfer.SessionDrains

// ── What happens on each trigger of the app process ─────────────────────────────────────────────────────────────────
//
// One entry point per operating-system trigger (`docs/patterns.md`, "Entry points"), named for it. Each `run` orders
// steps and decides nothing: what a step does is the call it makes. Read top to bottom, this file is what the app does
// when the operating system wakes it, hands it something, or shows it.

/** The app came to the front: its own work, then the wake's. */
class OnForeground(
    runtime: Runtime,
    private val service: ServiceConditions,
    private val foreground: AppForeground,
    private val pushTokens: PushTokens,
    private val prelude: Prelude,
    private val host: HostAssembly,
    private val network: ForegroundNetwork,
    private val work: ForegroundWork,
    private val wake: WakeWork,
) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        // What a person sees must not start from an outcome of before the return (decision record
        // `changes/tell-service-faults`, D4): the service's condition is forgotten before the return is told.
        required { service.opening() }
        required { foreground.entered() }
        required { pushTokens.request() }
        // The membership before the screen: a launch before the phone's first unlock since it restarted read it
        // unreadable, and the first frame is drawn from what the device holds, never from that read.
        isolated("membership") { prelude.membership() }
        isolated("key") { prelude.key() }
        required { host.assemble() }
        // What only a visible screen needs, until the app leaves the front (`OnBackground`).
        required { network.start() }
        isolated("own work") { work.run(on) }
        required { wake.full(WakeKind.FOREGROUND, on) }
    }
}

/** The app left the front, a transient interruption included: what only the screen needed stops. */
class OnBackground(
    runtime: Runtime,
    private val foreground: AppForeground,
    private val network: ForegroundNetwork,
    private val footprints: RecordFootprint,
) : InboundEntryPoint<Unit>(runtime) {
    private val log = Log("OnBackground")

    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: Unit) {
        foreground.left()
        network.stop()
        log.unlabelled.i("app entering background")
        footprints("entering background")
    }
}

/** The network came back while the app was in front: what waited for it runs now. */
class OnNetworkReturned(runtime: Runtime, private val work: ForegroundWork, private val wake: WakeWork) :
    BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        isolated("own work") { work.run(on) }
        required { wake.full(WakeKind.NETWORK, on) }
    }
}

/**
 * A silent push: the event changed. Its own work is the foreign photos it announces; the push's completion is answered
 * then, and the wake's work — for the active event only — goes on under the process's background time.
 */
class OnSilentPush(
    runtime: Runtime,
    private val prelude: Prelude,
    private val tail: PushTailLookup,
    private val receive: PlanJoined,
    private val wake: WakeWork,
) : BackgroundEntryPoint<PushMessage>(runtime) {
    override suspend fun run(arg: PushMessage, on: Run) = on.steps {
        required { prelude.run(on) }
        when (required { tail.of(pushEventId(arg.payload)) }) {
            // A push for the joined event: what it announced is read from the position it names (decision record
            // `changes/incremental-union`, D6) — a burst of pushes after one read costs no further reads.
            PushTailVerdict.Joins -> {
                required { receive.run(on, UnionTrigger.PUSH, pushSeq(arg.payload)) }
                detached { wake.full(WakeKind.SILENT_PUSH, on) }
            }
            // Notably a left event, whose backend membership persists and so keeps pushing: never received again.
            PushTailVerdict.OtherEvent, PushTailVerdict.NamesNoEvent, PushTailVerdict.MembershipUnreadable -> Unit
        }
    }
}

/** The operating system's scheduled wake — the heartbeat, or a change to the library: the wake is the work. */
class OnScheduledWake(runtime: Runtime, private val wake: WakeWork) : GrantedEntryPoint<WakeId>(runtime) {
    override suspend fun run(arg: WakeId, on: Run) = on.steps {
        required { wake.afterPrelude(WakeKind.SCHEDULED, on) }
    }
}

/**
 * The operating system relaunched the app for the upload session's events: once they are drained — the session reported
 * so — the relaunch is answered, and the wake's work follows under the process's background time.
 */
class OnUploadSessionEvents(runtime: Runtime, private val drains: SessionDrains, private val wake: WakeWork) :
    BackgroundEntryPoint<SessionRelaunch>(runtime) {
    override suspend fun run(arg: SessionRelaunch, on: Run) = on.steps {
        required { drains.awaitAfter(arg.drainsBefore) }
        detached { wake.afterPrelude(WakeKind.UPLOAD_SESSION, on) }
    }
}

/**
 * The operating system relaunched the app for the download session's events: once they are drained and what they staged
 * is recorded, the relaunch is answered, and the wake's work follows under the process's background time.
 */
class OnDownloadSessionEvents(
    runtime: Runtime,
    private val drains: SessionDrains,
    private val wake: WakeWork,
) : BackgroundEntryPoint<SessionRelaunch>(runtime) {
    override suspend fun run(arg: SessionRelaunch, on: Run) = on.steps {
        required { drains.awaitAfter(arg.drainsBefore) }
        // The session reports its own events drained, which says nothing about the store writes they caused.
        required { awaitDetachedOf(OnDownloadFinished::class) }
        detached { wake.afterPrelude(WakeKind.DOWNLOAD_SESSION, on) }
    }
}

/** A transfer session delivered every event it held: a relaunch waiting for them goes on. */
class OnSessionDrained(runtime: Runtime, private val drains: SessionDrains) : InboundEntryPoint<SessionDrain>(runtime) {
    override fun Delivery.run(arg: SessionDrain) = drains.reported(arg.drains)
}

/** An upload ended: recorded at once — the platform tells it once — and its freed slot filled. */
class OnUploadFinished(
    runtime: Runtime,
    private val finished: RecordFinishedUpload,
    private val admission: UploaderAdmission,
    private val topUp: TopUp,
) : InboundEntryPoint<FinishedTransfer>(runtime) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: FinishedTransfer) {
        finished(SessionEnd(arg.tag, arg.failure))
        when (admission.admission()) {
            UploadAdmission.ADMIT -> detachedSteps { run(topUp) }
            UploadAdmission.WITHHELD -> Unit
        }
    }
}

/**
 * A download's bytes arrived: moved to staging before the platform deletes them — on the delivering thread — then
 * recorded, and the photo imported, after it returns. The session's drain report waits for the record.
 */
class OnDownloadFinished(
    runtime: Runtime,
    private val stage: StageDownload,
    private val record: RecordStaged,
    private val staged: OnDownloadStaged,
) : InboundEntryPoint<FinishedDownload>(runtime) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: FinishedDownload) =
        when (
            val staged =
                stage(
                    arg.tag,
                    TransferFacts(arg.facts.statusCode, arg.facts.expectedBytes, arg.facts.receivedBytes),
                    arg.tempPath,
                )
        ) {
            is StageOutcome.Landed -> detachedSteps { recorded(staged) }.let { }
            is StageOutcome.Dropped -> Unit
        }

    @OptIn(EntryPointCall::class)
    private suspend fun Steps.recorded(landed: StageOutcome.Landed) = when (required { record(landed) }) {
        // The import is requested, not run here: the session's drain report waits for the record only.
        RecordOutcome.Recorded -> required { staged.requested() }
        is RecordOutcome.NotRecorded -> Unit
    }
}

/**
 * A received photo was staged in a running process: it is imported, and nothing else is due for it. A recognition this
 * import still needs is a background read of the operating system's download, with no push behind it.
 */
class OnDownloadStaged(private val runtime: Runtime, private val importReceived: ImportReceived) {
    suspend fun requested() = runtime.detached("OnDownloadStaged") { on ->
        on.steps { run(importReceived, UnionTrigger.WAKE) }
    }
}

/** A download ended — finished, failed or cancelled: its slot is free, and the next queued transfer starts. */
class OnDownloadCompleted(runtime: Runtime, private val free: FreeTransfer) : InboundEntryPoint<CompletedDownload>(
    runtime,
) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(
        arg: CompletedDownload,
    ) = detachedSteps { isolated("free") { free(arg.tag, arg.error) } }.let { }
}

/**
 * A reopened invite of the joined event offered its key: kept if the device lost it and it is the event's own —
 * answered as soon as it is — then sharing and receiving resume without waiting for the next wake.
 */
class OnKeyRestoreRequested(
    runtime: Runtime,
    private val restore: RestoreEventKey,
    private val work: ForegroundWork,
    private val wake: WakeWork,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<KeyRestoreRequest>(runtime) {
    private val log = Log("OnKeyRestoreRequested")

    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: KeyRestoreRequest, on: Run) = on.steps {
        statuses.restore.begun(arg.id)
        ensure("answer") { statuses.restore.settle(arg.id, RestoreAnswer.Failed) }
        val restored = required { restore(arg.key) }
        required { log.i("key restore answered", "answer" of restored.answer) }
        required { statuses.restore.done(arg.id, restored.answer) }
        when (restored.resumes) {
            true -> {
                isolated("own work") { work.run(on) }
                required { wake.full(WakeKind.FOREGROUND, on) }
            }
            false -> Unit
        }
    }
}

/**
 * The member tried again to read whether this device is in an event: the membership and the key read again — the screen
 * shows what was found as soon as it is — then what opening the app runs, and only then answered, so a second try
 * never runs beside it.
 */
class OnMembershipReadRetried(
    runtime: Runtime,
    private val prelude: Prelude,
    private val work: ForegroundWork,
    private val wake: WakeWork,
    private val statuses: MembershipStatuses,
) : BackgroundEntryPoint<MembershipReadRequest>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: MembershipReadRequest, on: Run) = on.steps {
        statuses.read.begun(arg.id)
        ensure("answer") { statuses.read.settle(arg.id, Unit) }
        isolated("membership") { prelude.membership() }
        isolated("key") { prelude.key() }
        isolated("own work") { work.run(on) }
        required { wake.full(WakeKind.FOREGROUND, on) }
        required { statuses.read.done(arg.id, Unit) }
    }
}

/** The library is creating an import's asset, inside its change block: the marker that keeps it from going back up. */
class OnImportPlaceholder(runtime: Runtime, private val markers: ImportMarkerWrites) :
    InboundEntryPoint<ImportPlaceholder>(runtime) {
    override fun Delivery.run(arg: ImportPlaceholder) = markers.placeholder(arg.correlation, arg.created).let { }
}

/** An import came out: persisted here — the library reports it once, even when its requester is gone. */
class OnImportSettled(runtime: Runtime, private val markers: ImportMarkerWrites) : InboundEntryPoint<ImportSettled>(
    runtime,
) {
    override fun Delivery.run(arg: ImportSettled) = markers.settled(arg.correlation, arg.outcome).let { }
}

/**
 * The platform issued, re-delivered or rotated this device's push token: kept, then registered with the backend if it
 * differs from the last registration the backend accepted — under the delivery's own background time, so a token
 * delivered in a background wake is published from it.
 */
class OnPushToken(runtime: Runtime, private val tokens: PushTokens, private val register: RegisterPush) :
    InboundEntryPoint<PushToken>(runtime) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: PushToken) {
        tokens.store(arg)
        detachedSteps { isolated("register") { register(PushTrigger.DELIVERED) } }
    }
}

/**
 * The device obtained a new credential — a mint, a re-attestation, a periodic renewal alike: the registration is
 * published again whatever the record holds. A `PUT` refused because the device had no valid attestation yet (a fresh
 * install races attestation, or the backend collected its record) is acceptable now, and without this it would wait for
 * the next app entry — which a device that receives no silent pushes rarely gets.
 */
class OnCredentialChanged(runtime: Runtime, private val register: RegisterPush) : BackgroundEntryPoint<Unit>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        isolated("register") { register(PushTrigger.CREDENTIAL) }
    }
}

/** The platform could not issue a push token: no silent push arrives until it can. */
class OnPushTokenFailure(runtime: Runtime) : InboundEntryPoint<PlatformError>(runtime) {
    private val log = Log("OnPushTokenFailure")

    override fun Delivery.run(arg: PlatformError) =
        log.unlabelled.w("push registration failed — no silent pushes will arrive", "error" of arg.description)
}

/** The app was opened with a link: read, and kept until a screen opens it. */
class OnLink(runtime: Runtime, private val reading: LinkReading, private val invites: OpenedInvites) :
    InboundEntryPoint<LinkDelivery>(runtime) {
    // A delivery that opens nothing changes nothing: an invite already waiting still waits.
    override fun Delivery.run(arg: LinkDelivery) {
        reading.inviteIn(arg)?.let(invites::offer)
    }
}

/** A screen opened the invite the app was opened with: it no longer waits — a newer one offered meanwhile stays. */
class OnInviteOpened(runtime: Runtime, private val invites: OpenedInvites) :
    BackgroundEntryPoint<InviteLinkVerdict>(runtime) {
    override suspend fun run(arg: InviteLinkVerdict, on: Run) = on.steps {
        required { invites.opened(arg) }
    }
}

/** The upload extension committed to one of the app's databases: what follows that store re-reads it. */
class OnChangedElsewhere(runtime: Runtime, private val stores: ChangedElsewhere) : InboundEntryPoint<String>(runtime) {
    override fun about(arg: String): String = arg

    override fun Delivery.run(arg: String) = stores.changed(arg)
}

/**
 * The member changed a partial grant's selection: the photos it brought that no pass recognised are recognised, the
 * selection is walked, then the wake's work follows.
 */
class OnSelectionChanged(
    runtime: Runtime,
    private val recognition: Recognition,
    private val count: CountOwnPhotos,
    private val walkAndPublish: WalkAndPublish,
    private val wake: WakeWork,
) : BackgroundEntryPoint<Unit>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        run(recognition, UnionTrigger.GRANT) // a partial grant's selection is the photo access it gives
        isolated("total") { count() }
        run(walkAndPublish)
        required { wake.full(WakeKind.SELECTION_CHANGE, on) }
    }
}

/**
 * The member switched whether photos may use mobile data: saved, and answered with the choice now in effect — the one
 * asked for, or, when it could not be saved, the one still governing the transfers. Turned on and saved, it runs a full
 * wake: what waited for Wi-Fi and no transfer carries yet starts over mobile data now, not at the next opening.
 */
class OnMobileDataChanged(
    runtime: Runtime,
    private val setMobileData: SetMobileData,
    private val store: MobileDataStore,
    private val statuses: MobileDataStatuses,
    private val wake: WakeWork,
) : BackgroundEntryPoint<MobileDataRequest>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: MobileDataRequest, on: Run) = on.steps {
        statuses.change.begun(arg.id)
        ensure("answer") { statuses.change.settle(arg.id, MobileDataOutcome.NotSaved(store.read())) }
        val outcome = required { setMobileData(arg.choice) }
        required { statuses.change.done(arg.id, outcome) }
        when (outcome) {
            is MobileDataOutcome.Saved -> when (outcome.inEffect) {
                MobileDataChoice.ALLOWED -> required { wake.full(WakeKind.MOBILE_DATA, on) }
                MobileDataChoice.WIFI_ONLY -> Unit
            }
            is MobileDataOutcome.NotSaved -> Unit
        }
    }
}

/** A process-metric report: accounted for before the platform's callback returns — it may be delivered once. */
class OnMetricReport(
    runtime: Runtime,
    private val reading: MetricPayloadReading,
    private val account: AccountForProcess,
) : InboundEntryPoint<ProcessMetricPayload>(runtime) {
    @OptIn(EntryPointCall::class)
    override fun Delivery.run(arg: ProcessMetricPayload) = account(reading.read(arg))
}

// ── What the entry points share ──────────────────────────────────────────────────────────────────────────────────────

/** Assembling the status host: a person reached the app. The host zone's; the foreground asks for it first. */
interface HostAssembly {
    fun assemble()
}

/**
 * What the re-read before a wake's own work covers: the membership and the event's key, which another process may have
 * changed — or a phone unlocked since — and the token.
 */
class Prelude(
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val refreshAttestation: RefreshAttestation,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        isolated("membership") { membership() }
        isolated("key") { key() }
        isolated("attestation") { refreshAttestation() }
    }

    /** The membership alone, re-read: what a screen about to be drawn needs before the token's network round trip. */
    fun membership() = membership.reload()

    /** The event's key, re-read: whether it is held is part of the screen too. */
    fun key() = keys.reload()
}

/**
 * The network watch the screen reads while the app is in front ([NetworkStatus]): started by the foreground's work, so
 * the platform's monitor runs only then, and stopped — the notice forgotten — when the app leaves the front.
 */
class ForegroundNetwork(
    private val gate: GatedWatch,
    private val readings: NetworkReadings,
    private val status: NetworkStatus,
) {
    fun start() = gate.start(readings.watch(), status::waitBefore, status::publish)

    fun stop() {
        gate.stop()
        status.hide()
    }
}

/** The foreground's own work — and a returned network's, which is the same. */
class ForegroundWork(
    private val prelude: Prelude,
    private val membership: MembershipQuery,
    private val staging: StagingSweep,
    private val settle: SettleStoredUploads,
    private val count: CountOwnPhotos,
    private val receiving: ForegroundReceiving,
    private val stateRefresh: StateRefresh,
    private val leftovers: SweepLeftovers,
) {
    @OptIn(EntryPointCall::class)
    suspend fun run(on: Run) = on.steps {
        required { prelude.run(on) }
        concurrently {
            child("staging") { staging.releaseUnclaimed(membership.active()?.let { it.event.id }) }
            child("settle") { settle(membership.active()?.let { it.event.id }) }
            child("total") { count() }
            receiving.children(this, on)
            child("membership") { stateRefresh.run(on) }
            child("leftovers") { leftovers.run(on) }
        }
    }
}

/**
 * What a wake hands on after its own work: the imports and this device's uploads at the same time, then the end of the
 * wake once both have ended — and, however those ended, the re-arm the next wake depends on. Neither stops the other:
 * an import can stall in the library, and uploads never wait behind it.
 */
class WakeWork(
    private val prelude: Prelude,
    private val importReceived: ImportReceived,
    private val uploadOwn: UploadOwn,
    private val rearm: RearmHeartbeat,
    private val downloads: ReceivingCounts,
    private val end: EndOfWake,
    private val footprints: RecordFootprint,
) {
    @OptIn(EntryPointCall::class)
    suspend fun full(kind: WakeKind, on: Run) = on.steps {
        var uploads: Pass<UploadPass> = Pass.Cut
        ensure("re-arm") { rearm(leftOf(uploads), downloads.stagedImports()) }
        ensure("footprint") { footprints("after the ${kind.name.lowercase()} wake") }
        concurrently {
            child("import received") { run(importReceived, kind.reads) }
            child("upload own") { uploads = run(uploadOwn) }
        }
        isolated("end of wake") { end.run(kind, on) }
    }

    /** [full], after the re-read every wake's own work starts with — for a wake that has no other. */
    suspend fun afterPrelude(kind: WakeKind, on: Run) = on.steps {
        required { prelude.run(on) }
        required { full(kind, on) }
    }

    /** What the heartbeat reads of a wake's uploads: a cut pass says nothing about whether work remains, so it does. */
    private fun leftOf(uploads: Pass<UploadPass>): UploadsLeft = uploads.orIfCut(UploadPass.WORK_LEFT).left()
}

// ── The sequences a wake runs, single-flight across the process ──────────────────────────────────────────────────────

/**
 * What a wake was for, as its end reads it (decision record `changes/timely-background-receiving`, D4–D5).
 * [checksPhotos]: the end runs the bounded photo check — every wake but a membership transition's, which read the union
 * in its own work. [boundsEventRead]: the end's read of the event's state is bounded to once an hour — every wake but
 * one whose own reason is to ask the event now (a push, an opening, a returned network, a join). [reads]: why the
 * wake's own union reads say the app read — a recognition its import still needs tells the backend this.
 */
enum class WakeKind(val checksPhotos: Boolean, val boundsEventRead: Boolean, val reads: UnionTrigger) {
    /** A membership transition — a join, mostly; its own work recognised already, so its wake rarely reads. */
    ARM(checksPhotos = false, boundsEventRead = false, reads = UnionTrigger.JOIN),
    FOREGROUND(checksPhotos = true, boundsEventRead = false, reads = UnionTrigger.FOREGROUND),

    /** The network came back while the app was in front. */
    NETWORK(checksPhotos = true, boundsEventRead = false, reads = UnionTrigger.FOREGROUND),
    MOBILE_DATA(checksPhotos = true, boundsEventRead = true, reads = UnionTrigger.RECONFIGURE),
    SILENT_PUSH(checksPhotos = true, boundsEventRead = false, reads = UnionTrigger.PUSH),

    /** A partial grant's selection changed: the photo access it gives. */
    SELECTION_CHANGE(checksPhotos = true, boundsEventRead = true, reads = UnionTrigger.GRANT),
    SCHEDULED(checksPhotos = true, boundsEventRead = true, reads = UnionTrigger.WAKE),
    UPLOAD_SESSION(checksPhotos = true, boundsEventRead = true, reads = UnionTrigger.WAKE),
    DOWNLOAD_SESSION(checksPhotos = true, boundsEventRead = true, reads = UnionTrigger.WAKE),
}

/**
 * The membership changed — or the app launched: every report this process sends from now on names the event and the
 * device it is about.
 */
class OnMembershipChanged(runtime: Runtime, private val describe: DescribeReporter) :
    BackgroundEntryPoint<MembershipRead>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: MembershipRead, on: Run) = on.steps {
        required { describe() }
    }
}
