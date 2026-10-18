package app.snapsync.cli

import app.snapsync.levers.BackendCall
import app.snapsync.levers.BackendFault
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.PhotoKind
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.rig.JvmRigHost
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `rig`, the typed services from a shell: its commands derived from the declared services, each flag's text decoded by
 * its parameter's type, and a call's answer, failure and refusal as they reach a shell — driven against the JVM host
 * over both of its backends.
 */
class RigCliTest {

    @Test
    fun every_declared_method_is_a_command() {
        LEVER_SERVICES.forEach { service ->
            val commands = checkNotNull(serviceCommands(service.name)) { "no command group for ${service.name}" }
            assertEquals(service.descriptor.callables.values.map { it.name }, commands.methods.map { it.declared })
            commands.methods.forEach { assertEquals(it, commands.method(it.name)) }
        }
    }

    /** `--timeout` bounds one call in seconds; `0` lifts the bound — what a contract run or a big seed asks for. */
    @Test
    fun a_timeout_of_zero_lifts_the_bound() {
        assertEquals(null, seconds("0"))
        assertEquals(90.seconds, seconds("90"))
        assertFailsWith<UsageError> { seconds("-1") }
        assertFailsWith<UsageError> { seconds("soon") }
    }

    @Test
    fun a_name_is_spelled_as_a_command() {
        assertEquals("fail-listing", commandSpelling("failListing"))
        assertEquals("server-error", commandSpelling("SERVER_ERROR"))
        assertEquals("device-modified", commandSpelling("device-modified"))
        assertEquals("offline", commandSpelling("offline"))
    }

    @Test
    fun flags_decode_by_the_parameters_types() {
        val fail = method("backend", "fail")
        assertEquals(
            listOf("createEvent", BackendFault.SERVER_ERROR, 2),
            fail.arguments(listOf("--operation", "createEvent", "--fault", "server-error", "--times=2")).toList(),
        )
        // An enum by its name in any case, or its serial name; an omitted nullable is null.
        assertEquals(
            listOf("x", BackendFault.MAINTENANCE, null),
            fail.arguments(listOf("--operation", "x", "--fault", "MAINTENANCE")).toList(),
        )
        assertEquals(listOf(BackendCall.EVENT), method("backend", "hold").arguments(listOf("--call", "event")).toList())
        // A bare switch is true; a list takes the flag repeatedly or comma-separated.
        assertEquals(listOf(true), method("backend", "offline").arguments(listOf("--on")).toList())
        assertEquals(listOf(false), method("backend", "offline").arguments(listOf("--on", "false")).toList())
        val seeded = method("public-backend", "foreign-device").arguments(
            listOf("--device", "D", "--assets", "A1,A2", "--assets", "A3", "--kind", "motion-photo", "--final"),
        )
        assertEquals(
            listOf("D", listOf("A1", "A2", "A3"), null, PhotoKind.MOTION_PHOTO, null, true, null),
            seeded.toList(),
        )
    }

    @Test
    fun a_flag_that_cannot_be_decoded_is_a_usage_error() {
        val fail = method("backend", "fail")
        assertFailsWith<UsageError> {
            fail.arguments(listOf("--operation", "x"))
        }.also { assertMentions(it, "--fault is required") }
        assertFailsWith<UsageError> { fail.arguments(listOf("--operation", "x", "--fault", "nope")) }
        assertFailsWith<UsageError> {
            fail.arguments(
                listOf("--operation", "x", "--fault", "closed", "--times", "two"),
            )
        }
        assertFailsWith<UsageError> { fail.arguments(listOf("--operation", "x", "--fault", "closed", "--bogus", "1")) }
            .also { assertMentions(it, "no parameter --bogus") }
    }

    @Test
    fun a_batch_line_splits_into_words() {
        assertEquals(listOf("backend", "next-event-id", "--id", "a b"), tokens("backend next-event-id --id 'a b'"))
    }

    @Test
    fun a_lever_and_a_read_answer_over_the_host() = onHost("mock") { url ->
        assertEquals(Run(EXIT_OK, "ok", ""), rigOn(url, "backend", "offline", "--on"))
        val switches = rigOn(url, "--json", "backend", "switches")
        assertEquals(EXIT_OK, switches.code)
        assertTrue(switches.out.startsWith("{\"offline\":true"), switches.out)
        // A batch runs every line over one connection.
        val steps = kotlin.io.path.createTempFile().toFile().apply {
            writeText("# undo it\nbackend offline --on false\nbackend switches\n")
            deleteOnExit()
        }
        val batch = rigOn(url, "--json", "run", steps.path)
        assertEquals(EXIT_OK, batch.code, batch.err)
        assertEquals(
            listOf("null", "{\"offline\":false"),
            batch.out.lines().map { it.take("{\"offline\":false".length) },
        )
    }

    @Test
    fun what_the_host_threw_prints_as_it_arrived() = onHost("mock") { url ->
        val run = rigOn(url, "backend", "fail", "--operation", "nope", "--fault", "closed")
        assertEquals(EXIT_FAILED, run.code)
        assertTrue(run.err.startsWith("failed: operation must be one of the device API's operationIds"), run.err)
    }

    @Test
    fun a_refusal_prints_the_hosts_reason() = onHost("deno") { url ->
        val run = rigOn(url, "backend", "offline", "--on")
        assertEquals(EXIT_REFUSED, run.code)
        assertTrue(run.err.startsWith("refused: backend.offline is refused here"), run.err)
    }

