@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.compose

import app.snapsync.ports.EntryContext
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.upload.AppUploadEngine
import app.snapsync.feature.upload.AppUploadEvents
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.TailRunner
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.GalleryAccess
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.wake.Heartbeat
import app.snapsync.services.wake.OsCompletions
import app.snapsync.ports.invocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The app process's **opportunistic tail** as composed, and everything that reaches it without an OS handler of its
 * own (capability `sync-status`, "Each OS wake does its own work, then hands the rest to one opportunistic tail";
 * decision record `changes/own-work-per-wake`, D1, D2 and D11).
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
    private val ports: AppPorts,
    /** The process's entry-point seam, which the tail's lines carry. */
    private val entryContext: EntryContext,
    private val downloads: () -> DownloadController,
    /** The app's admission as a Boolean — whether a completion may request the top-up. */
    private val mayCreate: () -> Boolean,
    /** The in-process ledger-counts re-read, run after a tail unit only while foregrounded. */
    private val refreshCounts: suspend () -> Unit,
) {
    private val foreground = AtomicBoolean(false)

    /** The app uploader, resolved at first use — it owns a process-lifetime background session on a device. */
    private val mechanism: AppUploadMechanism get() = ports.appDrivenUpload()

    /** The heartbeat the runner re-arms and a disarm cancels — the process's, over the `Wake` port. */
    private val heartbeat = Heartbeat(ports.wake, ports.log)

    /** The one tail runner of this process. */
    val runner: TailRunner by lazy {
        TailRunner(
            // Each import runs as its own job the drain awaits — unless the tail's time is up or another request is
            // due, when the wait gives way and the import runs on, claimed (capability `receiving-photos`, "A stalled
            // import blocks no other work"). An import that throws surfaces at the await that sees it — held as a
            // `Result`, so a throw nobody awaits any more cannot fail the composition scope it runs in.
            importStaged = { signal ->
                downloads().importReady(signal::stopRequested) { import ->
                    val job = scope.async { runCatchingCancellable { import() } }
                    if (signal.awaitUnlessInterrupted(job)) job.await().getOrThrow()
                }
            },
            topUp = { stop -> mechanism.topUp(stop) },
            walkAndPublish = { stop -> mechanism.walkAndPublish(stop) },
            // Exactly a full grant: under a partial one the tail reads no library (capability `photo-access`).
            walkPermitted = { ports.photoAccess.permission.value == GalleryAccess.GRANTED },
            mayCreate = mayCreate,
            foregrounded = { foreground.load() },
            refreshStatus = refreshCounts,
            heartbeat = heartbeat,
            importsRemain = { ports.downloadStore.importableAssets().isNotEmpty() },
            leftover = { "staged downloads not yet imported: ${ports.downloadStore.importableAssets().size}" },
            log = ports.log,
            entryContext = entryContext,
        )
    }

    /**
     * Record whether the app is foregrounded — written by the foreground and background entries, read by the runner
     * after each unit: counts are refreshed in-process only while something renders them (capability `sync-status`).
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
        scope.launch { hold.thenTail(trigger) }
    }

    /** A hold on the process's background time for [label], whose expiry stops this tail. */
    internal fun hold(label: String): WakeHold = WakeHold(label, ports.backgroundTime, runner, ports.log)

    /**
     * The seam the membership transitions drive (capability `background-upload`): an arm requests the tail — detached,
     * because a transition runs inside a flow or a tap, and a flow never awaits the tail (`docs/architecture.md`,
     * "A trigger flow never outlives its own run"); a disarm cancels the heartbeat; a leave cancels the transfers.
     */
    val appEngine: AppUploadEngine = object : AppUploadEngine {
        override suspend fun arm() = requestDetached(TailTrigger.ARM)
        override suspend fun disarm() = heartbeat.cancel()
        override suspend fun cancelTransfers() = mechanism.cancelTransfers()
    }

    /**
     * The upload session's OS completion handlers (`handleEventsForBackgroundURLSession`), held from the handover to
     * the session's drain report — the relaunch's own work, recording the terminals, is done by then (capability
     * `sync-status`). The adapter's completion puts the release on the main thread UIKit requires.
     */
    val uploadCompletions: OsCompletions = OsCompletions("url-session.onBackgroundSessionEvents", log = ports.log)

    /** What the upload transport tells the core — see [AppUploadEvents]. */
    val uploadEvents: AppUploadEvents = object : AppUploadEvents {
        // The completion was recorded by the transport already; the runner requests the top-up only on `Admit`.
        override fun uploadCompleted() = requestDetached(TailTrigger.UPLOAD_COMPLETED)

        override fun eventsDrained() {
            scope.launch { uploadCompletions.releaseAfter { } }
        }
    }

    /**
     * A selection change under a partial grant (capability `photo-access`): its **own work** is the
     * snapshot-fed discovery → manifest publish — the uploader's walk unit, whose discovery binding is the selection
     * snapshot there — then the tail (① import, ② top-up from the snapshot; never ③ under a partial grant).
     */
    internal suspend fun onSelectionChanged() = ports.log.invocation(entryContext, "onSelectionChanged") {
        // Held from before its own work to its tail's end, like any in-process request (see [requestDetached]).
        val hold = hold("onSelectionChanged")
        runCatchingCancellable { mechanism.walkAndPublish { false } }
            .onFailure { ports.log.w(it) { "the selection change's discovery failed; its tail still runs" } }
        hold.thenTail(TailTrigger.SELECTION_CHANGE)
    }
}
