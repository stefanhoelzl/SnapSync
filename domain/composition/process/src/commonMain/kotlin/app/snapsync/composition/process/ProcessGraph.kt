package app.snapsync.composition.process

import app.snapsync.application.process.AccountForProcess
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.logs.SinkLines
import app.snapsync.data.process.FootprintTrail
import app.snapsync.data.process.MetricPayloadReading
import app.snapsync.data.process.StartProcess
import app.snapsync.data.process.StartedProcess
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.entrypoints.OnMetricReport
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.ProcessLogging
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.Crypto
import app.snapsync.ports.Files
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ThreadLabel
import app.snapsync.scheduling.Runtime

/**
 * **What every process builds first** (`docs/architecture.md`, "The composition layer"): its one crash channel, its
 * log's writers, its own account of itself, and its start — over its [ProcessPorts], whatever it composes afterwards.
 * Wiring only; [startProcess] runs it.
 */
class ProcessGraph(ports: ProcessPorts, runtime: Runtime) {
    /** The process's one [Files]: every file-backed piece is built over it. */
    val files: Files = ports.files

    /** The process's one [Clock]. */
    val clock: Clock = ports.clock

    /** The process's one [Crypto]. */
    val crypto: Crypto = ports.crypto

    /** The process's one per-thread log label. */
    val threadLabel: ThreadLabel = ports.threadLabel

    /** What the running build is — see [ProcessPorts.build]. */
    val build: BuildInfo = ports.build

    /** The process's crash-reporting channel — one per process; started by [start]. */
    val crash: CrashChannel = CrashChannel(ports.crashReporter, ports.build, ports.threadLabel, CrashShaping.STANDARD)

    /** The app's own memory readings, which a later launch's metric report is told of. */
    val footprints: FootprintTrail = FootprintTrail(ports.files, ports.clock)

    /**
     * What a delivered process-metric report does. Exposed for ONE caller besides the port: the control channel, which
     * drives a synthetic report through this very instance, so the path the OS drives is the path a test drives.
     */
    val onMetricReport: OnMetricReport =
        OnMetricReport(runtime, MetricPayloadReading(), AccountForProcess(footprints, crash))

    /** The process's first act: its log's writers, its crash channel, its banner. */
    val start: StartProcess = StartProcess(
        ProcessLogging(listOfNotNull(SinkLines(ports.logSinks, ports.threadLabel), crash.lines), ports.logSinks.size),
        crash,
        ports.build,
    )
}

/**
 * **The process starts, then hears its metrics** — the first act of every composition (the app's and the upload
 * extension's), exactly once per process, and the one place that order is written. Listening is a commitment — a
 * provider may hand a held report over exactly once — so it waits until the channel the handler reports through is
 * live. The [StartedProcess] it returns is what every graph the process builds afterwards requires.
 */
@OptIn(CompositionOnly::class)
fun startProcess(ports: ProcessPorts, process: ProcessGraph): StartedProcess {
    val started = process.start.run()
    ports.processMetrics.inbound(MetricHandlers(onReport = process.onMetricReport))
    return started
}
