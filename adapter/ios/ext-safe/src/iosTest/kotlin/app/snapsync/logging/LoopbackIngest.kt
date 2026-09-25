@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package app.snapsync.logging

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * A receiving endpoint for the reporting SDK, inside the test executable (`docs/architecture.md`, "Hosts
 * are a closed set of what changes reachable states": an endpoint stood up only to receive and observe is part
 * of the observation, not a stand-in service). The `DiagnosticsReporter` contract's live binding points the
 * real adapter at [dsn] and reads [events] to see what actually left the process.
 *
 * Deliberately minimal — one client (the Sentry SDK), one route, loopback only — and hand-written rather than
 * Ktor, because `ktor-server-*` is withheld to `:test:rig` by the module set. What it relies on was measured
 * against sentry-cocoa 8.58.2 on 2026-09-23 (`changes/…/diagnostics-reporter-contracts/design.md`, M1–M2): the
 * SDK POSTs one envelope per request with a `Content-Length`, gzip-encoded.
 *
 * It decides nothing a clause asserts, with one exception it owes production: an event whose DECODED size
 * exceeds Bugsink's measured `MAX_EVENT_SIZE` is refused `413` and not recorded, so no clause can pass here
 * with a payload the real ingest would refuse (the size is judged decoded because compression hides it, M8).
 * Nothing else of Bugsink is modelled.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LoopbackIngest {

    private val lock = NSLock()
    private val received = mutableListOf<JsonObject>()
    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket() failed" } }

    /** The port the kernel assigned; the DSN is built from it, so parallel ingests never collide. */
    val port: Int

    @Volatile
    private var stopped = false

    init {
        port = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val address = alloc<sockaddr_in>().apply {
                sin_len = sizeOf<sockaddr_in>().convert()
                sin_family = AF_INET.convert()
                sin_port = 0u // any free port
                sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
            }
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
            check(listen(listener, BACKLOG) == 0) { "listen() failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname() failed" }
            swapBytes(address.sin_port).toInt()
        }
        NSThread { acceptLoop() }.start()
    }

    /** The DSN that routes the SDK here. The key is a fixed non-secret: nothing checks it. */
    val dsn: String get() = "http://$PUBLIC_KEY@127.0.0.1:$port/1"

    /** Every `event` item received so far, in arrival order. */
    fun events(): List<JsonObject> {
        lock.lock()
        try {
            return received.toList()
        } finally {
            lock.unlock()
        }
    }

    fun stop() {
        stopped = true
        shutdown(listener, SHUT_RDWR)
        close(listener)
    }

    private fun acceptLoop() {
        while (!stopped) {
            val client = accept(listener, null, null)
            if (client < 0) continue
            try {
                serve(client)
            } finally {
                close(client)
            }
        }
    }

    private fun serve(client: Int) {
        val request = readRequest(client) ?: return
        val body = gunzipIfNeeded(request)
        // The worst-case clause's real total, printed so the whole-event sum's slack is a measurement, not a claim.
        if (body.size > REPORTED_SIZE_FLOOR) println("LoopbackIngest: decoded envelope ${body.size} B of $MAX_EVENT_SIZE")
        val status = if (body.size > MAX_EVENT_SIZE) {
            413
        } else {
            val events = envelopeEvents(body)
            lock.lock()
            try {
                received += events
            } finally {
                lock.unlock()
            }
            200
        }
        val reason = if (status == 200) "OK" else "Payload Too Large"
        val payload = "{}"
        respond(
            client,
            "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload",
        )
    }

    private class Request(val headers: Map<String, String>, val body: ByteArray)

    /** Headers up to the blank line, then exactly `Content-Length` body bytes. `null` on a dropped connection. */
    private fun readRequest(client: Int): Request? {
        var buffered = ByteArray(0)
        var headerEnd = -1
        while (headerEnd < 0) {
            buffered += recvChunk(client) ?: return null
            headerEnd = buffered.indexOf(HEADER_END)
        }
        val head = buffered.copyOfRange(0, headerEnd).decodeToString()
        val headers = head.split("\r\n").drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        var body = buffered.copyOfRange(headerEnd + HEADER_END.size, buffered.size)
        while (body.size < length) body += recvChunk(client) ?: return null
        return Request(headers, body.copyOfRange(0, length))
    }

    private fun recvChunk(client: Int): ByteArray? {
        val chunk = ByteArray(CHUNK)
        val n = chunk.usePinned { recv(client, it.addressOf(0), CHUNK.convert(), 0) }
        return if (n <= 0) null else chunk.copyOfRange(0, n.toInt())
    }

    private fun respond(client: Int, text: String) {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { send(client, it.addressOf(0), bytes.size.convert(), 0) }
    }

    /**
     * A gzip member is a 10-byte header, a raw DEFLATE stream, and an 8-byte trailer. Foundation's `zlib`
     * algorithm is raw DEFLATE, so the framing is stripped here. The SDK writes no optional header fields
     * (flag byte 0); a member that carries any fails loudly rather than being misread.
     */
    private fun gunzipIfNeeded(request: Request): ByteArray {
        if (request.headers["content-encoding"] != "gzip") return request.body
        val gz = request.body
        check(gz.size > GZIP_FRAMING && gz[0] == 0x1f.toByte() && gz[1] == 0x8b.toByte()) { "not a gzip member" }
        check(gz[3] == 0.toByte()) { "gzip member carries optional header fields (flags ${gz[3]}), not handled" }
        val deflate = gz.copyOfRange(GZIP_HEADER, gz.size - GZIP_TRAILER)
        val data = deflate.usePinned { NSData.create(bytes = it.addressOf(0), length = deflate.size.convert()) }
        val inflated = checkNotNull(data.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)) {
            "the gzip body did not inflate"
        }
        val length = inflated.length.toInt()
        return if (length == 0) ByteArray(0) else inflated.bytes!!.reinterpret<ByteVar>().readBytes(length)
    }

    /**
     * An envelope is newline-delimited: an envelope header, then for each item an item header and its payload.
     * A payload whose item header names a `length` is exactly that many bytes; otherwise it runs to the next
     * newline. Only `event` items are kept — the SDK also sends `client_report` items (M2).
     */
    private fun envelopeEvents(envelope: ByteArray): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        var at = lineEnd(envelope, 0) + 1 // skip the envelope header
        while (at < envelope.size) {
            val headerEnd = lineEnd(envelope, at)
            val itemHeader = envelope.copyOfRange(at, headerEnd).decodeToString()
            if (itemHeader.isBlank()) break
            val header = Json.parseToJsonElement(itemHeader).jsonObject
            val start = headerEnd + 1
            val end = header["length"]?.jsonPrimitive?.int?.let { start + it } ?: lineEnd(envelope, start)
            if (header["type"]?.jsonPrimitive?.content == "event") {
                events += Json.parseToJsonElement(envelope.copyOfRange(start, end).decodeToString()).jsonObject
            }
            at = end + 1
        }
        return events
    }

    private fun lineEnd(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
        return i
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val PUBLIC_KEY = "cafebabecafebabecafebabecafebabe"
        const val BACKLOG = 8
        const val CHUNK = 64 * 1024
        const val GZIP_HEADER = 10
        const val GZIP_TRAILER = 8
        const val GZIP_FRAMING = GZIP_HEADER + GZIP_TRAILER

        /** Bugsink's `MAX_EVENT_SIZE`, measured 2026-07-29: larger events are refused with a `413`. */
        const val MAX_EVENT_SIZE = 1024 * 1024

        /** Only envelopes this large are worth a line: the dumps, not the per-clause sentinels. */
        private const val REPORTED_SIZE_FLOOR = 64 * 1024

        val HEADER_END = "\r\n\r\n".encodeToByteArray()

        /** `127.0.0.1` in network byte order, as `s_addr` holds it on a little-endian host. */
        const val LOOPBACK_NETWORK_ORDER: UInt = 0x0100007Fu

        fun swapBytes(value: UShort): UShort = (((value.toInt() and 0xff) shl 8) or (value.toInt() shr 8)).toUShort()
    }
}
