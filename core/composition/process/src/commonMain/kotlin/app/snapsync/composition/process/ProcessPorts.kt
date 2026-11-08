package app.snapsync.composition.process

import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.Files
import app.snapsync.ports.LogSink
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.ThreadLabel

/**
 * The ports every process has exactly one of, whatever it composes afterwards (`docs/architecture.md`, "The
 * composition layer"): each fronts a process-global OS source, so a second adapter would be a second view of the
 * same global — the four crash reporters this replaced were exactly that.
 */
class ProcessPorts(
    /** The crash-reporting channel — one per process. */
    val crashReporter: CrashReporter,
    /** The OS's account of this process; the inert `NoProcessMetrics` where there is no provider. */
    val processMetrics: ProcessMetrics,
    /**
     * Where this process's log lines are written — the device-log file and the platform's log on iOS.
     *
     * **A process that supplies sinks owns the logging globals**, and its process start (`StartProcess`) installs its
     * writers there. A process that supplies none leaves them alone: a JVM hosts many "processes" in one VM, where they
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
