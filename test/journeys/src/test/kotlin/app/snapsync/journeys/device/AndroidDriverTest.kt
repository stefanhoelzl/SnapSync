package app.snapsync.journeys.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

/** A [Shell] that records each command line, and the environment it added, and answers what [answers] gives it. */
class RecordingShell(private val answers: (List<String>) -> Answer = { Answer(0, "", "") }) : Shell {
    val commands = mutableListOf<List<String>>()
    val envs = mutableListOf<Map<String, String>>()

    override fun run(command: List<String>, within: Duration, env: Map<String, String>): Answer =
        answers(command).also {
            commands += command
            envs += env
        }

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
    fun a_swipe_removes_the_apps_own_task_from_the_recent_apps() {
        // `dumpsys activity recents`, as API 36 printed it (2026-11-07).
        val recents = """
              * Recent #0: Task{64d1a65 #165 type=standard A=10222:app.snapsync}
              * Recent #1: Task{3a836bb #2 type=home I=com.google.android.apps.nexuslauncher/.NexusLauncherActivity}
              * Recent #2: Task{3dc2d28 #162 type=standard A=10171:app.snapsync.other}
        """.trimIndent()
        val shell = RecordingShell { if ("recents" in it) Answer(0, recents, "") else Answer(0, "", "") }
        AndroidDriver(shell, "adb", "s").removeTask("app.snapsync")
        assertEquals(listOf("shell", "am", "stack", "remove", "165"), shell.commands.last().drop(3))
        assertEquals(162, AndroidDriver.taskOf(recents, "app.snapsync.other"))
        assertEquals(null, AndroidDriver.taskOf(recents, "app.snap"))
    }

    @Test
    fun the_last_exit_is_the_newest_record_and_names_its_subreason() {
        // `dumpsys activity exit-info`, newest first, as API 36 printed it after `am stack remove` (2026-11-07).
        val dump = """
            ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
              package: app.snapsync
                Historical Process Exit for uid=10222
                    ApplicationExitInfo #0:
                      timestamp=2026-11-07 19:02:46.387 pid=4571 realUid=10222 packageUid=10222 definingUid=10222 user=0
                      process=app.snapsync reason=10 (USER REQUESTED) subreason=22 (REMOVE TASK) status=0
                      importance=400 pss=0.00 rss=0.00 description=remove task state=empty trace=null
                    ApplicationExitInfo #1:
                      timestamp=2026-11-07 19:01:27.001 pid=4402 realUid=10222 packageUid=10222 definingUid=10222 user=0
                      process=app.snapsync reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0
        """.trimIndent()
        assertEquals(AndroidDriver.ExitRecord(4571, 10, 22, "REMOVE TASK"), AndroidDriver.ExitRecord.parse(dump))
        assertEquals(
            null,
            AndroidDriver.ExitRecord.parse("ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)"),
        )
    }

    @Test
    fun the_library_watches_are_the_jobs_waiting_for_a_content_change() {
        val dump = """
            JOB #u0a216/4: 7a3b app.snapsync/androidx.work.impl.background.systemjob.SystemJobService
                Minimum latency: +59m59s
            JOB #u0a216/7: 9c2d app.snapsync/app.snapsync.android.work.WakeJobService
                  Trigger content URIs:
                    1 content://media/external/images/media
        """.trimIndent()
        val driver = AndroidDriver(RecordingShell { Answer(0, dump, "") }, "adb", "s")
        assertEquals(1, driver.libraryWatches("app.snapsync"))
        assertEquals(1, driver.delayedJobs("app.snapsync"))
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
