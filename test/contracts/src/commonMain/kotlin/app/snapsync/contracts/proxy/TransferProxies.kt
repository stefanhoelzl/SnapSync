package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.ExpiringCompletion
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers

/**
 * The [Completion] handle an adapter hands its owner, recorded under the port handing it out ([r] is that port's
 * `Completion` recorder): what the owner calls on it.
 */
internal class CompletionProxy(private val inner: Completion, private val r: Recorder) : Completion {
    override fun complete() = r.returns("complete", inner.complete())
}

/** The [ExpiringCompletion] a wake hands its owner: [CompletionProxy]'s release, and the expiry action called back. */
internal class ExpiringCompletionProxy(
    private val inner: ExpiringCompletion,
    private val r: Recorder,
) : ExpiringCompletion {
    override fun complete() = r.returns("complete", inner.complete())
    override fun onExpired(action: () -> Unit) = r.returns(
        "onExpired",
        inner.onExpired {
            r.called("onExpired.action")
            action()
        },
    )
}

/** [Download] as its clause's [CallLog] sees it. */
fun Download.recorded(log: CallLog): Download = DownloadProxy(this, log)

internal class DownloadProxy(private val inner: Download, log: CallLog) : Download {
    private val r = log.recorder("Download")

    override fun start(url: String, tag: String, network: TransferNetwork) = r.answer(
        "start",
        inner.start(url, tag, network),
    )
    override suspend fun cancelAll() = r.returns("cancelAll", inner.cancelAll())

    override fun listen(handlers: DownloadHandlers) = r.returns(
        "listen",
        inner.listen(
            DownloadHandlers(
                onFinished = { tag, facts, tempPath ->
                    r.called(
                        "handlers.onFinished",
                        Recorder.arg(tag, "String"),
                        Recorder.arg(facts, "TransferOutcome"),
                        Recorder.arg(tempPath, "String"),
                    )
                    handlers.onFinished(tag, facts, tempPath)
                },
                onCompleted = { tag, error ->
                    r.called("handlers.onCompleted", Recorder.arg(tag, "String"), Recorder.arg(error, "String"))
                    handlers.onCompleted(tag, error)
                },
                onBackgroundEvents = { completion ->
                    r.called("handlers.onBackgroundEvents", Recorder.arg(completion, "Completion"))
                    handlers.onBackgroundEvents(CompletionProxy(completion, r.handle("Completion")))
                },
                onEventsDrained = {
                    r.called("handlers.onEventsDrained")
                    handlers.onEventsDrained()
                },
            ),
        ),
    )
}

/** [Upload] as its clause's [CallLog] sees it. */
fun Upload.recorded(log: CallLog): Upload = UploadProxy(this, log)

internal class UploadProxy(private val inner: Upload, log: CallLog) : Upload {
    private val r = log.recorder("Upload")

    override val accepts get() = r.answer("accepts", inner.accepts)
    override val acceptsFiles get() = r.answer("acceptsFiles", inner.acceptsFiles)
    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String) =
        r.answer("create", inner.create(source, target, tag))
    override suspend fun jobs(set: UploadJobSet) = r.returns("jobs", inner.jobs(set))
    override suspend fun retry(job: UploadJob, target: UploadTarget) = r.answer("retry", inner.retry(job, target))
    override suspend fun acknowledge(job: UploadJob) = r.answer("acknowledge", inner.acknowledge(job))
    override suspend fun cancel(job: UploadJob) = r.answer("cancel", inner.cancel(job))

    override fun listen(handlers: UploadHandlers) = r.returns(
        "listen",
        inner.listen(
            UploadHandlers(
                onFinished = { job ->
                    r.called("handlers.onFinished", Recorder.arg(job, "UploadJob"))
                    handlers.onFinished(job)
                },
                onBackgroundEvents = { completion ->
                    r.called("handlers.onBackgroundEvents", Recorder.arg(completion, "Completion"))
                    handlers.onBackgroundEvents(CompletionProxy(completion, r.handle("Completion")))
                },
                onEventsDrained = {
                    r.called("handlers.onEventsDrained")
                    handlers.onEventsDrained()
                },
            ),
        ),
    )
}

/** [Wake] as its clause's [CallLog] sees it. */
fun Wake.recorded(log: CallLog): Wake = WakeProxy(this, log)

internal class WakeProxy(private val inner: Wake, log: CallLog) : Wake {
    private val r = log.recorder("Wake")

    override suspend fun schedule(id: WakeId, trigger: WakeTrigger) = r.answer("schedule", inner.schedule(id, trigger))
    override fun cancel(id: WakeId) = r.returns("cancel", inner.cancel(id))

    override fun listen(handlers: WakeHandlers) = r.returns(
        "listen",
        inner.listen(
            WakeHandlers(
                onWake = { id, completion ->
                    r.called("handlers.onWake", Recorder.arg(id), Recorder.arg(completion, "ExpiringCompletion"))
                    handlers.onWake(id, ExpiringCompletionProxy(completion, r.handle("ExpiringCompletion")))
                },
            ),
        ),
    )
}

/** [BackgroundTime] as its clause's [CallLog] sees it. */
fun BackgroundTime.recorded(log: CallLog): BackgroundTime = BackgroundTimeProxy(this, log)

internal class BackgroundTimeProxy(private val inner: BackgroundTime, log: CallLog) : BackgroundTime {
    private val r = log.recorder("BackgroundTime")

    override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
        val hold = r.returns(
            "begin",
            inner.begin(label) {
                r.called("begin.onExpiry")
                onExpiry()
            },
        )
        return HoldProxy(hold, r.handle("BackgroundTimeHold"))
    }

    private class HoldProxy(private val inner: BackgroundTimeHold, private val r: Recorder) : BackgroundTimeHold {
        override fun end() = r.returns("end", inner.end())
    }
}

/** [ExtensionRegistry] as its clause's [CallLog] sees it. */
fun ExtensionRegistry.recorded(log: CallLog): ExtensionRegistry = ExtensionRegistryProxy(this, log)

internal class ExtensionRegistryProxy(private val inner: ExtensionRegistry, log: CallLog) : ExtensionRegistry {
    private val r = log.recorder("ExtensionRegistry")

    override suspend fun setEnabled(enabled: Boolean) = r.answer("setEnabled", inner.setEnabled(enabled))
    override fun isEnabled() = r.answer("isEnabled", inner.isEnabled())
}
