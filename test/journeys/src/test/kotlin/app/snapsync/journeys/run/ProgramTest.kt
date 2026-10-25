package app.snapsync.journeys.run

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramTest {

    private val runs = mutableListOf<List<String>>()
    private val runner = JourneyRunner { classes, _ -> runs.add(classes).let { true } }

    @Test
    fun a_run_names_its_platform_and_a_known_one() {
        assertTrue("--platform <value> is required" in run("run").second)
        assertTrue("no platform 'windows'" in run("run", "--platform", "windows").second)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun an_android_run_names_its_device() {
        val (code, err) = run("run", "--platform", "android")
        assertEquals(EXIT_USAGE, code)
        assertTrue("--serial <value> is required" in err, err)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun a_settle_names_its_simulator() {
        assertTrue("--udid <value> is required" in run("settle").second)
    }

    @Test
    fun an_unknown_command_or_flag_is_a_usage_error() {
        assertEquals(EXIT_USAGE, run("journey").first)
        assertEquals(EXIT_USAGE, run("run", "platform").first)
        assertEquals(EXIT_USAGE, run("run", "--platform").first)
        assertEquals(EXIT_USAGE, run().first)
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val code = program(args.toList(), runner, PrintStream(ByteArrayOutputStream()), PrintStream(err, true))
        return code to err.toString()
    }
}
