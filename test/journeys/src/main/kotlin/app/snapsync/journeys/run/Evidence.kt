package app.snapsync.journeys.run

import app.snapsync.journeys.device.Shell
import java.io.File
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a run learns, in [dir], which CI keeps as an artifact: the stage timestamps (`stages`), the outcome
 * (`result`), and whatever a step writes there itself — the backend's and the app's logs, the advertisement, a
 * screenshot on a failure.
 */
class Evidence(val dir: File) {
    init {
        dir.mkdirs()
    }

    /** A timestamped marker per stage, so the job's wall clock can be attributed. */
    fun stage(what: String) {
        val line = "[${LocalTime.now(ZoneOffset.UTC).format(CLOCK)}] $what"
        println(line)
        File(dir, "stages").appendText("$line\n")
    }

    /** The run's outcome, in its own words. */
    fun result(line: String) {
        println(line)
        File(dir, "result").appendText("$line\n")
    }

    fun file(name: String): File = File(dir, name)

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * Load and memory every 10 s for the whole run, into `resources.log` — a stall is then attributable to CPU or memory
 * from the evidence — and this program's own JVM beside them (heap, resident size), which the journeys run in.
 */
class ResourceSampler(private val evidence: Evidence, private val shell: Shell) : AutoCloseable {
    private val log = evidence.file("resources.log")
    private val macOs = System.getProperty("os.name").startsWith("Mac")

    @Volatile private var running = true
    private val sampler = thread(isDaemon = true, name = "resources") {
        while (running) {
            runCatching { log.appendText(sample()) }
            runCatching { Thread.sleep(EVERY.inWholeMilliseconds) }
        }
    }

    private fun sample(): String = buildString {
        appendLine("=== ${LocalTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm:ss"))}")
        if (macOs) {
            appendLine("load ${out("sysctl", "-n", "vm.loadavg")}")
            appendLine(out("memory_pressure", "-Q").lines().lastOrNull { it.isNotBlank() }.orEmpty())
            appendLine("swap ${out("sysctl", "-n", "vm.swapusage")}")
            appendLine(
                out("vm_stat").lines().filter { it.contains(Regex("Pages free|Pageouts|Swapouts")) }.joinToString("\n"),
            )
            // Who the load is: a booting simulator's is a long tail of its own daemons, not one process (run 37004211450).
            appendLine(out("ps", "-A", "-r", "-o", "%cpu=,pid=,comm=").lines().take(TOP).joinToString("\n"))
        } else {
            appendLine("load ${File("/proc/loadavg").readText().trim()}")
            appendLine(
                File("/proc/meminfo").readLines().filter { it.substringBefore(':') in MEMINFO }.joinToString("; "),
            )
            appendLine(out("ps", "-eo", "pcpu=,pid=,comm=", "--sort=-pcpu").lines().take(TOP).joinToString("\n"))
        }
        val jvm = Runtime.getRuntime()
        val rss = out("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).trim()
        appendLine(
            "journeys JVM: heap ${(jvm.totalMemory() - jvm.freeMemory()) / MB} of ${jvm.totalMemory() / MB} MB, rss ${rss}k",
        )
    }

    private fun out(vararg command: String): String = shell.run(command.toList(), 5.seconds).out.trim()

    override fun close() {
        running = false
        sampler.interrupt()
    }

    private companion object {
        val EVERY = 10.seconds
        const val TOP = 12
        const val MB = 1024 * 1024
        val MEMINFO = setOf("MemAvailable", "SwapTotal", "SwapFree", "Committed_AS")
    }
}
