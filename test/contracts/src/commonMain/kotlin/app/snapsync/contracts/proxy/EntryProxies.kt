package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.UiState
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers

/** [CrashReporter] as its clause's [CallLog] sees it. */
fun CrashReporter.recorded(log: CallLog): CrashReporter = CrashReporterProxy(this, log)

internal class CrashReporterProxy(private val inner: CrashReporter, log: CallLog) : CrashReporter {
    private val r = log.recorder("CrashReporter")

    override fun start(options: CrashOptions) = r.returns("start", inner.start(options))
    override fun capture(event: CrashEvent) = r.returns("capture", inner.capture(event))
    override fun breadcrumb(crumb: Crumb) = r.returns("breadcrumb", inner.breadcrumb(crumb))
    override fun setContext(name: String, fields: Map<String, String>) =
        r.returns("setContext", inner.setContext(name, fields))
    override suspend fun sendDump(dump: CrashEvent) = r.answer("sendDump", inner.sendDump(dump))

    override fun listen(handlers: CrashHandlers) = r.returns(
        "listen",
        inner.listen(
            CrashHandlers(
                onEvent = { event ->
                    r.called("handlers.onEvent", Recorder.arg(event, "CrashEvent"))
                    handlers.onEvent(event)
                },
                onBreadcrumb = { crumb ->
                    r.called("handlers.onBreadcrumb", Recorder.arg(crumb, "Crumb"))
                    handlers.onBreadcrumb(crumb)
                },
            ),
        ),
    )
}

/** [DevControls] as its clause's [CallLog] sees it. */
fun DevControls.recorded(log: CallLog): DevControls = DevControlsProxy(this, log)

internal class DevControlsProxy(private val inner: DevControls, log: CallLog) : DevControls {
    private val r = log.recorder("DevControls")

    override fun uploaderPin() = r.returns("uploaderPin", inner.uploaderPin())
    override fun inviteLinkHints() = r.answer("inviteLinkHints", inner.inviteLinkHints())
    override fun createsPlainEvents() = r.answer("createsPlainEvents", inner.createsPlainEvents())

    override fun listen(handlers: DevHandlers) = r.returns(
        "listen",
        inner.listen(
            DevHandlers(
                onReset = {
                    r.called("handlers.onReset")
                    handlers.onReset()
                },
            ),
        ),
    )
}

/** [ExtensionHost] as its clause's [CallLog] sees it. */
fun ExtensionHost.recorded(log: CallLog): ExtensionHost = ExtensionHostProxy(this, log)

internal class ExtensionHostProxy(private val inner: ExtensionHost, log: CallLog) : ExtensionHost {
    private val r = log.recorder("ExtensionHost")

    override fun listen(handlers: ExtensionHandlers) = r.returns(
        "listen",
        inner.listen(
            ExtensionHandlers(
                onProcess = {
                    r.called("handlers.onProcess")
                    handlers.onProcess()
                },
                onTerminate = {
                    r.called("handlers.onTerminate")
                    handlers.onTerminate()
                },
            ),
        ),
    )
}

/** [Lifecycle] as its clause's [CallLog] sees it. */
fun Lifecycle.recorded(log: CallLog): Lifecycle = LifecycleProxy(this, log)

internal class LifecycleProxy(private val inner: Lifecycle, log: CallLog) : Lifecycle {
    private val r = log.recorder("Lifecycle")

    override fun listen(handlers: LifecycleHandlers) = r.returns(
        "listen",
        inner.listen(
            LifecycleHandlers(
                onForeground = {
                    r.called("handlers.onForeground")
                    handlers.onForeground()
                },
                onBackground = {
                    r.called("handlers.onBackground")
                    handlers.onBackground()
                },
            ),
        ),
    )
}

/** [Links] as its clause's [CallLog] sees it. */
fun Links.recorded(log: CallLog): Links = LinksProxy(this, log)

internal class LinksProxy(private val inner: Links, log: CallLog) : Links {
    private val r = log.recorder("Links")

    override fun listen(handlers: LinkHandlers) = r.returns(
        "listen",
        inner.listen(
            LinkHandlers(
                onLink = { delivery ->
                    r.called("handlers.onLink", Recorder.arg(delivery, "LinkDelivery"))
                    handlers.onLink(delivery)
                },
            ),
        ),
    )
}

/** [ProcessMetrics] as its clause's [CallLog] sees it. */
fun ProcessMetrics.recorded(log: CallLog): ProcessMetrics = ProcessMetricsProxy(this, log)

internal class ProcessMetricsProxy(private val inner: ProcessMetrics, log: CallLog) : ProcessMetrics {
    private val r = log.recorder("ProcessMetrics")

    override fun listen(handlers: MetricHandlers) = r.returns(
        "listen",
        inner.listen(
            MetricHandlers(
                onReport = { report ->
                    r.called("handlers.onReport", Recorder.arg(report, "ProcessMetricReport"))
                    handlers.onReport(report)
                },
            ),
        ),
    )
}

/** [PushNotifications] as its clause's [CallLog] sees it. */
fun PushNotifications.recorded(log: CallLog): PushNotifications = PushNotificationsProxy(this, log)

internal class PushNotificationsProxy(private val inner: PushNotifications, log: CallLog) : PushNotifications {
    private val r = log.recorder("PushNotifications")

    override val kind get() = r.returns("kind", inner.kind)
    override fun register() = r.returns("register", inner.register())

    override fun listen(handlers: PushHandlers) = r.returns(
        "listen",
        inner.listen(
            PushHandlers(
                onToken = { token ->
                    r.called("handlers.onToken", Recorder.arg(token, "PushToken"))
                    handlers.onToken(token)
                },
                onTokenFailure = { error ->
                    r.called("handlers.onTokenFailure", Recorder.arg(error, "PlatformError"))
                    handlers.onTokenFailure(error)
                },
                onMessage = { message, completion ->
                    r.called(
                        "handlers.onMessage",
                        Recorder.arg(message, "PushMessage"),
                        Recorder.arg(completion, "Completion"),
                    )
                    handlers.onMessage(message, CompletionProxy(completion, r.handle("Completion")))
                },
            ),
        ),
    )
}

/** [Ui] as its clause's [CallLog] sees it. */
fun Ui.recorded(log: CallLog): Ui = UiProxy(this, log)

internal class UiProxy(private val inner: Ui, log: CallLog) : Ui {
    private val r = log.recorder("Ui")

    override fun show(state: UiState) = r.returns("show", inner.show(state))

    override fun listen(handlers: UiHandlers) = r.returns(
        "listen",
        inner.listen(
            UiHandlers(
                onIntent = { intent ->
                    r.called("handlers.onIntent", Recorder.arg(intent))
                    handlers.onIntent(intent)
                },
                onLive = {
                    r.called("handlers.onLive")
                    handlers.onLive()
                },
            ),
        ),
    )
}
