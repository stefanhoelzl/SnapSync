package app.snapsync.integration

import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app's entry points, as the operating system delivers them (`docs/architecture.md`, "Events arrive through
 * `listen`"; capability `sync-status`, "Each OS wake does its own work, then hands the rest to one opportunistic
 * tail"): each test delivers one entry over the control protocol and asserts what the person and the operating system
 * can see behind it — the screen, the transfers the app started, the heartbeat it asked for, the background time it
 * held, the screen it built or did not — so a crossed wire produces the wrong outcome whatever the code is named.
 *
 * A background launch (`relaunch?scene=false`) is the operating system starting the process for a wake with no scene:
 * the tests about what a wake must NOT build start there, and read only the operating system's record until a
 * foreground brings a screen up.
 */
class EntryIntegrationTest {

    // ---- Links ----------------------------------------------------------------------------------------------------

    @Test
    fun a_link_opens_the_join_gate() = rigTest {
        val event = registerEvent()
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.eventId == event }
    }

    @Test
    fun a_link_that_decodes_to_nothing_shows_the_error_and_opens_no_gate() = rigTest {
        openLink("https://example.invalid/not-an-invite")
        val shown = awaitState { (it.ui.layer as? Layer.CreateEvent)?.error != null }
        assertFalse(shown.ui.layer is Layer.JoiningEvent, "a link that decodes to nothing opens no gate")
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    @Test
    fun foreground_builds_the_screen_reconciles_then_runs_the_tail_and_re_arms() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        device("relaunch", "scene" to "false")
        val before = osRecord()
        assertFalse(before.screenShown, "precondition: a background launch builds no screen")

        foreground() // awaited: the foreground's background time is held until its tail is done

        val after = osRecord()
        assertTrue(after.screenShown, "the foreground handler assembles the host first")
        assertTrue(after.downloadsStarted > before.downloadsStarted, "the foreground reconcile starts the photo's transfer")
        assertTrue(after.heartbeatsScheduled > before.heartbeatsScheduled, "foreground entry re-arms the heartbeat")
    }

    @Test
    fun the_push_token_is_asked_for_at_every_launch_and_every_foreground() = rigTest {
        val launched = osRecord().pushRegistrations
        assertTrue(launched >= 1, "composing the app asks once")

        foreground()
        foreground()
        assertEquals(launched + 2, osRecord().pushRegistrations, "every activation asks again, to learn a rotated token")

        device("relaunch", "scene" to "false")
        assertEquals(launched + 3, osRecord().pushRegistrations, "a cold start asks, a background one included")
    }

    @Test
    fun background_arms_nothing_and_builds_no_screen() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        device("relaunch", "scene" to "false")
        val armed = osRecord().heartbeatsScheduled

        os("app", "onBackground")

        osNeverWithin("leaving the foreground queued a background task or built a screen") {
            it.heartbeatsScheduled != armed || it.screenShown
        }
    }

    // ---- Wake ------------------------------------------------------------------------------------------------------

    @Test
    fun both_heartbeat_tasks_wake_the_app_and_an_unknown_task_does_not() = rigTest {
        createAndJoin()
        device("relaunch", "scene" to "false")
        val before = osRecord().handed

        os("app", "onBackgroundTask", HEARTBEAT_TASK)
        os("app", "onBackgroundTask", IDLE_HEARTBEAT_TASK)
        os("app", "onBackgroundTask", "app.example.not-registered")

        val after = osRecord()
        assertEquals(before + 2, after.handed, "each heartbeat task hands the app its completion; an unknown one none")
        assertEquals(after.handed, after.released, "and every handed completion is released")
    }

    // ---- PushNotifications ----------------------------------------------------------------------------------------

    @Test
    fun a_push_token_in_a_background_wake_is_registered_and_builds_no_screen() = rigTest {
        createAndJoin()
        device("relaunch", "scene" to "false")

        os("app", "onPushToken", TOKEN)

        eventually(read = { deviceConfig()?.first }) { it == TOKEN }
        assertFalse(osRecord().screenShown, "a token delivered in a background wake builds no screen")
    }

    @Test
    fun a_token_failure_is_logged_and_registers_nothing() = rigTest {
        createAndJoin()
        os("app", "onPushTokenFailure", "no network")

        eventually(read = { client.logs() }) { "no network" in it }
        assertNull(deviceConfig(), "nothing is registered")
    }

    @Test
    fun a_silent_push_releases_after_its_own_work_then_runs_the_tail_and_builds_no_screen() = rigTest {
        val event = createAndJoin()
        foreignDevice("DEV-F", "FQ")
        device("relaunch", "scene" to "false")

        val atRelease = osAtRelease(os("app", "onSilentPush", event))

        assertTrue(atRelease.downloadsStarted > 0, "released after its own work: the union read and the enqueue")
        awaitOs { r -> r.backgroundTimeHolds.none { it == "onSilentPush" } }
        val after = osRecord()
        assertEquals(0, after.releasedAgain, "the completion is released exactly once")
        assertFalse(after.screenShown, "a silent push builds no screen")
    }

    @Test
    fun a_silent_push_for_another_event_reaches_no_arm_and_joins_no_tail() = rigTest {
        createAndJoin()
        // A cold background launch first, so the join's own reconcile — still running in the process it ends — cannot
        // start the foreign photo's transfer; the push below is the only thing the new process is asked to do.
        device("relaunch", "scene" to "false")
        foreignDevice("DEV-F", "FQ")
        val armed = osRecord().heartbeatsScheduled

        val atRelease = osAtRelease(os("app", "onSilentPush", OTHER_EVENT))

        assertEquals(0, atRelease.downloadsStarted, "the download arm's guard refuses it")
        awaitOs { r -> r.backgroundTimeHolds.none { it == "onSilentPush" } }
        assertEquals(armed, osRecord().heartbeatsScheduled, "and no tail runs for it, so nothing re-arms")
    }

    @Test
    fun a_silent_push_naming_no_event_still_releases() = rigTest {
        createAndJoin()
        // A cold background launch first, so the join's own reconcile — still running in the process it ends — cannot
        // start the foreign photo's transfer; the push below is the only thing the new process is asked to do.
        device("relaunch", "scene" to "false")
        foreignDevice("DEV-F", "FQ")

        val atRelease = osAtRelease(os("app", "onSilentPush"))

        assertEquals(0, atRelease.downloadsStarted, "a push naming no event reaches no arm")
        assertEquals(0, osRecord().releasedAgain)
    }

    @Test
    fun the_operating_systems_expiry_ends_every_hold_at_once_without_awaiting_the_work_in_flight() = rigTest {
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        reconcile()
        device("import/suspend-next")
        stage(wait = false) // the staging requests the tail, whose import parks
        device("import/await-parked")
        assertTrue(osRecord().backgroundTimeHolds.any { it.startsWith("tail(") }, "precondition: the tail holds time")

        expire()

        // At once — while the import is still parked: Apple's recipe, never the watchdog's.
        awaitOs { it.backgroundTimeHolds.isEmpty() }
        device("import/resume")
    }

    // ---- DevControls ----------------------------------------------------------------------------------------------

    @Test
    fun the_dev_reset_voids_the_membership() = rigTest {
        createAndJoin()
        device("reset")
        awaitState { !it.ready.configResolved }
    }

    // ---- The selection observer -----------------------------------------------------------------------------------

    /**
     * **The partial grant's selection observer opens on every start** (capability `background-upload`, "Photos upload
     * without the app being opened"; decision record `changes/timely-background-receiving`, D6): a background start
     * that builds no screen still reads the selection, so the app's own wake uploads what waits — which a start that
     * never learned its selection withheld, backlog included.
     */
    @Test
    fun a_background_start_reads_the_selection_and_shares_what_waits() = rigTest {
        permission("LIMITED")
        createAndJoin()
        addPhoto("A")
        // The member picks A while the app's uploader is held back: selected, nothing created — the work a cold start
        // inherits. (Under a partial grant the extension withholds by its own admission; only the app's uploader runs.)
        device("uploaders", "app" to "off")
        device("selection/change", "assets" to "A")
        eventually(read = { gallery().policy?.assets?.mapTo(mutableSetOf()) { it.assetId } }) { it == setOf("A") }
        // Back on, with A never discovered: under a partial grant only the selection's own read discovers.
        device("uploaders", "app" to "on")
        assertEquals(0, appUploads().created, "precondition: nothing was created")

        device("relaunch", "scene" to "false")
        assertTrue(osRecord().selectionObserved, "composition opens the observer, a background start included")

        // The start's own baseline read discovers A and creates it: a start that never read its selection withholds.
        assertEquals(listOf(primaryKey("A")), awaitAppUploads(1).live)
        assertFalse(osRecord().screenShown, "and the start built no screen")
    }

    private companion object {
        const val TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val OTHER_EVENT = "33333333-3333-4333-8333-333333333333"
    }
}

/**
 * Assert [condition] never holds of the operating system's record for [window] — the only way to observe that
 * something does NOT happen. The record, not `/device/state`: reading the screen would assemble the host a background
 * launch never built.
 */
suspend fun Rig.osNeverWithin(what: String, window: kotlin.time.Duration = 500.milliseconds, condition: (OsRecord) -> Boolean) {
    val deadline = kotlin.time.TimeSource.Monotonic.markNow() + window
    while (deadline.hasNotPassedNow()) {
        val r = osRecord()
        assertFalse(condition(r), "$what: $r")
        kotlinx.coroutines.delay(50.milliseconds)
    }
}
