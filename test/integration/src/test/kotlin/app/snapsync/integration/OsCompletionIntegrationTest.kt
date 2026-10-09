package app.snapsync.integration

import app.snapsync.control.Verifies
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The operating system's completion handlers and its expiry, as it plays them (decision record `own-work-per-wake`):
 * the heartbeat task, the transfer sessions' relaunches and the silent push, each over the control protocol, with the
 * OS's expiry delivered by `/os/app/onExpiry`.
 *
 * "Released after the work" is a statement about an instant, so it is read at the release: a receipted entry answers
 * with what the operating system had recorded then (`osAtRelease`). The work a tail does here is the one unit a JVM
 * host can see land — the import of a staged photo (its bytes leave the staging directory) — so most tests start from
 * a photo whose first import failed and whose bytes wait staged.
 *
 * Which of the tail's units ran, and that none starts after a stop, is `TailRunnerTest`'s (`:test:feature`): the JVM
 * root's uploader is the operator, so its top-up and walk move nothing a protocol could read.
 */
class OsCompletionIntegrationTest {

    /** A joined device holding a received photo whose import failed once: its bytes wait in the staging directory. */
    private suspend fun Rig.stagedBacklog(): String {
        val event = createAndJoin()
        foreignDevice("DEV-F", "FQ")
        reconcile()
        device("import/fail-next")
        stage()
        assertTrue(osRecord().stagedFiles > 0, "precondition: the failed import kept its bytes for the retry")
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
        device("relaunch", "scene" to "false")
        val armed = osRecord().heartbeatsScheduled

        val atRelease = osAtRelease(os("app", "onBackgroundTask", HEARTBEAT_TASK))

        assertEquals(0, atRelease.stagedFiles, "released after the tail — the wake's work — imported the backlog")
        awaitOs { it.heartbeatsScheduled == armed + 1 }
        settle()
        assertEquals(0, osRecord().releasedAgain, "the completion is released exactly once")
    }

    @Test
    fun the_operating_systems_expiry_completes_the_wake_at_once_and_a_cut_tail_still_re_arms() = rigTest {
        stagedBacklog()
        device("relaunch", "scene" to "false")
        val armed = osRecord().heartbeatsScheduled
        device("import/suspend-next")

        coroutineScope {
            val wake = async { os("app", "onBackgroundTask", HEARTBEAT_TASK) }
            device("import/await-parked") // the tail's import is in flight
            expire()
            // Released on the operating system's signal — while the import is still parked, not after the work.
            wake.await()
        }
        assertTrue(osRecord().stagedFiles > 0, "the unit in flight had not finished when the handler was released")

        device("import/resume")
        awaitOs { it.heartbeatsScheduled == armed + 1 }
        settle()
        assertEquals(0, osRecord().releasedAgain, "the release after the work does not release it again")
    }

    @Test
    fun an_expiry_after_the_wake_ended_releases_nothing() = rigTest {
        createAndJoin()
        os("app", "onBackgroundTask", HEARTBEAT_TASK)
        val released = osRecord().released

        expire()
        settle()

        assertEquals(released, osRecord().released)
        assertEquals(0, osRecord().releasedAgain)
    }

    @Test
    fun a_wake_whose_time_was_up_before_it_ran_is_released_and_runs_no_tail() = rigTest {
        stagedBacklog()
        device("relaunch", "scene" to "false")
        val staged = osRecord().stagedFiles
        expireNext()

        os("app", "onBackgroundTask", HEARTBEAT_TASK) // an expiry registered late runs at once

        settle()
        assertEquals(staged, osRecord().stagedFiles, "no tail is requested with no time left to run in")
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
        device("relaunch", "scene" to "false")

        val atRelease = osAtRelease(os("app", "onBackgroundTransfers", UPLOAD_SESSION))

        assertEquals(1, atRelease.uploadSessionHandbacks, "the upload session is handed its events")
        assertTrue(atRelease.stagedFiles > 0, "released at the session's drain report, before the tail")
        awaitOs { it.stagedFiles == 0 && it.backgroundTimeHolds.isEmpty() }
    }

    @Test
    fun download_session_events_bring_the_download_session_up_and_not_the_upload_one() = rigTest {
        createAndJoin()
        device("relaunch", "scene" to "false")
        assertFalse(osRecord().downloadSessionUp, "precondition: a background launch has no session up")

        coroutineScope {
            val wake = async { os("app", "onBackgroundTransfers", DOWNLOAD_SESSION) }
            awaitOs { it.downloadSessionUp }
            assertEquals(0, osRecord().uploadSessionHandbacks, "the app uploader's session is not handed them")
            expire() // this session never reports its events drained
            wake.await()
        }
    }

    @Test
    fun a_drain_report_that_never_comes_releases_on_the_operating_systems_expiry() = rigTest {
        createAndJoin()
        device("relaunch", "scene" to "false")

        coroutineScope {
            val wake = async { os("app", "onBackgroundTransfers", DOWNLOAD_SESSION) }
            awaitOs { it.downloadSessionUp }
            osNeverWithin("no clock of the app's own releases it") { it.released > 0 }
            expire()
            wake.await()
        }
        settle()
        val after = osRecord()
        assertEquals(1, after.released, "released exactly once, on the operating system's expiry")
        assertEquals(0, after.releasedAgain)
        assertTrue(after.backgroundTimeHolds.isEmpty(), "and the wake's hold ended with it")
    }

    @Test
    fun a_drain_report_releases_after_the_stagings_it_announced_are_recorded() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        reconcile() // the transfer is in flight in the operating system's session
        device("relaunch", "scene" to "false")
        // A recorded staging requests its own tail at once, and that import would take the bytes out of staging before
        // the release is read. Failing it keeps them, so the count at the release reads the wake's work alone.
        device("import/fail-next")

        val atRelease = coroutineScope {
            val wake = async { os("app", "onBackgroundTransfers", DOWNLOAD_SESSION) }
            awaitOs { it.downloadSessionUp }
            device("downloads/stage", "drained" to "true")
            osAtRelease(wake.await())
        }

        assertTrue(atRelease.stagedFiles > 0, "the stagings the wake delivered were recorded before the release")
    }

    // ---- the silent push's tail -----------------------------------------------------------------------------------

    @Test
    fun a_push_for_the_active_event_re_arms_the_heartbeat() = rigTest {
        val event = createAndJoin()
        device("relaunch", "scene" to "false")
        val armed = osRecord().heartbeatsScheduled

        os("app", "onSilentPush", event)

        awaitOs { it.heartbeatsScheduled == armed + 1 }
        assertFalse(osRecord().screenShown, "a background wake assembles no host")
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun a_push_whose_union_read_fails_still_imports_what_is_staged() = rigTest {
        val event = stagedBacklog()
        device("relaunch", "scene" to "false")
        device("backend/offline") // the push's union read fails fast

        os("app", "onSilentPush", event)

        awaitOs { it.stagedFiles == 0 }
    }

    /** Long enough for a second release, or a tail that should not run, to happen before asserting it did not. */
    private suspend fun settle() = kotlinx.coroutines.delay(200)
}
