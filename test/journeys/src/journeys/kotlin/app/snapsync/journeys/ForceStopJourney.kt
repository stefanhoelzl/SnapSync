package app.snapsync.journeys

import app.snapsync.client.RigClient
import app.snapsync.control.Verifies
import app.snapsync.journeys.device.AndroidDriver
import app.snapsync.journeys.device.DatedJpeg
import app.snapsync.journeys.device.ProcessShell
import app.snapsync.journeys.run.AndroidRun
import app.snapsync.journeys.run.awaitChannel
import app.snapsync.journeys.run.until
import app.snapsync.levers.WorldEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A force-stop pauses the app's own background work until the next opening, and the opening resumes it (capability
 * `delivery`; decision record `changes/timely-background-receiving`, D8): A — joined by [Journeys], which run first —
 * is force-stopped as a person does it from Settings; a photo is taken while it is stopped; and once A is opened again,
 * that photo is shared. JobScheduler's own record shows how: the heartbeat pending, gone with the force-stop, pending
 * again at the opening.
 *
 * Android's alone: no tool performs a user's swipe-away on an iPhone (Apple's documented behaviour; D8). Run LAST — a
 * force-stop ends the app every journey before it drives. A photo the shell puts in the camera folder is pending, unseen
 * by every app, until MediaStore scans it, so the driver publishes it with a synchronous scan (measured 2026-10-25).
 */
class ForceStopJourney {

    private val appA = address("appA")
    private val backend = address("backend").trimEnd('/')
    private val device = AndroidDriver(ProcessShell, address("adb"), address("androidSerial"))

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A force-quit pauses the app's own background uploading only until the next opening",
        scenario = "Reopening after a force-stop on Android resumes uploading",
    )
    fun aPhotoTakenWhileTheAppWasForceStoppedIsSharedOnceItIsOpened() = runBlocking {
        RigClient(appA).use { a ->
            HttpClient(CIO).use { http ->
                val shown = a.state().ui
                val event = joinedTo(shown) ?: fail("A is not joined: the journeys before this one join it")
                val sharedBefore = sharedCount(shown).done
                val unionBefore = union(http, backend, event).size
                // A re-arms its heartbeat at every wake's end (the foreground's, a scheduled one's, the download
                // session's), and JobScheduler replaces the pending job each time, so a single read can fall in that
                // gap: one read found none and the next, in the same assertion, one (CI 2026-11-08). The read repeats,
                // as the force-stop's below does; on the emulator the arm after the join lands as on main (~340 ms).
                until(REARMED, "A, joined, has its heartbeat pending") { heartbeats() >= 1 }

                // The force-stop lands on an idle A: a job JobScheduler is running or binding when the package stops is
                // restarted by the system, process and all, which re-arms the heartbeat the check below waits to see
                // gone (CI 2026-11-08: the journeys before this one left an uploader arm running). Two reads in a row —
                // the arm's work enqueues its jobs as it goes.
                var idleBefore = false
                until(IDLE, "A has no job running or due before it is force-stopped") {
                    val idle = device.jobsInFlight(AndroidRun.APP) == 0
                    (idle && idleBefore).also { idleBefore = idle }
                }
                device.forceStop(AndroidRun.APP)
                // JobScheduler drops a stopped package's jobs on its own thread, after `am force-stop` has returned: a
                // read straight after it can still find the heartbeat (353 ms behind a contended system_server, CI
                // 2026-10-29), so the read repeats until the cancel has landed.
                until(CANCELLED, "a force-stop leaves no heartbeat pending") { heartbeats() == 0 }

                // Taken today at noon (UTC, the emulator's zone): inside the event, which runs from yesterday.
                val photo = File.createTempFile("force-stop", ".jpg").apply { deleteOnExit() }
                DatedJpeg.write(photo, LocalDate.now(ZoneOffset.UTC).atTime(LocalTime.NOON))
                val row = device.takePhoto(photo, "PXL_force_stop_${System.currentTimeMillis()}.jpg")
                assertTrue(!row.pending && row.takenMillis != null, "the photo is in the library, dated: $row")

                device.start(AndroidRun.ACTIVITY)
                a.awaitChannel(AndroidRun.CHANNEL_UP)
                // The opening's `done`: its own work and the wake's re-arm have ended, so the heartbeat read below
                // follows them. Its entry is named by its class, so this also proves the release's R8 kept the name.
                a.awaitEvent(UPLOAD, "the opening's OnForeground done") { event ->
                    (event as? WorldEvent.Done)?.takeIf { it.entry == "OnForeground" }
                }
                a.awaitScreen(UPLOAD, "the photo taken while A was stopped, shared") {
                    sharedCount(it).reached(sharedBefore + 1)
                }
                assertTrue(heartbeats() >= 1, "the opening put the heartbeat back: ${heartbeats()}")
                val unionAfter = union(http, backend, event).size
                assertTrue(unionAfter > unionBefore, "the photo in the event union: $unionBefore → $unionAfter assets")
            }
        }
    }

    /** The heartbeat, as JobScheduler holds it: A's delayed jobs. */
    private fun heartbeats(): Int = device.delayedJobs(AndroidRun.APP)

    private companion object {
        val UPLOAD = 180.seconds

        /** How long JobScheduler may take to drop a force-stopped package's jobs. */
        val CANCELLED = 10.seconds

        /** How long a joined A's heartbeat may be between JobScheduler's replacements of it. */
        val REARMED = 10.seconds

        /** How long A's jobs may run on after the journeys before this one. */
        val IDLE = 120.seconds
    }
}
