package app.snapsync.journeys.device

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** What a command answered: its exit code and what it printed. */
data class Answer(val exit: Int, val out: String, val err: String) {
    val ok: Boolean get() = exit == 0
}

/**
 * Runs a command line — the one way the device drivers reach `adb`, `xcrun simctl` and the rest: an argument list,
 * never a shell string, each call bounded. Tests replace it with a recording fake, so a driver's exact command lines
 * and the parsing of their output are tested on any machine.
 */
interface Shell {
    /**
     * Runs [command] to its end or [within], with [env] added to this process's environment, answering what it printed;
     * a command still running then is killed.
     */
    fun run(command: List<String>, within: Duration, env: Map<String, String> = emptyMap()): Answer

    /** [run], its standard output written to [into] as it is — bytes, not text — rather than answered. */
    fun runInto(command: List<String>, into: File, within: Duration): Answer

    /** [run], failing — naming the command and what it printed — unless it exited 0. */
    fun ok(command: List<String>, within: Duration, env: Map<String, String> = emptyMap()): String {
        val answer = run(command, within, env)
        if (!answer.ok) throw CommandFailed(command, answer)
        return answer.out
    }
}

/** A command that did not exit 0, as it was given and with what it printed. */
class CommandFailed(command: List<String>, answer: Answer) :
    Exception(
        "`${command.joinToString(" ")}` exited ${answer.exit}: ${(answer.err + answer.out).trim().take(MAX_SHOWN)}",
    )

/** The machine's own processes. */
object ProcessShell : Shell {
    override fun run(command: List<String>, within: Duration, env: Map<String, String>): Answer {
        val out = File.createTempFile("journeys", ".out").apply { deleteOnExit() }
        return start(command, out, within, env).let { it.copy(out = out.readText()) }.also { out.delete() }
    }

    override fun runInto(command: List<String>, into: File, within: Duration): Answer =
        start(command, into, within, emptyMap())

    private fun start(command: List<String>, into: File, within: Duration, env: Map<String, String>): Answer {
        val err = File.createTempFile("journeys", ".err").apply { deleteOnExit() }
        val builder = ProcessBuilder(command).redirectOutput(into).redirectError(err)
        builder.environment().putAll(env)
        val process = builder.start()
        val ended = process.waitFor(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!ended) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
        }
        val exit = if (ended) process.exitValue() else TIMED_OUT
        return Answer(exit, "", err.readText() + if (ended) "" else "\n(killed: still running after $within)")
            .also { err.delete() }
    }

    /** The exit a command killed at its bound answers: 124, as `timeout(1)`'s. */
    const val TIMED_OUT = 124
}

private const val MAX_SHOWN = 2000
