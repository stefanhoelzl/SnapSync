package app.snapsync.data.process

import app.snapsync.data.crash.CrashChannel
import app.snapsync.domain.report.ProcessName
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.ProcessLogging
import app.snapsync.inbound.of
import app.snapsync.ports.BuildInfo

/**
 * The proof that this process's crash reporting is live: only [StartProcess.run] mints one, and every composition a
 * process builds afterwards requires it — so none can be built in a process whose crash reporting has not started.
 */
class StartedProcess internal constructor(
    /** The process's crash channel — started. */
    val crash: CrashChannel,
)

/**
 * **The first act of every process**, before it composes anything else and exactly once (`docs/architecture.md`, "The
 * composition layer"). In order, each for a reason:
 * 1. The log's writers, where this process owns the logging globals: **a process that supplies log sinks owns them** —
 *    `Log`'s writers — so nothing below logs into the void. A process that supplies none leaves them alone: a JVM
 *    hosts many "processes" in one VM, where they are VM-global, and a composition there must not take them over
 *    ([ProcessLogging]).
 * 2. Crash reporting starts, as the process's first act that can fail: it must be live before any other wiring can
 *    fail, and it reads only this build's own configuration, so it is safe on a locked background launch.
 * 3. The boot banner — the process and its build, then the backend it talks to — so it is the process's first line
 *    and, the channel running, the first breadcrumb of anything it reports.
 *
 * The process metrics are listened to after this, by the composition: listening is a commitment — a provider may hand
 * a held report over exactly once — so it waits for the started channel this returns.
 */
class StartProcess(
    private val logging: ProcessLogging,
    private val crash: CrashChannel,
    private val build: BuildInfo,
) {
    @CompositionOnly
    fun run(): StartedProcess {
        logging.install()
        crash.start()
        banner()
        return StartedProcess(crash)
    }

    /**
     * The process's first lines: the process and its build, so a reader who concatenates the app's and the extension's
     * logs can tell runs apart — a process with no id of its own (a test executable) by its platform; then the backend
     * this build talks to, the one fact that makes an otherwise-silent failure legible (a build pointed at another
     * backend without a device reset uploads nothing, its ledger saying everything is done).
     */
    private fun banner() {
        val boot = Log("process").unlabelled
        boot.i(
            "=== process start ===",
            "process" of ProcessName.of(build.processId, build.platform.tag),
            "build" of "${build.appVersion}(${build.buildNumber})",
        )
        boot.i("upload base", "host" of build.uploadHost)
    }
}
