package app.snapsync.journeys

import app.snapsync.client.RigClient
import app.snapsync.control.Verifies
import app.snapsync.journeys.device.AndroidDriver
import app.snapsync.journeys.device.AndroidDriver.ExitRecord
import app.snapsync.journeys.device.DatedJpeg
import app.snapsync.journeys.device.ProcessShell
import app.snapsync.journeys.run.AndroidRun
import app.snapsync.journeys.run.BackendRequests
import app.snapsync.journeys.run.awaitChannel
import app.snapsync.journeys.run.until
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A swipe from the recent apps leaves the app's background uploading standing (capability `delivery`): A — joined and
 * sharing, by [Journeys] — is left for the home screen and its task removed as a person's swipe removes it; a photo is
 * taken; and the photo reaches the backend while A stays closed. Android's alone, as the force-stop is.
 *
 * What it reads, every one of them outside the app: JobScheduler's record (the heartbeat and the library watch pending,
 * before the swipe and after it), the system's record of how the process ended (`REMOVE TASK`, not `FORCE STOP`), the
 * window manager's focus (the launcher's, never A's), and the backend's — the request log's line for the photo's upload,
 * then the event's union. Once A is closed its process is frozen whenever no job runs it, so the control channel is
 * not asked anything until A is opened again at the end, as [ForceStopJourney], which runs next, finds it.
 */
class SwipeAwayJourney {

    private val appA = address("appA")
    private val backend = address("backend").trimEnd('/')
    private val requests = BackendRequests(File(address("backendLog")))
    private val device = AndroidDriver(ProcessShell, address("adb"), address("androidSerial"))

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A force-quit pauses the app's own background uploading only until the next opening",
        scenario = "Swiping the app away on Android keeps uploading",
    )
    fun aPhotoTakenAfterTheAppWasSwipedAwayIsUploadedWithTheAppClosed() = runBlocking {
        RigClient(appA).use { a ->
            HttpClient(CIO).use { http ->
                val event = joinedTo(a.state().ui) ?: fail("A is not joined: the journeys before this one join it")
                val key = inviteKey(a.state().ui)
                assertWakesPending("A, joined, waits for its heartbeat and for a new photo")

                device.home()
                val pid = assertNotNull(device.pid(AndroidRun.APP), "A runs before it is swiped away")
                device.removeTask(AndroidRun.APP)
                // The process ends on the system's own thread, after `am stack remove` has returned.
                until(ENDED, "the swipe ends A's process") { !device.running(AndroidRun.APP) }
                val exit = assertNotNull(device.lastExit(AndroidRun.APP), "the system recorded how A's process ended")
                assertEquals(pid, exit.pid, "the exit recorded is the swiped process's: $exit")
                assertTrue(
                    exit.reason == ExitRecord.USER_REQUESTED && exit.subreason == ExitRecord.REMOVE_TASK,
                    "A ended as a swipe from the recent apps ends it — not a force-stop: $exit",
                )
                assertWakesPending("the swipe leaves A's wakes pending")

                // Taken today at noon (UTC, the emulator's zone): inside the event, which runs from yesterday.
                val name = "PXL_swipe_away_${System.currentTimeMillis()}.jpg"
                val photo = File.createTempFile("swipe-away", ".jpg").apply { deleteOnExit() }
                DatedJpeg.write(photo, LocalDate.now(ZoneOffset.UTC).atTime(LocalTime.NOON))
                val from = requests.mark()
                val row = device.takePhoto(photo, name)
                assertTrue(!row.pending && row.takenMillis != null, "the photo is in the library, dated: $row")

                // Its name read with the key A's invite carries: an encrypted event names a photo only sealed for it.
                requests.await(UPLOAD, "the photo taken after the swipe, uploaded by A in the background", from) {
                    it.method == "PUT" && "/events/$event/files/" in it.url && nameUploaded(key, it.url) == name &&
                        it.status in STORED
                }
                val focus = device.focusedWindow()
                assertTrue(AndroidRun.APP !in focus, "A stayed closed while it uploaded: $focus")
                val files = union(
                    http,
                    backend,
                    event,
                ).flatMap { asset -> namesOf(key, event, asset).map { it.second } }
                assertTrue(name in files, "the photo in the event union: $files")

                // Opened again, as the force-stop journey after this one finds A.
                device.start(AndroidRun.ACTIVITY)
                a.awaitChannel(AndroidRun.CHANNEL_UP)
            }
        }
    }

    /** The heartbeat and the library watch, as JobScheduler holds them for A: both pending, or fail naming [what]. */
    private fun assertWakesPending(what: String) {
        val heartbeats = device.delayedJobs(AndroidRun.APP)
        val watches = device.libraryWatches(AndroidRun.APP)
        assertTrue(heartbeats >= 1 && watches >= 1, "$what: heartbeats $heartbeats, library watches $watches")
    }

    private companion object {
        /**
         * The library watch fires at most a minute after the change; the process starts cold and uploads one photo
         * (measured 2026-11-07, API 36: the process back 10 s after the photo).
         */
        val UPLOAD = 180.seconds

        /** How long the system may take to end a removed task's process. */
        val ENDED = 10.seconds

        /** An upload the backend answered with success: its bytes are stored. */
        val STORED = 200..299
    }
}
