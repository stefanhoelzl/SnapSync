package app.snapsync.cli

import app.snapsync.client.RpcConnection
import app.snapsync.levers.LeverRefused
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.RpcCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// `rig`: a command line made into a typed service's call, sent through kRPC's client as a test's call is — the same
// route (`ws://…/rpc`), the same serialization — and its answer printed. A refusal (`LeverRefused`, which kRPC rebuilds
// by name) prints the host's reason; any other failure the message it arrived with, which from an iOS host is kRPC's
// stand-in exception's (`docs/testing.md`, "Typed services").

fun main(args: Array<String>) {
    // Ktor's client logs through SLF4J, which has no provider here and says so on every call.
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    // Into a pipe, a stream probes it for a reader that has gone (`Stream.kt`); onto a terminal, a person reads it.
    val probe = System.console()?.isTerminal != true
    exitProcess(rig(args.toList(), System.out, System.err, probe))
}

/**
 * Runs one command line, printing to [out] and [err]; answers the exit code. [probe]: a stream writes a bare newline to
 * [out] every second while it waits, so a reader that has gone is noticed within one (`Stream.kt`).
 */
internal fun rig(args: List<String>, out: PrintStream, err: PrintStream, probe: Boolean = false): Int = try {
    val options = Options.parse(args)
    when {
        options.help -> out.print(help(options.command)).let { EXIT_OK }
        options.command.firstOrNull() == "run" -> options.connected {
            batch(
                options.command.drop(1),
                options.json,
                out,
                err,
            )
        }
        else -> options.connected { call(options.command, options.json, out, err, probe) }
    }
} catch (e: UsageError) {
    err.println("rig: ${e.message}")
    EXIT_USAGE
}

/** The global flags, and the command they precede or follow. */
private class Options(
    val url: String?,
    val json: Boolean,
    val help: Boolean,
    val command: List<String>,
    /** `--timeout`'s bound, `null` for none — or [Bound.Default] where it was not given. */
    val timeout: Bound,
) {
    fun connected(block: suspend Connection.() -> Int): Int {
        val base = url ?: usage("--url <base> is required, e.g. --url http://127.0.0.1:18101 (see rig --help)")
        val call = if (timeout is Bound.Given) timeout.bound else CALL_TIMEOUT
        val stream = if (timeout is Bound.Given) timeout.bound else STREAM_TIMEOUT
        return Connection(base, call, stream).use { runBlocking { it.block() } }
    }

    companion object {
        fun parse(args: List<String>): Options {
            var url: String? = null
            var json = false
            var help = false
            var timeout: Bound = Bound.Default
            val command = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--url" -> url = args.getOrNull(++i) ?: usage("--url needs a value")
                    "--json" -> json = true
                    "--timeout" -> timeout = Bound.Given(seconds(args.getOrNull(++i) ?: usage("--timeout needs a value")))
                    "--help", "-h" -> help = true
                    "help" -> if (command.isEmpty()) help = true else command += arg
                    else -> if (arg.startsWith("--url=")) url = arg.removePrefix("--url=") else command += arg
                }
                i++
            }
            return Options(url, json, help, command, timeout)
        }
    }
}

private fun help(command: List<String>): String {
    val service = command.getOrNull(0) ?: return topHelp()
    val commands = serviceCommands(service) ?: unknownService(service)
    val method = command.getOrNull(1) ?: return commands.help()
    return (commands.method(method) ?: unknownMethod(commands, method)).help()
}

/** How long a command may take: as `--timeout` gave it (`null`: as long as it takes), or each kind's default. */
private sealed interface Bound {
    data object Default : Bound

    data class Given(val bound: Duration?) : Bound
}

/** `--timeout`'s seconds as the bound of one call or one stream; `0` lifts it. */
internal fun seconds(text: String): Duration? {
    val n = text.toLongOrNull()?.takeIf { it >= 0 } ?: usage("--timeout: '$text' is no number of seconds (0: none)")
    return if (n == 0L) null else n.seconds
}

/**
 * The host's kRPC route, every call of a run over one connection — replaced when it drops ([RpcConnection] says which
 * calls are sent again, and which never are). [timeout] bounds a call, [streamTimeout] a stream; `null`: as long as it
 * takes.
 */
internal class Connection(
    base: String,
    private val timeout: Duration?,
    val streamTimeout: Duration?,
) : AutoCloseable {
    private val rpc = RpcConnection(base)
    val url = rpc.url
    private var calls = 0L

    suspend fun send(method: MethodCommand, arguments: Array<Any?>): Any? {
        val call = suspend { rpc.sending { client -> client.call<Any?>(rpcCall(method, arguments)) } }
        return if (timeout == null) call() else withTimeout(timeout) { call() }
    }

    /**
     * Follows the stream [method] serves, opened again on every dropped connection ([dropped] hearing why), handing each
     * element to [each] until it answers `true`; unbounded — the caller bounds it. Answers `false` when the host ended it.
     */
    suspend fun follow(
        method: MethodCommand,
        arguments: Array<Any?>,
        dropped: (String) -> Unit,
        each: suspend (Any?) -> Boolean,
    ): Boolean = rpc.follow(
        open = { client -> client.callServerStreaming(rpcCall(method, arguments)) },
        dropped = dropped,
        each = each,
    )

    override fun close() = rpc.close()

    private fun rpcCall(method: MethodCommand, arguments: Array<Any?>) =
        RpcCall(method.service.descriptor, method.declared, arguments, ++calls)
}

