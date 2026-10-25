package app.snapsync.journeys.run

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramTest {

    private val runs = mutableListOf<Pair<List<String>, Map<String, String>>>()
    private var passes = true
    private val runner = JourneyRunner { classes, properties -> runs.add(classes to properties).let { passes } }

    @Test
    fun the_journeys_alone_run_against_the_addresses_given() {
        val code = run("journeys-only", "--app", APP_URL, "--backend", BACKEND_URL)
        assertEquals(EXIT_OK, code.first)
        assertEquals(
            listOf(listOf(JOURNEYS) to mapOf(APP_A to APP_URL, BACKEND to BACKEND_URL)),
            runs,
        )
    }

    @Test
    fun a_failed_journey_fails_the_run() {
        passes = false
        assertEquals(EXIT_FAILED, run("journeys-only", "--app", "a", "--backend", "b").first)
    }

    @Test
    fun a_missing_address_runs_nothing() {
        val (code, err) = run("journeys-only", "--app", "a")
        assertEquals(EXIT_USAGE, code)
        assertTrue("--backend <value> is required" in err, err)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun an_unknown_command_or_flag_is_a_usage_error() {
        assertEquals(EXIT_USAGE, run("journey").first)
        assertEquals(EXIT_USAGE, run("journeys-only", "app").first)
        assertEquals(EXIT_USAGE, run("journeys-only", "--app").first)
        assertEquals(EXIT_USAGE, run().first)
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val code = program(args.toList(), runner, PrintStream(ByteArrayOutputStream()), PrintStream(err, true))
        return code to err.toString()
    }

    private companion object {
        const val APP_URL = "http://127.0.0.1:18099"
        const val BACKEND_URL = "http://127.0.0.1:8080/api/v2"
    }
}