    @Test
    fun an_unknown_command_names_what_there_is() {
        val service = rig("--url", "http://127.0.0.1:1", "backnd", "offline")
        assertEquals(EXIT_USAGE, service.code)
        assertMentions(service.err, "services: rig, world, backend, public-backend")
        val method = rig("--url", "http://127.0.0.1:1", "backend", "offlin")
        assertEquals(EXIT_USAGE, method.code)
        assertMentions(method.err, "methods: offline, fail-listing")
        assertEquals(EXIT_USAGE, rig("backend", "offline", "--on").code, "a call names its host")
    }

    /** A stream prints one line of JSON per event, and ends with exit 0 when its reader has gone. */
    @Test
    fun a_stream_ends_when_its_reader_goes() = onHost("mock") { url ->
        val reader = Reader(lines = 1)
        val code = PrintStream(
            reader,
            true,
        ).use { out -> rig(listOf("--url", url, "world", "events"), out, System.err, probe = true) }
        assertEquals(EXIT_OK, code)
        assertTrue(reader.text.lines().first().startsWith("{\"type\":"), reader.text)
    }

    /** Its bound passing is a failure: no reader took what it waited for. */
    @Test
    fun a_stream_fails_when_its_bound_passes() = onHost("mock") { url ->
        val run = rigOn(url, "--timeout", "2", "world", "events")
        assertEquals(EXIT_FAILED, run.code)
        assertTrue(run.err.startsWith("timed out: the stream's bound (2 s) passed"), run.err)
        assertTrue(run.out.lines().all { it.startsWith("{\"type\":") }, run.out)
    }

    /**
     * A dropped connection is opened again until the bound — a device's app the operating system suspended comes back
     * with its sockets gone — and the stream goes on with what the host serves once it is back.
     */
    @Test
    fun a_stream_outlives_a_dropped_connection() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$port"
        val reader = Reader(until = "\"name\":\"backend.offline\"")
        val stream = Thread {
            PrintStream(reader, true).use {
                rig(listOf("--url", url, "--timeout", "60", "world", "events"), it, System.err, probe = true)
            }
        }.apply { start() }
        val first = runBlocking { JvmRigHost.start("mock", port) }
        try {
            reader.awaitLines(1)
        } finally {
            first.close()
        }
        val second = runBlocking { JvmRigHost.start("mock", port) }
        try {
            // Acted on until the stream shows it: its first lines are skipped as the ones printed before the drop.
            val deadline = System.currentTimeMillis() + STREAM_JOIN_MS
            while (!reader.done && System.currentTimeMillis() < deadline) {
                assertEquals(EXIT_OK, rigOn(url, "backend", "offline", "--on").code)
                Thread.sleep(ACT_EVERY_MS)
            }
            stream.join(STREAM_JOIN_MS)
        } finally {
            second.close()
        }
        assertTrue(reader.done, "the act on the host that came back never reached the stream:\n${reader.text}")
    }

    @Test
    fun a_stream_has_no_place_in_a_batch() {
        val steps = kotlin.io.path.createTempFile().toFile().apply {
            writeText("world events\n")
            deleteOnExit()
        }
        val run = rig("--url", "http://127.0.0.1:1", "run", steps.path)
        assertEquals(EXIT_USAGE, run.code)
        assertMentions(run.err, "is a stream")
    }

    @Test
    fun help_needs_no_host() {
        assertMentions(rig("--help").out, "public-backend")
        assertMentions(rig("backend", "--help").out, "fail --operation <String> --fault <server-error|")
        assertMentions(rig("public-backend", "foreign-device", "--help").out, "--assets <String…>")
        assertEquals(EXIT_OK, rig("help").code)
    }

    private data class Run(val code: Int, val out: String, val err: String)

    /**
     * A stream's reader: it goes — every write after fails — once it holds [lines] lines, or a line containing [until].
     */
    private class Reader(private val lines: Int = Int.MAX_VALUE, private val until: String? = null) : OutputStream() {
        private val buffer = StringBuilder()

        @Volatile var done = false
            private set

        val text: String get() = synchronized(buffer) { buffer.toString() }

        override fun write(b: Int) {
            if (done) throw IOException("the reader has gone")
            synchronized(buffer) {
                buffer.append(b.toChar())
                // Whole lines only: a line is the reader's once its newline arrived.
                val held = buffer.split('\n').dropLast(1).filter { it.isNotBlank() }
                if (held.size >= lines || (until != null && held.any { until in it })) done = true
            }
        }

        fun awaitLines(n: Int) {
            val deadline = System.currentTimeMillis() + STREAM_JOIN_MS
            while (text.lines().count { it.isNotBlank() } < n && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
    }

    private fun rig(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true).use { o -> PrintStream(err, true).use { e -> rig(args.toList(), o, e) } }
        return Run(code, out.toString().trim(), err.toString().trim())
    }

    private fun rigOn(url: String, vararg args: String) = rig("--url", url, *args)

    private fun onHost(backend: String, block: (String) -> Unit) {
        val host = runBlocking { JvmRigHost.start(backend) }
        try {
            block("http://127.0.0.1:${host.port}")
        } finally {
            host.close()
        }
    }

    private fun method(service: String, name: String) = checkNotNull(serviceCommands(service)?.method(name))

    private fun assertMentions(e: Throwable, text: String) = assertMentions(e.message.orEmpty(), text)

    private fun assertMentions(actual: String, text: String) = assertTrue(
        text in actual,
        "expected '$text' in:\n$actual",
    )

    private companion object {
        const val STREAM_JOIN_MS = 30_000L
        const val ACT_EVERY_MS = 300L

        init {
            // Both services this suite drives are declared; a rename fails here before it fails a shell.
            check(BackendLevers.name == "backend" && PublicBackendLevers.name == "public-backend")
        }
    }
}
