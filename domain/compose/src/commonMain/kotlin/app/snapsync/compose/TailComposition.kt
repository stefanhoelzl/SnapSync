@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.compose

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.upload.AppUploadEngine
import app.snapsync.feature.upload.CadenceFacts
import app.snapsync.feature.upload.TailRunner
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.feature.upload.heartbeatWake
import app.snapsync.feature.upload.thenTail
import app.snapsync.feature.upload.thenTailWhen
import app.snapsync.model.contained
import app.snapsync.model.invocation
import app.snapsync.ports.EntryContext
import app.snapsync.services.crash.FootprintSampler
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.wake.Heartbeat
import app.snapsync.services.wake.OsCompletions
import app.snapsync.services.wake.WakeHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The app process's **opportunistic tail** as composed, and everything that reaches it without an OS handler of its
 * own: each OS wake does its own work, then hands the rest to one opportunistic tail (decision record
 * `changes/own-work-per-wake`, D1, D2 and D11).
 *
 * One [runner] per process, over the three units: ① the download arm's staged-import drain, ② and ③ the app
 * uploader's top-up and walk. Each entry port's handler (`lifecycleHandlers`, `pushHandlers`, the wake's and the
 * transfers') requests it after each wake's own work;
 * this class holds the requests that come from elsewhere — a membership transition's arm, an upload completion, a
 * staged download, a selection change — and the one fact only the entries know, whether the app is foregrounded.
 *
 * A separate class rather than `AppCore` members for the reason the `compose` tier's `LargeClass` and
 * `TooManyFunctions` ceilings exist: `AppCore` is already the graph, and this is one coherent piece of it.
 */
class AppTail internal constructor(
    private val scope: CoroutineScope,
    private val services: AppServices,
    /** The app's uploader — resolved on first use, since it depends on the graph this tail belongs to. */
    private val appUploader: () -> AppUploader,
    /** The process's entry-point seam, which the tail's lines carry. */
    private val entryContext: EntryContext,
    private val downloads: () -> DownloadController,
    /** What the photo grant means now — the walk runs under a full one only. */
    private val galleryAccess: GalleryAccessState,
    /** The app's admission as a Boolean — whether a completion may request the top-up. */
    private val mayCreate: () -> Boolean,
    /** What the heartbeat's re-arm reads after a tail — see `cadenceFacts`. */
    private val cadenceFacts: () -> CadenceFacts,
    /** The in-process ledger-counts re-read, run after a tail unit only while foregrounded. */
    private val refreshCounts: suspend () -> Unit,
    /**
     * The end of every wake that handed its rest to the tail: the event-completion step, run once the tail has ended,
     * outside it — it may leave the event, and a leave must never run inside the tail it would stop.
     */
    private val finish: suspend (TailTrigger) -> Unit,
) {
    private val foreground = AtomicBoolean(false)

    /** The app's memory readings, taken only while it is not foregrounded — see [FootprintSampler]. */
    private val footprints = FootprintSampler(
        services.ports.processInfo,
        services.process.footprints,
    ) { foreground.load() }

    /** The app uploader, resolved at first use — it owns a process-lifetime background session on a device. */
    private val uploader: AppUploader get() = appUploader()

    /** The heartbeat the runner re-arms and a disarm cancels — the process's, over the `Wake` port. */
    private val heartbeat = Heartbeat(
        services.ports.wake,
        transferNetwork = services.mobileData::transferNetwork,
        log = services.log,
    )

    /** The one tail runner of this process. */
    val runner: TailRunner by lazy {
        TailRunner(
            // Each import runs as its own job on the composition scope, which the drain awaits unless interrupted.
            importStaged = { signal ->
                downloads().importReady(signal::stopRequested) { import -> signal.awaitImport(scope, import) }
            },
            topUp = { stop -> uploader.topUp(stop) },
            walkAndPublish = { stop -> uploader.walkAndPublish(stop) },
            // Exactly a full grant: under a partial one the tail reads no library.
            walkPermitted = { galleryAccess.full },
            mayCreate = mayCreate,
            foregrounded = { foreground.load() },
            refreshStatus = refreshCounts,
            heartbeat = heartbeat,
            importsRemain = { services.downloadStore.importableAssets().isNotEmpty() },
            cadenceFacts = cadenceFacts,
            leftover = { "staged downloads not yet imported: ${services.downloadStore.importableAssets().size}" },
            log = services.log,
            entryContext = entryContext,
        )
    }

    /**
     * Record whether the app is foregrounded — written by the foreground and background entries, read by the runner
     * after each unit: counts are refreshed in-process only while something renders them.
     */
    internal fun foregrounded(value: Boolean) = foreground.store(value)

    /**
     * Request [trigger]'s tail without awaiting it, for a caller that holds no OS handler and must not wait on the
     * tail: a transition, a completion, a staging. It holds the process's background time from the request to the
     * tail's end ([WakeHold]), taken here, before the launch, so no instant between the two holds nothing. A failed
     * tail is logged by the hold — nobody else awaits it.
     */
    fun requestDetached(trigger: TailTrigger) {
        val hold = hold("tail($trigger)")
        scope.launch { handTo(hold, trigger) }
    }

    /** A hold on the process's background time for [label], whose expiry stops this tail. */
    internal fun hold(label: String): WakeHold = WakeHold(
        label,
        services.ports.backgroundTime,
        stopTail = runner::stop,
        log = services.log,
        settling = { footprints.record("after $it") },
    )

    /** Hand [trigger]'s tail to the runner under [hold] — see `thenTail`. */
    internal suspend fun handTo(hold: WakeHold, trigger: TailTrigger) = hold.thenTail(trigger, runner, finish)

    /** [handTo] for a wake that joins the tail only when [joins] — see `thenTailWhen`. */
    internal suspend fun handToWhen(joins: Boolean, hold: WakeHold, trigger: TailTrigger) =
        hold.thenTailWhen(joins, trigger, runner, finish)

    /** Read the app's own memory footprint at [moment] — see [FootprintSampler]. */
    internal fun recordFootprint(moment: String) = footprints.record(moment)

    /**
     * The heartbeat wake's hand-over — it holds no [WakeHold] of its own: its tail, then the end-of-wake step, unless
     * the OS's time is already up ([released]). See `heartbeatWake`.
     */
    internal suspend fun heartbeat(label: String, released: () -> Boolean) = heartbeatWake(
        label,
        released,
        runner,
        finish,
        services.log,
        settling = { footprints.record("after $label") },
    )

    /**
     * The seam the membership transitions drive: an arm requests the tail — detached,
     * because a transition runs inside a flow or a tap, and a flow never awaits the tail (`docs/architecture.md`,
     * "A trigger flow never outlives its own run"); a disarm cancels the heartbeat; a leave cancels the transfers.
     */
    val appEngine: AppUploadEngine = object : AppUploadEngine {
        override suspend fun arm() = requestDetached(TailTrigger.ARM)
        override suspend fun disarm() = heartbeat.cancel()
        override suspend fun cancelTransfers() = uploader.cancelTransfers()
    }

    /**
     * The upload session's OS completion handlers (`handleEventsForBackgroundURLSession`), held from the handover to
     * the session's drain report — the relaunch's own work, recording the terminals, is done by then. The adapter's
     * completion puts the release on the main thread UIKit requires.
     */
    val uploadCompletions: OsCompletions = OsCompletions("url-session.onBackgroundSessionEvents", log = services.log)

    /**
     * A transfer reached its terminal outcome — **already recorded** by the transport's guarded write — and freed a
     * slot: the tail's top-up is requested, which the runner runs only while the app may create. The delegate records
     * the terminal fact before it returns.
     */
    internal fun uploadCompleted() = requestDetached(TailTrigger.UPLOAD_COMPLETED)

    /**
     * The upload session reported every event it had delivered (`urlSessionDidFinishEvents`): the relaunch's own work —
     * recording the terminals — is done, so the handlers the wake handed over are released.
     */
    internal fun eventsDrained() {
        scope.launch { uploadCompletions.releaseAfter { } }
    }

    /**
     * A selection change under a partial grant: its **own work** is the

     * snapshot-fed discovery → manifest publish — the uploader's walk unit, whose discovery binding is the selection
     * snapshot there — then the tail (① import, ② top-up from the snapshot; never ③ under a partial grant).
     */
    internal suspend fun onSelectionChanged() = services.log.invocation(entryContext, "onSelectionChanged") {
        // Held from before its own work to its tail's end, like any in-process request (see [requestDetached]).
        val hold = hold("onSelectionChanged")
        services.log.contained("the selection change's discovery failed; its tail still runs") {
            uploader.walkAndPublish { false }
        }
        handTo(hold, TailTrigger.SELECTION_CHANGE)
    }
}
