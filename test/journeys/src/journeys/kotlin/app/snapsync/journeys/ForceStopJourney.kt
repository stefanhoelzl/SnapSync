package app.snapsync.journeys

import app.snapsync.client.RigClient
import app.snapsync.control.Verifies
import app.snapsync.journeys.device.AndroidDriver
import app.snapsync.journeys.device.DatedJpeg
import app.snapsync.journeys.device.ProcessShell
import app.snapsync.journeys.run.AndroidRun
import app.snapsync.journeys.run.awaitChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
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
                assertTrue(heartbeats() >= 1, "A, joined, has its heartbeat pending: ${heartbeats()}")

                device.forceStop(AndroidRun.APP)
                assertEquals(0, heartbeats(), "a force-stop leaves no heartbeat pending")

                // Taken today at noon (UTC, the emulator's zone): inside the event, which runs from yesterday.
                val photo = File.createTempFile("force-stop", ".jpg").apply { deleteOnExit() }
                DatedJpeg.write(photo, LocalDate.now(ZoneOffset.UTC).atTime(LocalTime.NOON))
                val row = device.takePhoto(photo, "PXL_force_stop_${System.currentTimeMillis()}.jpg")
                assertTrue(!row.pending && row.takenMillis != null, "the photo is in the library, dated: $row")

                device.start(AndroidRun.ACTIVITY)
                a.awaitChannel(AndroidRun.CHANNEL_UP)
                // The opening's own `done` (phase 33) is the better wait for the heartbeat read below, but not yet usable
                // here: an entry is named by its class, which R8 renames in this release build (`done entry=gt4`).
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
    }
}