/**
 * `<service> <method> [flags]`: the call, its answer printed — or a stream, its elements printed as they come
 * ([probe]: see [rig]). A stream has no place in a batch, which never ends one ([inBatch]).
 */
private suspend fun Connection.call(
    command: List<String>,
    json: Boolean,
    out: PrintStream,
    err: PrintStream,
    probe: Boolean,
    inBatch: Boolean = false,
): Int {
    if (command.isEmpty()) usage("no command (see rig --help)")
    val commands = serviceCommands(command[0]) ?: unknownService(command[0])
    val name = command.getOrNull(1) ?: usage("${commands.name}: no method\n\n${commands.help()}")
    val method = commands.method(name) ?: unknownMethod(commands, name)
    val arguments = method.arguments(command.drop(2))
    if (method.streams && inBatch) {
        usage("run: ${method.usage()} is a stream, which a batch never gets past — run it on its own")
    }
    return if (method.streams) stream(method, arguments, out, err, probe) else answer(method, arguments, json, out, err)
}

/** A one-shot call: its answer printed, or why there is none. */
private suspend fun Connection.answer(
    method: MethodCommand,
    arguments: Array<Any?>,
    json: Boolean,
    out: PrintStream,
    err: PrintStream,
): Int {
    val answer = try {
        send(method, arguments)
    } catch (e: LeverRefused) {
        err.println("refused: ${e.message}")
        return EXIT_REFUSED
    } catch (e: TimeoutCancellationException) {
        err.println("failed: no answer from $url within the call's bound — raise --timeout (${e.message})")
        return EXIT_FAILED
    } catch (e: java.io.IOException) {
        err.println("failed: cannot reach $url: ${e.message}")
        return EXIT_FAILED
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Whatever the host threw, as it arrived — or why the host could not be reached.
        err.println("failed: ${e.message ?: e::class.simpleName}")
        return EXIT_FAILED
    }
    out.println(render(method, answer, json))
    return EXIT_OK
}

/** `run <file>`: each line a command, over one connection; stops at the first that does not answer. */
private suspend fun Connection.batch(args: List<String>, json: Boolean, out: PrintStream, err: PrintStream): Int {
    val source = args.singleOrNull() ?: usage("run <file>: one file, or '-' for stdin")
    val lines = if (source == "-") generateSequence(::readlnOrNull).toList() else readFile(source)
    lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
        err.println("> $line")
        val code = try {
            call(tokens(line), json, out, err, probe = false, inBatch = true)
        } catch (e: UsageError) {
            err.println("rig: ${e.message}")
            EXIT_USAGE
        }
        if (code != EXIT_OK) return code
    }
    return EXIT_OK
}

private fun readFile(path: String): List<String> =
    File(path).takeIf { it.isFile }?.readLines() ?: usage("run: no file '$path'")

/** A line's words: whitespace-separated, a quoted run ('…' or "…") one word. */
internal fun tokens(line: String): List<String> =
    Regex("""'([^']*)'|"([^"]*)"|(\S+)""").findAll(line).map { m ->
        m.groupValues.drop(1).first {
            it.isNotEmpty()
        }
    }.toList()

/** A lever's `Unit` prints `ok`, a `String` itself, anything else its JSON — one line of it with `--json`. */
private fun render(method: MethodCommand, answer: Any?, json: Boolean): String {
    val type = method.callable.returnType.kType
    if (type.classifier == Unit::class) return if (json) "null" else "ok"
    if (answer is String && !json) return answer
    return (if (json) ONE_LINE else PRETTY).encodeToString(serializer(type), answer)
}

private fun unknownService(name: String): Nothing =
    usage("no service '$name' — services: ${SERVICE_COMMANDS.joinToString { it.name }} (see rig --help)")

private fun unknownMethod(commands: ServiceCommands, name: String): Nothing =
    usage("${commands.name}: no method '$name' — methods: ${commands.methods.joinToString { it.name }}")

// Every field, a default's included: a reader of the answer (a script's `python3 -c`) finds what the value holds.
internal val ONE_LINE = Json { encodeDefaults = true }
private val PRETTY = Json {
    prettyPrint = true
    encodeDefaults = true
}
private val CALL_TIMEOUT = 60.seconds

/** A stream waits for what an app does in the background — an upload landing, a wake — so longer than a call. */
private val STREAM_TIMEOUT = 300.seconds

internal const val EXIT_OK = 0
internal const val EXIT_FAILED = 1
internal const val EXIT_USAGE = 2
internal const val EXIT_REFUSED = 3
