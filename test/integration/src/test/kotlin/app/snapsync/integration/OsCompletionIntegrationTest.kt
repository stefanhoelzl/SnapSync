package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.Completions
import app.snapsync.levers.WorldEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The operating system's completion handlers and its expiry, as it plays them (decision record `own-work-per-wake`):
 * the heartbeat task, the transfer sessions' relaunches and the silent push, each over the control protocol, with the
 * OS's expiry delivered by `played-os`'s `expire`.
 *
 * "Released after the work" is a statement about an instant, so it is read at the release: a delivery awaited to its
 * release answers with what the device held then (`Released.atRelease`). The work a tail does here is the one unit a JVM
 * host can see land — the import of a staged photo (its bytes leave the staging directory) — so most tests start from
 * a photo whose first import failed and whose bytes wait staged.
 *
 * Which of a wake's units ran, and that none starts after a stop, is the units' own tests (`:test:feature`): the JVM
 * root's uploader is the operator, so its top-up and walk move nothing a protocol could read.
 */
class OsCompletionIntegrationTest {

    /** A joined device holding a received photo whose import failed once: its bytes wait in the staging directory. */
    private suspend fun Rig.stagedBacklog(): String {
        val event = createAndJoin()
        foreignDevice(DEV_F, "FQ")
        reconcile()
        awaitDownloadQueued()
        library.failNextImport()
        stage()
        assertTrue(files.stagedFiles() > 0, "precondition: the failed import kept its bytes for the retry")
        return event
    }

    // ---- the heartbeat task --------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun a_heartbeat_wake_runs_the_tail_then_completes_once_and_re_arms() = rigTest {
        stagedBacklog()
        host.relaunch(scene = false)
        val armed = wake.wakes().heartbeatsScheduled

        val atRelease = checkNotNull(os.backgroundTask(HEARTBEAT_TASK, awaitRelease = true)).atRelease

