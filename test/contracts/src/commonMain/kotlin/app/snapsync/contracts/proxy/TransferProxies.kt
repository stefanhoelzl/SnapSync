package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Running
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers

/**
 * The [Completion] handle an adapter hands its owner, recorded under the port handing it out ([r] is that port's
 * `Completion` recorder): what the owner calls on it.
 */
internal class CompletionProxy(private val inner: Completion, private val r: Recorder) : Completion {
    override fun complete() = r.returns("complete", inner.complete())
}

/** [Download] as its clause's [CallLog] sees it. */
fun Download.recorded(log: CallLog): Download = DownloadProxy(log.wraps(this), log)

internal class DownloadProxy(private val inner: Download, log: CallLog) : Download {
    private val r = log.recorder("Download")

    override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork) = r.answer(
        "start",
        inner.start(url, headers, tag, network),
    )
    override suspend fun cancelAll() = r.returns("cancelAll", inner.cancelAll())

    @CompositionOnly
    override fun inbound(handlers: DownloadHandlers) = r.returns(
        "inbound",
        inner.inbound(
            DownloadHandlers(
                InboundProxy(handlers.onFinished, r, "onFinished", "FinishedDownload"),
                InboundProxy(handlers.onCompleted, r, "onCompleted", "CompletedDownload"),
                BackgroundProxy(handlers.onBackgroundEvents, r, "onBackgroundEvents", "SessionRelaunch"),
                InboundProxy(handlers.onEventsDrained, r, "onEventsDrained", "SessionDrain"),
            ),
        ),
    )
}

/** [OsUploadQueue] as its clause's [CallLog] sees it. */
fun OsUploadQueue.recorded(log: CallLog): OsUploadQueue = OsUploadQueueProxy(log.wraps(this), log)

internal class OsUploadQueueProxy(private val inner: OsUploadQueue, log: CallLog) : OsUploadQueue {
    private val r = log.recorder("OsUploadQueue")

    override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget) =
        r.answer("create", inner.create(resource, target))
    override suspend fun retryOffered() = r.returns("retryOffered", inner.retryOffered())
    override suspend fun terminals() = r.returns("terminals", inner.terminals())
    override suspend fun retry(job: OsUploadJob, target: UploadTarget) = r.answer("retry", inner.retry(job, target))
    override suspend fun acknowledge(job: OsUploadJob) = r.answer("acknowledge", inner.acknowledge(job))
}

/** [TransferSession] as its clause's [CallLog] sees it. */
fun TransferSession.recorded(log: CallLog): TransferSession = TransferSessionProxy(log.wraps(this), log)

internal class TransferSessionProxy(private val inner: TransferSession, log: CallLog) : TransferSession {
    private val r = log.recorder("TransferSession")

    override val takesResources get() = r.answer("takesResources", inner.takesResources)
    override suspend fun create(source: TransferSource, target: UploadTarget, tag: String) =
        r.answer("create", inner.create(source, target, tag))
    override suspend fun live() = r.returns("live", inner.live())
    override suspend fun cancel(transfer: Transfer) = r.returns("cancel", inner.cancel(transfer))

    @CompositionOnly
    override fun inbound(handlers: TransferHandlers) = r.returns(
        "inbound",
        inner.inbound(
            TransferHandlers(
                InboundProxy(handlers.onFinished, r, "onFinished", "FinishedTransfer"),
                BackgroundProxy(handlers.onBackgroundEvents, r, "onBackgroundEvents", "SessionRelaunch"),
                InboundProxy(handlers.onEventsDrained, r, "onEventsDrained", "SessionDrain"),
            ),
        ),
    )
}

/** [Wake] as its clause's [CallLog] sees it. */
fun Wake.recorded(log: CallLog): Wake = WakeProxy(log.wraps(this), log)

internal class WakeProxy(private val inner: Wake, log: CallLog) : Wake {
    private val r = log.recorder("Wake")

    override suspend fun schedule(id: WakeId, trigger: WakeTrigger) = r.answer("schedule", inner.schedule(id, trigger))
    override fun cancel(id: WakeId) = r.returns("cancel", inner.cancel(id))

    @CompositionOnly
    override fun inbound(handlers: WakeHandlers) = r.returns(
        "inbound",
        inner.inbound(WakeHandlers(GrantedProxy(handlers.onWake, r, "onWake"))),
    )
}

/** [BackgroundTime] as its clause's [CallLog] sees it. */
fun BackgroundTime.recorded(log: CallLog): BackgroundTime = BackgroundTimeProxy(log.wraps(this), log)

internal class BackgroundTimeProxy(private val inner: BackgroundTime, log: CallLog) : BackgroundTime {
    private val r = log.recorder("BackgroundTime")

    override fun begin(label: String, expiry: Running): BackgroundTimeHold {
        val hold = r.returns("begin", inner.begin(label, RunningProxy(expiry, r.handle("Running"))))
        return HoldProxy(hold, r.handle("BackgroundTimeHold"))
    }

    private class HoldProxy(private val inner: BackgroundTimeHold, private val r: Recorder) : BackgroundTimeHold {
        override fun end() = r.returns("end", inner.end())
    }
}

/** [ExtensionRegistry] as its clause's [CallLog] sees it. */
fun ExtensionRegistry.recorded(log: CallLog): ExtensionRegistry = ExtensionRegistryProxy(log.wraps(this), log)

internal class ExtensionRegistryProxy(private val inner: ExtensionRegistry, log: CallLog) : ExtensionRegistry {
    private val r = log.recorder("ExtensionRegistry")

    override val supported get() = r.answer("supported", inner.supported)
    override suspend fun setEnabled(enabled: Boolean) = r.answer("setEnabled", inner.setEnabled(enabled))
    override fun isEnabled() = r.answer("isEnabled", inner.isEnabled())
}
