@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState

/** [CrashReporter] as its clause's [CallLog] sees it. */
fun CrashReporter.recorded(log: CallLog): CrashReporter = CrashReporterProxy(log.wraps(this), log)

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
fun DevControls.recorded(log: CallLog): DevControls = DevControlsProxy(log.wraps(this), log)

internal class DevControlsProxy(private val inner: DevControls, log: CallLog) : DevControls {
    private val r = log.recorder("DevControls")

    override fun uploaderPin() = r.returns("uploaderPin", inner.uploaderPin())
    override fun inviteLinkHints() = r.answer("inviteLinkHints", inner.inviteLinkHints())
    override fun createsPlainEvents() = r.answer("createsPlainEvents", inner.createsPlainEvents())

    @CompositionOnly
    override fun inbound(handlers: DevHandlers) = r.returns(
        "inbound",
        inner.inbound(DevHandlers(BackgroundProxy(handlers.onReset, r, "onReset"))),
    )
}

/** [ExtensionHost] as its clause's [CallLog] sees it. */
fun ExtensionHost.recorded(log: CallLog): ExtensionHost = ExtensionHostProxy(log.wraps(this), log)

internal class ExtensionHostProxy(private val inner: ExtensionHost, log: CallLog) : ExtensionHost {
    private val r = log.recorder("ExtensionHost")

    @CompositionOnly
    override fun inbound(handlers: ExtensionHandlers) = r.returns(
        "inbound",
        inner.inbound(
            ExtensionHandlers(
                AnsweredProxy(handlers.onProcess, r, "onProcess"),
                InboundProxy(handlers.onTerminate, r, "onTerminate"),
            ),
        ),
    )
}

/** [Lifecycle] as its clause's [CallLog] sees it. */
fun Lifecycle.recorded(log: CallLog): Lifecycle = LifecycleProxy(log.wraps(this), log)

internal class LifecycleProxy(private val inner: Lifecycle, log: CallLog) : Lifecycle {
    private val r = log.recorder("Lifecycle")

    @CompositionOnly
    override fun inbound(handlers: LifecycleHandlers) = r.returns(
        "inbound",
        inner.inbound(
            LifecycleHandlers(
                BackgroundProxy(handlers.onForeground, r, "onForeground"),
                InboundProxy(handlers.onBackground, r, "onBackground"),
            ),
        ),
    )
}

/** [Links] as its clause's [CallLog] sees it. */
fun Links.recorded(log: CallLog): Links = LinksProxy(log.wraps(this), log)

internal class LinksProxy(private val inner: Links, log: CallLog) : Links {
    private val r = log.recorder("Links")

    @CompositionOnly
    override fun inbound(handlers: LinkHandlers) = r.returns(
        "inbound",
        inner.inbound(LinkHandlers(InboundProxy(handlers.onLink, r, "onLink"))),
    )
}

/** [ProcessMetrics] as its clause's [CallLog] sees it. */
fun ProcessMetrics.recorded(log: CallLog): ProcessMetrics = ProcessMetricsProxy(log.wraps(this), log)

internal class ProcessMetricsProxy(private val inner: ProcessMetrics, log: CallLog) : ProcessMetrics {
    private val r = log.recorder("ProcessMetrics")

    @CompositionOnly
    override fun inbound(handlers: MetricHandlers) = r.returns(
        "inbound",
        inner.inbound(MetricHandlers(InboundProxy(handlers.onReport, r, "onReport", "ProcessMetricPayload"))),
    )
}

/** [PushNotifications] as its clause's [CallLog] sees it. */
fun PushNotifications.recorded(log: CallLog): PushNotifications = PushNotificationsProxy(log.wraps(this), log)

internal class PushNotificationsProxy(private val inner: PushNotifications, log: CallLog) : PushNotifications {
    private val r = log.recorder("PushNotifications")

    override val kind get() = r.returns("kind", inner.kind)
    override fun register() = r.returns("register", inner.register())

    @CompositionOnly
    override fun inbound(handlers: PushHandlers) = r.returns(
        "inbound",
        inner.inbound(
            PushHandlers(
                InboundProxy(handlers.onToken, r, "onToken", "PushToken"),
                InboundProxy(handlers.onTokenFailure, r, "onTokenFailure", "PlatformError"),
                BackgroundProxy(handlers.onMessage, r, "onMessage", "PushMessage"),
            ),
        ),
    )
}

/** [Ui] as its clause's [CallLog] sees it. */
fun Ui<UiState, UiIntent>.recorded(log: CallLog): Ui<UiState, UiIntent> = UiProxy(log.wraps(this), log)

internal class UiProxy(private val inner: Ui<UiState, UiIntent>, log: CallLog) : Ui<UiState, UiIntent> {
    private val r = log.recorder("Ui")

    override fun show(state: UiState) = r.returns("show", inner.show(state))

    @CompositionOnly
    override fun inbound(handlers: UiHandlers<UiState, UiIntent>) = r.returns(
        "inbound",
        inner.inbound(
            UiHandlers(InboundProxy(handlers.onIntent, r, "onIntent"), InboundProxy(handlers.onLive, r, "onLive")),
        ),
    )
}
