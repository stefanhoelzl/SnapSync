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
    fun every_declared_one_shot_method_is_a_command() {
        LEVER_SERVICES.forEach { service ->
            val commands = checkNotNull(serviceCommands(service.name)) { "no command group for ${service.name}" }
            val oneShot = service.descriptor.callables.values.filterNot { it.isNonSuspendFunction }.map { it.name }
            assertEquals(oneShot, commands.methods.map { it.declared })
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
        assertEquals(EXIT_USAGE, rig("--url", "http://127.0.0.1:1", "world", "events").code, "a stream is no command")
    }

    @Test
    fun help_needs_no_host() {
        assertMentions(rig("--help").out, "public-backend")
        assertMentions(rig("backend", "--help").out, "fail --operation <String> --fault <server-error|")
        assertMentions(rig("public-backend", "foreign-device", "--help").out, "--assets <String…>")
        assertEquals(EXIT_OK, rig("help").code)
    }

    private data class Run(val code: Int, val out: String, val err: String)

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
        init {
            // Both services this suite drives are declared; a rename fails here before it fails a shell.
            check(BackendLevers.name == "backend" && PublicBackendLevers.name == "public-backend")
        }
    }
}
