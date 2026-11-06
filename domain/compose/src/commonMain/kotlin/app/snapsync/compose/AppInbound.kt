package app.snapsync.compose

import app.snapsync.entrypoints.HostAssembly
import app.snapsync.entrypoints.OnBackground
import app.snapsync.entrypoints.OnChangedElsewhere
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
import app.snapsync.entrypoints.OnUploadFinished
import app.snapsync.entrypoints.OnUploadSessionEvents
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.WakeHandlers

/**
 * Hands every app entry point to its port (`docs/patterns.md`, "Composition") — the graph is built, so each port may
 * start delivering. The UI's deliveries are the host zone's: they reach the status host, which this zone cannot see.
 * [host] is the status host's assembly, which the foreground asks for first.
 */
@CompositionOnly
fun AppCore.inbound(host: HostAssembly) {
    ports.gallery.inbound(
        GalleryHandlers(OnImportPlaceholder(runtime, receiving.markers), OnImportSettled(runtime, receiving.markers)),
    )
    ports.wake.inbound(WakeHandlers(OnScheduledWake(runtime, work.wakeWork)))
    ports.download.inbound(
        DownloadHandlers(
            OnDownloadFinished(runtime, receiving.stage, receiving.record, work.downloadStaged),
            OnDownloadCompleted(runtime, receiving.free),
            OnDownloadSessionEvents(runtime, downloadDrains, work.wakeWork),
            OnSessionDrained(runtime, downloadDrains),
        ),
    )
    ports.uploadSession.inbound(
        TransferHandlers(
            OnUploadFinished(runtime, appUploader.recordFinished, appUploader.admission, work.topUp),
            OnUploadSessionEvents(runtime, uploadDrains, work.wakeWork),
            OnSessionDrained(runtime, uploadDrains),
        ),
    )
    ports.lifecycle.inbound(
        LifecycleHandlers(
            OnForeground(
                runtime,
                serviceConditions,
                foreground,
                services.pushTokens,
                work.prelude,
                host,
                network.foreground,
                work.foregroundWork,
                work.wakeWork,
            ),
            OnBackground(runtime, foreground, network.foreground, footprints),
        ),
    )
    ports.pushNotifications.inbound(pushHandlers())
    ports.links.inbound(LinkHandlers(OnLink(runtime, linkReading, openedInvites)))
    // What the upload extension commits reaches the app's readers of that store. The app's alone: the extension
    // registers no handler, and follows nothing.
    ports.databases.inbound(DatabaseHandlers(OnChangedElsewhere(runtime, status.changedElsewhere)))
}

/**
 * The app's push entry points — what [inbound] hands the push port, and what a contract run on the app's own push
 * adapter hands back when its clause is done.
 */
@CompositionOnly
fun AppCore.pushHandlers(): PushHandlers = PushHandlers(
    OnPushToken(runtime, services.pushTokens, push.register),
    OnPushTokenFailure(runtime),
    OnSilentPush(runtime, work.prelude, push.tail, work.planJoined, work.wakeWork),
)

/**
 * The network's return, while the app is in front: followed from the status host's assembly on — a person can see what
 * it tells them only then.
 */
@CompositionOnly
fun AppCore.followNetworkReturns() =
    observation.follow(network.status.returned, OnNetworkReturned(runtime, work.foregroundWork, work.wakeWork))