        assertEquals(0, atRelease.stagedFiles, "released after the tail — the wake's work — imported the backlog")
        eventually(read = { wake.wakes().heartbeatsScheduled }) { it == armed + 1 }
        settle()
        assertEquals(0, playedOs.completions().releasedAgain, "the completion is released exactly once")
    }

    @Test
    fun the_operating_systems_expiry_completes_the_wake_at_once_and_a_cut_tail_still_re_arms() = rigTest {
        stagedBacklog()
        host.relaunch(scene = false)
        val armed = wake.wakes().heartbeatsScheduled
        library.suspendNextImport()

        coroutineScope {
            val delivery = async { os.backgroundTask(HEARTBEAT_TASK, awaitRelease = true) }
            awaitImportParked() // the tail's import is in flight
            playedOs.expire()
            // Released on the operating system's signal — while the import is still parked, not after the work.
            delivery.await()
        }
        assertTrue(files.stagedFiles() > 0, "the unit in flight had not finished when the handler was released")

        library.resumeImport()
        eventually(read = { wake.wakes().heartbeatsScheduled }) { it == armed + 1 }
        settle()
        assertEquals(0, playedOs.completions().releasedAgain, "the release after the work does not release it again")
    }

    @Test
    fun an_expiry_after_the_wake_ended_releases_nothing() = rigTest {
        createAndJoin()
        os.backgroundTask(HEARTBEAT_TASK, awaitRelease = true)
        val released = playedOs.completions().released

        playedOs.expire()
        settle()

        assertEquals(released, playedOs.completions().released)
        assertEquals(0, playedOs.completions().releasedAgain)
    }

    @Test
    fun a_wake_whose_time_was_up_before_it_ran_is_released_and_runs_no_tail() = rigTest {
        stagedBacklog()
        host.relaunch(scene = false)
        val staged = files.stagedFiles()
        playedOs.expireNext()

        os.backgroundTask(HEARTBEAT_TASK, awaitRelease = true) // an expiry registered late runs at once

        settle()
        assertEquals(staged, files.stagedFiles(), "no tail is requested with no time left to run in")
    }

    // ---- the transfer sessions' relaunches ------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun upload_session_events_release_at_the_drain_then_run_the_tail() = rigTest {
        stagedBacklog()
        host.relaunch(scene = false)

        val atRelease = checkNotNull(os.backgroundTransfers(UPLOAD_SESSION, awaitRelease = true)).atRelease

        assertEquals(1, atRelease.uploadSessionHandbacks, "the upload session is handed its events")
        assertTrue(checkNotNull(atRelease.stagedFiles) > 0, "released at the session's drain report, before the tail")
        awaitStaged(0)
        awaitNoHolds()
    }

    @Test
    fun download_session_events_bring_the_download_session_up_and_not_the_upload_one() = rigTest {
        createAndJoin()
        host.relaunch(scene = false)
        assertFalse(downloadSession.downloads().up, "precondition: a background launch has no session up")

        coroutineScope {
            val delivery = async { os.backgroundTransfers(DOWNLOAD_SESSION, awaitRelease = true) }
            awaitDownloadSessionUp()
            assertEquals(0, uploadSession.session().handbacks, "the app uploader's session is not handed them")
            playedOs.expire() // this session never reports its events drained
            delivery.await()
        }
    }

    @Test
    fun a_drain_report_that_never_comes_releases_on_the_operating_systems_expiry() = rigTest {
        createAndJoin()
        host.relaunch(scene = false)

        coroutineScope {
            val delivery = async { os.backgroundTransfers(DOWNLOAD_SESSION, awaitRelease = true) }
            awaitDownloadSessionUp()
            completionsNeverWithin("no clock of the app's own releases it") { it.released > 0 }
            playedOs.expire()
            delivery.await()
        }
        settle()
        val after = playedOs.completions()
        assertEquals(1, after.released, "released exactly once, on the operating system's expiry")
        assertEquals(0, after.releasedAgain)
        assertTrue(backgroundTime.holds().isEmpty(), "and the wake's hold ended with it")
    }

    @Test
    fun a_drain_report_releases_after_the_stagings_it_announced_are_recorded() = rigTest {
        createAndJoin()
        foreignDevice(DEV_F, "FQ")
        reconcile() // the transfer is in flight in the operating system's session
        host.relaunch(scene = false)
        // A recorded staging requests its own tail at once, and that import would take the bytes out of staging before
        // the release is read. Failing it keeps them, so the count at the release reads the wake's work alone.
        library.failNextImport()
        val armed = wake.wakes().heartbeatsScheduled

        val atRelease = coroutineScope {
            val delivery = async { os.backgroundTransfers(DOWNLOAD_SESSION, awaitRelease = true) }
            awaitDownloadSessionUp()
            downloadSession.finish()
            downloadSession.reportEventsDrained()
            checkNotNull(delivery.await()).atRelease
        }

        assertTrue(
            checkNotNull(atRelease.stagedFiles) > 0,
            "the stagings the wake delivered were recorded before the release",
        )
        // The wake's work follows the release, under the process's background time: awaited to its end, or the test
        // could end before it ever started.
        eventually(read = { wake.wakes().heartbeatsScheduled }) { it > armed }
        awaitNoHolds()
    }

    // ---- the silent push's tail -----------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun a_push_for_the_active_event_re_arms_the_heartbeat() = behaviour {
        val event = given { joinedEvent().also { backgroundLaunch() } }
        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.wake == "heartbeat" }
            // A background wake assembles no host.
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun a_push_whose_union_read_fails_still_imports_what_is_staged() = rigTest {
        val event = stagedBacklog()
        host.relaunch(scene = false)
        backend.offline(true) // the push's union read fails fast

        os.silentPush(event, awaitRelease = true)

        awaitStaged(0)
    }

    /** Long enough for a second release, or a tail that should not run, to happen before asserting it did not. */
    private suspend fun settle() = kotlinx.coroutines.delay(200)

    /** Assert [condition] never holds of the operating system's completion record for [window]. */
    private suspend fun Rig.completionsNeverWithin(
        what: String,
        window: kotlin.time.Duration = 500.milliseconds,
        condition: (Completions) -> Boolean,
    ) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + window
        while (deadline.hasNotPassedNow()) {
            val completions = playedOs.completions()
            assertFalse(condition(completions), "$what: $completions")
            kotlinx.coroutines.delay(50.milliseconds)
        }
    }
}
