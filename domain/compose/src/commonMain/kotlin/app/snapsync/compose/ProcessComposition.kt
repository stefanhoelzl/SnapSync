package app.snapsync.compose

import app.snapsync.application.process.AccountForProcess
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.logs.SinkLines
import app.snapsync.data.process.FootprintTrail
import app.snapsync.data.process.MetricPayloadReading
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ProcessName
import app.snapsync.domain.report.ReportDestination
import app.snapsync.entrypoints.OnMetricReport
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.Log
import app.snapsync.inbound.ProcessLogging
import app.snapsync.inbound.of
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.Files
import app.snapsync.ports.LogSink
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.ThreadLabel
import app.snapsync.scheduling.Runtime

/**
 * The ports every process has exactly one of, whatever it composes afterwards (`docs/architecture.md`, "One
 * shared composition"): each fronts a process-global OS source, so a second adapter would be a second view of the
 * same global — the four crash reporters this replaced were exactly that.
 */
class ProcessPorts(
    /** The crash-reporting channel — one per process. */
    val crashReporter: CrashReporter,
    /** The OS's account of this process; [NoProcessMetrics] where there is no provider. */
    val processMetrics: ProcessMetrics,
    /**
     * Where this process's log lines are written — the device-log file and the platform's log on iOS.
     *
     * **A process that supplies sinks owns the logging globals**, and [snapSyncProcess] installs its writers
     * there. A process that supplies none leaves them alone: a JVM hosts many "processes" in one VM, where they
     * are VM-global, and a composition there must not take them over.
     */
    val logSinks: List<LogSink>,
    /** This process's files, by area. One instance: every file-backed service is built over it. */
    val files: Files,
    /** Wall-clock time and the device's zone. One instance: the core and the screen's formatter read it. */
    val clock: Clock,
    /** The platform's cryptographic primitives — an encrypted event's files are sealed and opened over them. */
    val crypto: Crypto,
    /** The label each thread's log lines carry — the run it is working for; the crash channel's `entry_point` tag. */
    val threadLabel: ThreadLabel,
    /**
     * Where the runtime tells each entry point's work — delivered, answered, done — beside its log line: nowhere on a
     * shipped build, the control channel's event stream on a rig build and the JVM.
     */
    val entryEvents: EntryEvents,
    /**
     * What the running build is: where it reports to ([BuildInfo.dsn]), what the boot banner names, and the constants
     * every composition over this process reads.
     */
    val build: BuildInfo,
)

/**
 * What [snapSyncProcess] set up, handed to every composition the process builds afterwards — and required by each,
 * so no composition can be built in a process whose crash reporting has not started.
 */
class ProcessServices internal constructor(
    /** The process's crash-reporting channel — started. */
    val crash: CrashChannel,
    /**
     * What a delivered process-metric report does, and the entry point it is delivered through. Exposed for ONE caller
     * besides the port: the control channel, which drives a synthetic report through this very instance, so the path
     * the OS drives is the path a test drives.
     */
    val processMetrics: Inbound<ProcessMetricPayload, Unit>,
    /**
     * The app's own memory readings, which [processMetrics] attaches to the next report. Only the app records them —
     * it is the process the platform's reports describe.
     */
    val footprints: FootprintTrail,
    /** The process's one [Files]. */
    val files: Files,
    /** The process's one [Clock]. */
    val clock: Clock,
    /** The process's one [Crypto]. */
    val crypto: Crypto,
    /** The process's one per-thread log label. */
    val threadLabel: ThreadLabel,
    /** What the running build is — see [ProcessPorts.build]. */
    val build: BuildInfo,
) {
    /** Where a bug report goes on this build — [CrashChannel.destination]. */
    val reportDestination: ReportDestination get() = crash.destination

    /** Which build this is, as the app menu shows it. */

    val buildLabel: BuildLabel
        get() = BuildLabel(version = build.appVersion, buildNumber = build.buildNumber)
}

/**
 * Set up what is per-process — **the first act of every composition** (`snapSyncHost`, `snapSyncExtension`), before it
 * composes anything else, and exactly once per process. No root calls it: a root hands its [ProcessPorts] to its
 * composition, which cannot then be built in a process whose crash reporting has not started.
 *
 * In order, each for a reason:
 * 1. The log's writers, where this process owns the logging globals (`docs/architecture.md`, "One shared
 *    composition"): **a process that supplies log sinks owns them** — [Log]'s writers and Kermit's, whose one writer is
 *    the [KermitBridge] onto [Log]'s — so nothing below logs into the void. A process that supplies none leaves them
 *    alone: a JVM hosts many "processes" in one VM, where both are VM-global, and a composition there must not take
 *    them over.
 * 2. Crash reporting starts, as the process's first act that can fail: it must be live before any other wiring can
 *    fail, and it reads only this build's own configuration, so it is safe on a locked background launch.
 * 3. The boot banner — the process and its build, then the backend it talks to — so it is the process's first line
 *    and, the channel running, the first breadcrumb of anything it reports.
 * 4. The process metrics are listened to — only now, because listening is a commitment: a provider may hand a held
 *    report over exactly once, so the handler must already be live (it is: `AccountForProcess` over the started
 *    channel, whose log writer is already installed).
 */
@OptIn(CompositionOnly::class)
fun snapSyncProcess(ports: ProcessPorts, runtime: Runtime): ProcessServices {
    val crash = CrashChannel(ports.crashReporter, ports.build, ports.threadLabel, CrashShaping.STANDARD)
    ProcessLogging(
        listOfNotNull(SinkLines(ports.logSinks, ports.threadLabel), crash.lines),
        ports.logSinks.size,
    ).install()
    crash.start()
    bootBanner(ports.build)
    val footprints = FootprintTrail(ports.files, ports.clock)
    val metrics = OnMetricReport(runtime, MetricPayloadReading(), AccountForProcess(footprints, crash))
    val services = ProcessServices(
        crash,
        metrics,
        footprints,
        ports.files,
        ports.clock,
        ports.crypto,
        ports.threadLabel,
        ports.build,
    )
    ports.processMetrics.inbound(MetricHandlers(onReport = metrics))
    return services
}

/**
 * The process's first lines: the process and its build, so a reader who concatenates the app's and the extension's
 * logs can tell runs apart — a process with no id of its own (a test executable) by its platform; then the backend this
 * build talks to, the one fact that makes an otherwise-silent failure legible (a build pointed at another backend
 * without a device reset uploads nothing, its ledger saying everything is done).
 */
private fun bootBanner(build: BuildInfo) {
    val boot = Log("process").unlabelled
    boot.i(
        "=== process start ===",
        "process" of ProcessName.of(build.processId, build.platform.tag),
        "build" of build.label(),
    )
    boot.i("upload base", "host" of build.uploadHost)
}

private fun BuildInfo.label(): String = "$appVersion($buildNumber)"
