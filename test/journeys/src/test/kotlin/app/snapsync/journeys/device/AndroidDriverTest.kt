package app.snapsync.journeys.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

/** A [Shell] that records each command line and answers what [answers] gives it for one. */
class RecordingShell(private val answers: (List<String>) -> Answer = { Answer(0, "", "") }) : Shell {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>, within: Duration): Answer = answers(command).also { commands += command }

    override fun runInto(command: List<String>, into: File, within: Duration): Answer = run(command, within)
}

class AndroidDriverTest {

    @Test
    fun every_command_names_its_device() {
        val shell = RecordingShell()
        AndroidDriver(shell, "adb", "emulator-5554").apply {
            booted()
            forceStop("app.snapsync")
            forward(18099, 18099)
        }
        assertTrue(shell.commands.all { it.take(3) == listOf("adb", "-s", "emulator-5554") }, "${shell.commands}")
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "am", "force-stop", "app.snapsync"),
            shell.commands[1],
        )
    }

    @Test
    fun the_delayed_jobs_are_the_ones_waiting_for_a_minimum_latency() {
        val dump = """
            JOB #u0a216/4: 7a3b app.snapsync/androidx.work.impl.background.systemjob.SystemJobService
                Minimum latency: +59m59s
            JOB #u0a216/5: 9c2d app.snapsync/androidx.work.impl.background.systemjob.SystemJobService
                Required constraints: CONTENT_TRIGGER
        """.trimIndent()
        val driver = AndroidDriver(RecordingShell { Answer(0, dump, "") }, "adb", "emulator-5554")
        assertEquals(1, driver.delayedJobs("app.snapsync"))
    }

    @Test
    fun a_photo_is_pushed_into_the_camera_folder_and_scanned_before_it_is_read() {
        val row = "Row: 0 _id=456, is_pending=0, datetaken=1792929600000"
        val shell = RecordingShell { if ("query" in it) Answer(0, row, "") else Answer(0, "", "") }
        val taken = AndroidDriver(shell, "adb", "s").takePhoto(File("/tmp/p.jpg"), "PXL_x.jpg")
        assertEquals(AndroidDriver.MediaRow(456, pending = false, takenMillis = 1792929600000), taken)
        val steps = shell.commands.map { it.drop(3) }
        assertEquals(listOf("push", "/tmp/p.jpg", "/sdcard/DCIM/Camera/PXL_x.jpg"), steps[0])
        assertEquals(
            listOf("shell", "content", "call", "--uri", "content://media", "--method", "scan_file", "--arg", "/sdcard/DCIM/Camera/PXL_x.jpg"),
            steps[1],
        )
        assertEquals("query", steps[2][2])
        // Quoted for the device's shell, which `adb shell` hands the words to: the query gets its own quotes.
        assertEquals("\"_display_name='PXL_x.jpg'\"", steps[2].last())
    }

    @Test
    fun a_pending_row_and_an_unscanned_photo_read_as_what_they_are() {
        val pending = AndroidDriver.MediaRow.parse("Row: 0 _id=455, is_pending=1, datetaken=NULL")
        assertEquals(AndroidDriver.MediaRow(455, pending = true, takenMillis = null), pending)
        assertEquals(null, AndroidDriver.MediaRow.parse("No result found."))
    }

    @Test
    fun a_failing_command_fails_naming_itself_and_what_it_printed() {
        val driver = AndroidDriver(RecordingShell { Answer(1, "", "Failure [INSTALL_FAILED]") }, "adb", "s")
        val failed = assertFailsWith<CommandFailed> { driver.install(File("a.apk")) }
        assertTrue(
            "adb -s s install -r a.apk" in failed.message.orEmpty() && "INSTALL_FAILED" in failed.message.orEmpty(),
        )
    }
}
