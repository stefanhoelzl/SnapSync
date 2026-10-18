package app.snapsync.cli

import app.snapsync.levers.LeverRefused
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.serializer
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// A stream from a shell (`rig world events`): what a wait in a skill reads, with no matcher of its own — `jq` picks the
// element, and the wait ends when `jq` has it and goes (`docs/testing.md`, "How a test waits").
//
// - One line of JSON per element, as the host sends it: an event's `type` is its serial name.
// - It ends when its reader has gone (exit 0), at its bound (exit 1), or when the host refuses it (exit 3).
// - A reader is noticed gone only by a write that fails. A stream can wait long between elements, so into a pipe it
//   probes: a bare newline every [PROBE_EVERY] — whitespace, which `jq` reads past. Without it, a `jq` that took its
//   element and left would keep the wait open until the next element came, or the bound.
// - A dropped connection is opened again until the bound: an app the operating system suspends in the background can
//   come back with its sockets gone. A device host serves from its last act, so the stream it serves again begins with
//   what was already printed; that many are skipped — exact, unless another caller acted in between.

/** The stream [method] over this connection, each element printed to [out] as it arrives; answers the exit code. */
internal suspend fun Connection.stream(
    method: MethodCommand,
    arguments: Array<Any?>,
    out: PrintStream,
    err: PrintStream,
    probe: Boolean,
): Int {
    val printing = Printing(method, out)
    val ended = try {
        withTimeoutOrNull(streamTimeout ?: Duration.INFINITE) {
            coroutineScope {
                val prober = launch { if (probe) printing.probe() }
                try {
                    var ended: Ended? = null
                    while (ended == null) ended = follow(method, arguments, printing)
                    ended
                } finally {
                    prober.cancel()
                }
            }
        }
    } catch (_: ReaderGone) {
        Ended.BY_THE_READER
    } catch (e: LeverRefused) {
        err.println("refused: ${e.message}")
        return EXIT_REFUSED
    }
    if (ended != null) return EXIT_OK
    val bound = streamTimeout?.let { " (${it.inWholeSeconds} s)" }.orEmpty()
    err.println(
        "timed out: the stream's bound$bound passed after ${printing.printed} elements" +
            printing.dropped?.let { " — the connection last dropped: $it" }.orEmpty() + "; raise --timeout to wait longer",
    )
    return EXIT_FAILED
}

/**
 * Follows the stream over one connection: [Ended.BY_THE_HOST] when the host ended it, `null` when the connection
 * dropped — then reconnected, to be followed again. The reader going or the host refusing ends the stream.
 */
private suspend fun Connection.follow(method: MethodCommand, arguments: Array<Any?>, printing: Printing): Ended? =
    try {
        var seen = 0
        stream(method, arguments).collect { value -> if (seen++ >= printing.printed) printing.print(value) }
        Ended.BY_THE_HOST
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        if (e is ReaderGone || e is LeverRefused) throw e
        // The bound passed, or the reader went: this stream is over.
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        // The connection dropped — kRPC cancels a stream whose socket closed — or the host could not be reached: open
        // another, until the bound.
        printing.dropped = e.message ?: e::class.simpleName
        reconnect()
        delay(RECONNECT_AFTER)
        null
    }

/** What a stream printed to [out]: one line of JSON per element; a write that fails means its reader has gone. */
private class Printing(method: MethodCommand, private val out: PrintStream) {
    // kRPC describes a stream by its element type; a `Flow<…>` itself, should it ever, by its argument.
    private val element = method.callable.returnType.kType.let { serializer(it.arguments.singleOrNull()?.type ?: it) }

    /** How many elements were printed: as many are skipped when a dropped stream is served again. */
    var printed = 0
        private set

    /** Why the connection last dropped, if it did. */
    var dropped: String? = null

    fun print(value: Any?) {
        write(ONE_LINE.encodeToString(element, value) + "\n")
        printed++
    }

    /** A bare newline every [PROBE_EVERY], so a reader that has gone is noticed while no element comes. */
    suspend fun probe() {
        while (true) {
            delay(PROBE_EVERY)
            write("\n")
        }
    }

    private fun write(text: String) {
        out.print(text)
        out.flush()
        if (out.checkError()) throw ReaderGone()
    }
}

private enum class Ended { BY_THE_READER, BY_THE_HOST }

/** A write to the stream's reader failed: it has gone, which ends the stream. */
private class ReaderGone : Exception("the reader has gone")

/** How often a stream into a pipe probes it for a reader that has gone. */
private val PROBE_EVERY = 1.seconds

/** How long a stream waits before opening a dropped connection again. */
private val RECONNECT_AFTER = 500.milliseconds
