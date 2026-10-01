package app.snapsync.sentry

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject

/**
 * A receiving endpoint for the reporting SDK, inside the instrumented test process — the Android twin of the iOS
 * binding's ingest (`docs/architecture.md`, "Hosts are a closed set of what changes reachable states": an endpoint
 * stood up only to receive and observe is part of the observation, not a stand-in service). The `CrashReporter`
 * contract's live binding points the real adapter at [dsn] and reads [events] to see what actually left the process.
 *
 * Deliberately minimal — one client, one route, loopback only — and hand-written, because `ktor-server-*` is withheld
 * to `:test:rig` by the module set. Envelopes are read by the parsing both bindings share (`Wire.kt`), and an event
 * over Bugsink's size is refused `413`, as there.
 *
 * Its port is reserved at construction and it listens only once [open] runs, so a binding can hand the SDK a
 * destination that refuses connections until then — the offline half of `ACROSS_A_RESTART`.
 */
internal class LoopbackIngest {

    private val received = mutableListOf<JsonObject>()

    /** Chosen by the kernel and released at once; nothing else on the loopback claims it inside one clause. */
    val port: Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

    val dsn: String get() = ingestDsn(port)

    @Volatile
    private var server: ServerSocket? = null

    fun open() {
        if (server != null) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOOPBACK, port))
        }
        server = socket
        thread(name = "loopback-ingest", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                client.use(::serve)
            }
        }
    }

    /** Every `event` item received so far, in arrival order. */
    fun events(): List<JsonObject> = synchronized(received) { received.toList() }

    fun stop() {
        server?.close()
        server = null
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val headers = readHeaders(input) ?: return
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val raw = input.readExactly(length) ?: return
        val body = if (headers["content-encoding"] == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
        val status = if (body.size > MAX_EVENT_SIZE) {
            TOO_LARGE
        } else {
            val events = envelopeEvents(body)
            synchronized(received) { received += events }
            OK
        }
        val payload = "{}"
        client.getOutputStream().apply {
            write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload").encodeToByteArray(),
            )
            flush()
        }
    }

    /** Header lines up to the blank line, names lowercased. `null` on a dropped connection. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (tail != HEADER_END) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            tail = tail shl Byte.SIZE_BITS or b
        }
        return head.toString(Charsets.US_ASCII.name()).split("\r\n").drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
    }

    private fun InputStream.readExactly(length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }
        return bytes
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        const val OK = "200 OK"
        const val TOO_LARGE = "413 Payload Too Large"

        /** `\r\n\r\n` as the last four bytes read. */
        const val HEADER_END = 0x0D0A0D0A
    }
}
