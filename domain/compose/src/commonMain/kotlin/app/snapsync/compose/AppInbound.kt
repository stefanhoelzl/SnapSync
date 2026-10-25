package app.snapsync.compose

import app.snapsync.entrypoints.HostAssembly
import app.snapsync.entrypoints.OnBackground
import app.snapsync.entrypoints.OnDownloadCompleted
import app.snapsync.entrypoints.OnDownloadFinished
import app.snapsync.entrypoints.OnDownloadSessionEvents
import app.snapsync.entrypoints.OnForeground
import app.snapsync.entrypoints.OnImportPlaceholder
import app.snapsync.entrypoints.OnImportSettled
import app.snapsync.entrypoints.OnLink
import app.snapsync.entrypoints.OnNetworkReturned
import app.snapsync.entrypoints.OnPushToken
import app.snapsync.entrypoints.OnPushTokenFailure
import app.snapsync.entrypoints.OnScheduledWake
import app.snapsync.entrypoints.OnSessionDrained
import app.snapsync.entrypoints.OnSilentPush
import app.snapsync.entrypoints.OnTap
import app.snapsync.entrypoints.OnUploadFinished
import app.snapsync.entrypoints.OnUploadSessionEvents
import app.snapsync.entrypoints.Screen
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.WakeHandlers
import app.snapsync.services.push.PushTokenRequest
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Hands every app entry point to its port (`docs/patterns.md`, "Composition") — the graph is built, so each port may
 * start delivering. The UI's deliveries are the host zone's: they reach the status host, which this zone cannot see.
 * [host] is the status host's assembly, which the foreground asks for first.
 */
@CompositionOnly
fun AppCore.inbound(host: HostAssembly) {
    ports.gallery.inbound(
        GalleryHandlers(OnImportPlaceholder(runtime, importMarkers), OnImportSettled(runtime, importMarkers)),
    )
    ports.wake.inbound(WakeHandlers(OnScheduledWake(runtime, work.wakeWork)))
    ports.download.inbound(
        DownloadHandlers(
            OnDownloadFinished(runtime, downloadJobs),
            OnDownloadCompleted(runtime, downloadJobs),
            OnDownloadSessionEvents(runtime, downloadDrains, downloadJobs, work.wakeWork),
            OnSessionDrained(runtime, downloadDrains),
        ),
    )
    ports.uploadSession.inbound(
        TransferHandlers(
            OnUploadFinished(runtime, uploadTransfer, work.ownUploads, work.topUp),
            OnUploadSessionEvents(runtime, uploadDrains, work.wakeWork),
            OnSessionDrained(runtime, uploadDrains),
        ),
    )
    ports.lifecycle.inbound(
        LifecycleHandlers(
            OnForeground(
                runtime,
                foreground,
                PushTokenRequest(ports.pushNotifications),
                work.prelude,
                host,
                work.foregroundWork,
                work.wakeWork,
            ),
            OnBackground(runtime, foreground, network.watches, footprints),
        ),
    )
    ports.pushNotifications.inbound(pushHandlers())
    ports.devControls.inbound(DevHandlers(work.devReset))
    ports.links.inbound(LinkHandlers(OnLink(runtime, linkOpening, openedLinks)))
}

/**
 * The app's push entry points — what [inbound] hands the push port, and what a contract run on the app's own push
 * adapter hands back when its clause is done.
 */
@CompositionOnly
fun AppCore.pushHandlers(): PushHandlers = PushHandlers(
    OnPushToken(runtime, services.pushTokens),
    OnPushTokenFailure(runtime),
    OnSilentPush(runtime, work.prelude, downloadPushReceiver, pushTailGuard, work.wakeWork),
)

/**
 * The network's return, while the app is in front: followed from the status host's assembly on — a person can see what
 * it tells them only then.
 */
@CompositionOnly
fun AppCore.followNetworkReturns() =
    observation.follow(network.status.returned, OnNetworkReturned(runtime, work.foregroundWork, work.wakeWork))

/**
 * The screen's taps, as the UI port delivers them: each an [OnTap] over [table] — the status host's intent table,
 * which the host zone holds and this zone cannot see — handed the tap's context and its joining for the work it starts.
 */
@CompositionOnly
fun <I : Any> AppCore.taps(table: (I, CoroutineContext, (Job) -> Unit) -> Unit): Inbound<I, Unit> =
    OnTap(
        runtime,
        object : Screen<I> {
            override fun tapped(intent: I, context: CoroutineContext, joining: (Job) -> Unit) =
                table(intent, context, joining)
        },
    )
