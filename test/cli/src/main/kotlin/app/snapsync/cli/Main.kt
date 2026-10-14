package app.snapsync.cli

import app.snapsync.levers.LeverRefused
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.RpcCall
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
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
    exitProcess(rig(args.toList(), System.out, System.err))
}

/** Runs one command line, printing to [out] and [err]; answers the exit code. */
internal fun rig(args: List<String>, out: PrintStream, err: PrintStream): Int = try {
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
        else -> options.connected { call(options.command, options.json, out, err) }
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
    /** How long one call may take before it fails; `null`: as long as it takes. */
    val timeout: Duration?,
) {
    fun connected(block: suspend Connection.() -> Int): Int {
        val base = url ?: usage("--url <base> is required, e.g. --url http://127.0.0.1:18101 (see rig --help)")
        return Connection(base, timeout).use { runBlocking { it.block() } }
    }

    companion object {
        fun parse(args: List<String>): Options {
            var url: String? = null
            var json = false
            var help = false
            var timeout: Duration? = CALL_TIMEOUT
            val command = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--url" -> url = args.getOrNull(++i) ?: usage("--url needs a value")
                    "--json" -> json = true
                    "--timeout" -> timeout = seconds(args.getOrNull(++i) ?: usage("--timeout needs a value"))
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

/** `--timeout`'s seconds as the bound of one call; `0` lifts it. */
internal fun seconds(text: String): Duration? {
    val n = text.toLongOrNull()?.takeIf { it >= 0 } ?: usage("--timeout: '$text' is no number of seconds (0: none)")
    return if (n == 0L) null else n.seconds
}

/** One WebSocket to the host's kRPC route, every call of a run over it. */
private class Connection(base: String, private val timeout: Duration?) : AutoCloseable {
    val url = "ws${base.trimEnd('/').removePrefix("http")}$RPC_PATH"
    private val http = HttpClient(CIO) { installKrpc() }
    private val client: KtorRpcClient by lazy { http.rpc(url) { rpcConfig { serialization { json() } } } }
    private var calls = 0L

    suspend fun send(method: MethodCommand, arguments: Array<Any?>): Any? {
        val call = RpcCall(method.service.descriptor, method.declared, arguments, ++calls)
        return if (timeout == null) client.call<Any?>(call) else withTimeout(timeout) { client.call<Any?>(call) }
    }

    override fun close() {
        runCatching { client.close() }
        http.close()
    }
}

/** `<service> <method> [flags]`: the call, its answer printed. */
private suspend fun Connection.call(command: List<String>, json: Boolean, out: PrintStream, err: PrintStream): Int {
    if (command.isEmpty()) usage("no command (see rig --help)")
    val commands = serviceCommands(command[0]) ?: unknownService(command[0])
    val name = command.getOrNull(1) ?: usage("${commands.name}: no method\n\n${commands.help()}")
    val method = commands.method(name) ?: unknownMethod(commands, name)
    val arguments = method.arguments(command.drop(2))
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
            call(tokens(line), json, out, err)
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
private val ONE_LINE = Json { encodeDefaults = true }
private val PRETTY = Json {
    prettyPrint = true
    encodeDefaults = true
}
private val CALL_TIMEOUT = 60.seconds
private const val RPC_PATH = "/rpc"

internal const val EXIT_OK = 0
internal const val EXIT_FAILED = 1
internal const val EXIT_USAGE = 2
internal const val EXIT_REFUSED = 3
