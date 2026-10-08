package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.model.DateFormats
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.EntryContext
import app.snapsync.ports.LogSink
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.SystemUi
import co.touchlab.kermit.Severity
import kotlinx.coroutines.flow.onEach

/** [BuildInfo] as its clause's [CallLog] sees it: every fact read is an answer. */
fun BuildInfo.recorded(log: CallLog): BuildInfo = BuildInfoProxy(this, log)

internal class BuildInfoProxy(private val inner: BuildInfo, log: CallLog) : BuildInfo {
    private val r = log.recorder("BuildInfo")

    override val appVersion get() = r.returns("appVersion", inner.appVersion)
    override val uploadHost get() = r.returns("uploadHost", inner.uploadHost)
    override val store get() = r.returns("store", inner.store)
    override val apnsEnvironment get() = r.returns("apnsEnvironment", inner.apnsEnvironment)
    override val osSupportsOsDrivenUpload get() = r.answer("osSupportsOsDrivenUpload", inner.osSupportsOsDrivenUpload)
    override val diagnostics get() = r.returns("diagnostics", inner.diagnostics)
    override val dsn get() = r.returns("dsn", inner.dsn)
    override val platform get() = r.answer("platform", inner.platform)
    override val processId get() = r.returns("processId", inner.processId)
    override val bootLines get() = r.returns("bootLines", inner.bootLines)
}

/** [Clock] as its clause's [CallLog] sees it. */
fun Clock.recorded(log: CallLog): Clock = ClockProxy(this, log)

internal class ClockProxy(private val inner: Clock, log: CallLog) : Clock {
    private val r = log.recorder("Clock")

    override fun now() = r.returns("now", inner.now())
    override fun timeZone() = r.returns("timeZone", inner.timeZone())
}

/** [DeviceConditions] as its clause's [CallLog] sees it. */
fun DeviceConditions.recorded(log: CallLog): DeviceConditions = DeviceConditionsProxy(this, log)

internal class DeviceConditionsProxy(private val inner: DeviceConditions, log: CallLog) : DeviceConditions {
    private val r = log.recorder("DeviceConditions")

    override suspend fun read() = r.returns("read", inner.read())
}

/** [EntryContext] as its clause's [CallLog] sees it. */
fun EntryContext.recorded(log: CallLog): EntryContext = EntryContextProxy(this, log)

internal class EntryContextProxy(private val inner: EntryContext, log: CallLog) : EntryContext {
    private val r = log.recorder("EntryContext")

    override fun current() = r.returns("current", inner.current())
    override fun enter(name: String) = r.answer("enter", inner.enter(name))
    override fun exit(owned: Boolean) = r.returns("exit", inner.exit(owned))
}

/** [LogSink] as its clause's [CallLog] sees it. */
fun LogSink.recorded(log: CallLog): LogSink = LogSinkProxy(this, log)

internal class LogSinkProxy(private val inner: LogSink, log: CallLog) : LogSink {
    private val r = log.recorder("LogSink")

    override fun write(severity: Severity, tag: String, line: String) = r.returns(
        "write",
        inner.write(severity, tag, line),
    )
}

/** [NetworkMonitor] as its clause's [CallLog] sees it: every emission is an answer. */
fun NetworkMonitor.recorded(log: CallLog): NetworkMonitor = NetworkMonitorProxy(this, log)

internal class NetworkMonitorProxy(private val inner: NetworkMonitor, log: CallLog) : NetworkMonitor {
    private val r = log.recorder("NetworkMonitor")

    override fun watch() = inner.watch().onEach { r.answer("watch", it) }
}

/** [ProcessInfo] as its clause's [CallLog] sees it. */
fun ProcessInfo.recorded(log: CallLog): ProcessInfo = ProcessInfoProxy(this, log)

internal class ProcessInfoProxy(private val inner: ProcessInfo, log: CallLog) : ProcessInfo {
    private val r = log.recorder("ProcessInfo")

    override suspend fun protectedDataAvailable() = r.answer("protectedDataAvailable", inner.protectedDataAvailable())
    override fun memoryFootprint() = r.returns("memoryFootprint", inner.memoryFootprint())
}

/** [SystemUi] as its clause's [CallLog] sees it. */
fun SystemUi.recorded(log: CallLog): SystemUi = SystemUiProxy(this, log)

internal class SystemUiProxy(private val inner: SystemUi, log: CallLog) : SystemUi {
    private val r = log.recorder("SystemUi")

    override suspend fun share(text: String, title: String) = r.answer("share", inner.share(text, title))
    override suspend fun openUrl(url: String) = r.answer("openUrl", inner.openUrl(url))
    override fun openSettings() = r.returns("openSettings", inner.openSettings())
}

/** [DateFormatting] as its clause's [CallLog] sees it: each [DateFormats] it hands over is an answer. */
fun DateFormatting.recorded(log: CallLog): DateFormatting = DateFormattingProxy(this, log)

internal class DateFormattingProxy(private val inner: DateFormatting, log: CallLog) : DateFormatting {
    private val r = log.recorder("DateFormatting")

    override fun formats(languageTag: String?) = r.returns("formats", inner.formats(languageTag))
}
