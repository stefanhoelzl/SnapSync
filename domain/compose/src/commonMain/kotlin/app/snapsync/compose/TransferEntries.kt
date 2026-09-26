package app.snapsync.compose

import app.snapsync.feature.upload.TailTrigger
import app.snapsync.ports.WakeHandlers
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.services.upload.UploadTransferService
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.invocation
import app.snapsync.services.wake.OsCompletions
import kotlinx.coroutines.launch

/**
 * What the operating system tells [core] through its event ports — the scheduled wakes and the transfer sessions —
 * registered by the host zone's `listen` as the graph is composed, so a background launch that delivers one finds it
 * (phase 11f). Each bundle is built here, in the composition (law "Commands cross one door").
 */
class AppEvents internal constructor(private val core: AppCore) {
    /** The operating system's scheduled wakes — the heartbeat. */
    val wakeHandlers: WakeHandlers = wakeHandlersOf(core)

    /** The download session's events. */
    val downloadHandlers: DownloadHandlers = downloadHandlersOf(core)

    /** The app uploader's session events. */
    val uploadHandlers: UploadHandlers = uploadHandlersOf(core)

    /**
     * The app uploader's transfer service over [AppPorts.appUpload] — what records a transfer's end the moment the
     * platform reports it ([uploadHandlers]) and cancels the in-flight transfers at a leave.
     */
    val uploadTransfer: UploadTransferService by lazy {
        UploadTransferService(
            upload = core.ports.appUpload,
            record = core.ports.uploadRecord.ledger,
            resources = GalleryDiscovery(core.ports.gallery),
            gallery = core.ports.gallery,
            files = core.process.files,
            log = core.ports.log,
            entryContext = core.process.entryContext,
        )
    }
}

/**
 * What the download port tells [core] — registered by the host zone's `listen`, as the graph is composed, so a
 * background relaunch that delivers finished transfers finds them (capability `receiving-photos`). Each handler is one
 * call into the download jobs; the background-events one is a transfer wake ([onTransferEvents]).
 */
internal fun downloadHandlersOf(core: AppCore): DownloadHandlers = DownloadHandlers(
    onFinished = { tag, facts, tempPath -> core.downloadJobs.onFinished(tag, facts, tempPath) },
    onCompleted = { tag, error -> core.downloadJobs.onCompleted(tag, error) },
    onInvalidated = { core.downloadJobs.onInvalidated() },
    onBackgroundEvents = { completion ->
        core.onTransferEvents("download", TailTrigger.DOWNLOAD_SESSION_EVENTS) {
            core.downloadJobs.adoptBackgroundEvents(completion)
        }
    },
    onEventsDrained = { core.downloadJobs.onBackgroundEventsFinished() },
)

/**
 * What the app's upload port tells [core] — registered by the host zone's `listen`. A transfer's end is recorded
 * **inline**, by the upload service's guarded write, before the platform's callback returns (the platform tells it
 * once), then the core's tail tops up the freed slot; the background-events one is a transfer wake
 * ([onTransferEvents]).
 */
internal fun uploadHandlersOf(core: AppCore): UploadHandlers = UploadHandlers(
    onFinished = { job ->
        core.events.uploadTransfer.recordFinished(job)
        core.tail.uploadEvents.uploadCompleted()
    },
    onBackgroundEvents = { completion ->
        core.onTransferEvents("upload", TailTrigger.UPLOAD_SESSION_EVENTS) {
            core.tail.uploadCompletions.adopt(completion)
        }
    },
    onEventsDrained = { core.tail.uploadEvents.eventsDrained() },
)

/**
 * **A background-transfer wake** (capability `sync-status`; decision record `changes/own-work-per-wake`, D5): the
 * operating system relaunched (or woke) the app to hand back a session's finished transfers, with a completion handler.
 *
 * The background time comes first — no later than the handover — so the wait for the session's drain report is
 * covered too, and a report that never comes ends in Apple's expiry rather than a handler held forever. The handler is
 * adopted synchronously ([adopt]), before the adapter brings its session up to deliver the events. The wake's own work
 * is the session's — its deliveries, recorded as they arrive, and its drain report, which releases the handler; then
 * the prelude, and the rest is the tail's.
 */
internal fun AppCore.onTransferEvents(session: String, trigger: TailTrigger, adopt: () -> OsCompletions.Handover) =
    ports.log.invocation(process.entryContext, "onBackgroundTransfers", params = "session=$session") {
        val wake = tail.hold("onBackgroundTransfers($session)")
        val handover = adopt()
        wake.guard(handover)
        scope.launch {
            // The protected-storage state for this wake (capability `sync-status`), recorded one dispatch later: the
            // read may have to hop threads, and the adoption above must not wait for it.
            val protectedData = ports.processInfo.protectedDataAvailable()
            ports.log.i { "onBackgroundTransfers(session=$session): protectedData=$protectedData" }
            prelude()
            handover.awaitRelease()
            wake.thenTail(trigger)
        }
        Unit
    }
