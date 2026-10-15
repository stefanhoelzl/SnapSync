package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.DateFormats
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.LogSink
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.SystemUi
import app.snapsync.ports.ThreadLabel
import co.touchlab.kermit.Severity
import kotlinx.coroutines.flow.onEach

/** [BuildInfo] as its clause's [CallLog] sees it: every fact read is an answer. */
fun BuildInfo.recorded(log: CallLog): BuildInfo = BuildInfoProxy(log.wraps(this), log)

internal class BuildInfoProxy(private val inner: BuildInfo, log: CallLog) : BuildInfo {
    private val r = log.recorder("BuildInfo")

    override val appVersion get() = r.returns("appVersion", inner.appVersion)
    override val uploadHost get() = r.returns("uploadHost", inner.uploadHost)
    override val store get() = r.returns("store", inner.store)
    override val buildNumber get() = r.returns("buildNumber", inner.buildNumber)
    override val pushEnvironment get() = r.returns("pushEnvironment", inner.pushEnvironment)
    override val osVersion get() = r.returns("osVersion", inner.osVersion)
    override val deviceModel get() = r.returns("deviceModel", inner.deviceModel)
    override val reporterEnvironment get() = r.returns("reporterEnvironment", inner.reporterEnvironment)
    override val dsn get() = r.returns("dsn", inner.dsn)
    override val platform get() = r.answer("platform", inner.platform)
    override val processId get() = r.returns("processId", inner.processId)
}

/** [Clock] as its clause's [CallLog] sees it. */
fun Clock.recorded(log: CallLog): Clock = ClockProxy(log.wraps(this), log)

internal class ClockProxy(private val inner: Clock, log: CallLog) : Clock {
    private val r = log.recorder("Clock")

    override fun now() = r.returns("now", inner.now())
    override fun timeZone() = r.returns("timeZone", inner.timeZone())
}

/** [DeviceConditions] as its clause's [CallLog] sees it. */
fun DeviceConditions.recorded(log: CallLog): DeviceConditions = DeviceConditionsProxy(log.wraps(this), log)

internal class DeviceConditionsProxy(private val inner: DeviceConditions, log: CallLog) : DeviceConditions {
    private val r = log.recorder("DeviceConditions")

    override suspend fun read() = r.returns("read", inner.read())
}

/** [ThreadLabel] as its clause's [CallLog] sees it. */
fun ThreadLabel.recorded(log: CallLog): ThreadLabel = ThreadLabelProxy(log.wraps(this), log)

internal class ThreadLabelProxy(private val inner: ThreadLabel, log: CallLog) : ThreadLabel {
    private val r = log.recorder("ThreadLabel")

    override fun swap(label: String?) = r.returns("swap", inner.swap(label))
    override fun current() = r.returns("current", inner.current())
}

/** [LogSink] as its clause's [CallLog] sees it. */
fun LogSink.recorded(log: CallLog): LogSink = LogSinkProxy(log.wraps(this), log)

internal class LogSinkProxy(private val inner: LogSink, log: CallLog) : LogSink {
    private val r = log.recorder("LogSink")

    override fun write(severity: Severity, tag: String, line: String) = r.returns(
        "write",
        inner.write(severity, tag, line),
    )
}

/** [NetworkMonitor] as its clause's [CallLog] sees it: every emission is an answer. */
fun NetworkMonitor.recorded(log: CallLog): NetworkMonitor = NetworkMonitorProxy(log.wraps(this), log)

internal class NetworkMonitorProxy(private val inner: NetworkMonitor, log: CallLog) : NetworkMonitor {
    private val r = log.recorder("NetworkMonitor")

    override fun watch() = inner.watch().onEach { r.answer("watch", it) }
}

/** [ProcessInfo] as its clause's [CallLog] sees it. */
fun ProcessInfo.recorded(log: CallLog): ProcessInfo = ProcessInfoProxy(log.wraps(this), log)

internal class ProcessInfoProxy(private val inner: ProcessInfo, log: CallLog) : ProcessInfo {
    private val r = log.recorder("ProcessInfo")

    override fun memoryFootprint() = r.returns("memoryFootprint", inner.memoryFootprint())
}

/** [SystemUi] as its clause's [CallLog] sees it. */
fun SystemUi.recorded(log: CallLog): SystemUi = SystemUiProxy(log.wraps(this), log)

internal class SystemUiProxy(private val inner: SystemUi, log: CallLog) : SystemUi {
    private val r = log.recorder("SystemUi")

    override suspend fun share(text: String, title: String) = r.answer("share", inner.share(text, title))
    override suspend fun openUrl(url: String) = r.answer("openUrl", inner.openUrl(url))
    override fun openSettings() = r.returns("openSettings", inner.openSettings())
}

/** [DateFormatting] as its clause's [CallLog] sees it: each [DateFormats] it hands over is an answer. */
fun DateFormatting.recorded(log: CallLog): DateFormatting = DateFormattingProxy(log.wraps(this), log)

internal class DateFormattingProxy(private val inner: DateFormatting, log: CallLog) : DateFormatting {
    private val r = log.recorder("DateFormatting")

    override fun deviceLocale() = r.returns("deviceLocale", inner.deviceLocale())
    override fun formats(locale: app.snapsync.model.DateLocale) = r.returns("formats", inner.formats(locale))
}
