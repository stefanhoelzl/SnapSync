package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's entry points, as the operating system delivers them (`docs/architecture.md`, "Events arrive through
 * `listen`"): each test delivers one entry over the control protocol and asserts what the person and the operating
 * system can see behind it — the screen, the transfers the app started, the heartbeat it asked for, the background
 * time it held, the screen it built or did not — so a crossed wire produces the wrong outcome whatever the code is
 * named.
 *
 * A background launch (`relaunch?scene=false`) is the operating system starting the process for a wake with no scene:
 * the tests about what a wake must NOT build start there, and read only the operating system's record until a
 * foreground brings a screen up.
 */
class EntryIntegrationTest {

    // ---- Links ----------------------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "invite-link", requirement = "An invite opens the app on its join screen")
    fun a_link_opens_the_join_gate() = behaviour {
        val (event, link) = given { registeredEvent().let { it to inviteFor(it) } }
        whenever { openLink(link) }
        then { screen<Layer.JoiningEvent> { it.eventId == event } }
    }

    @Test
    @Verifies(spec = "invite-link", requirement = "A damaged invite is reported and changes nothing")
    fun a_link_that_decodes_to_nothing_shows_the_error_and_opens_no_gate() = behaviour {
        whenever { openLink("https://example.invalid/not-an-invite") }
        then {
            screen<Layer.CreateEvent> { it.error != null }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    // Waits on the download the reconcile starts, which only the transfer sessions' read sees until their events reach
    // the world stream.
    @Test
    fun foreground_builds_the_screen_reconciles_then_runs_the_tail_and_re_arms() = rigTest {
        createAndJoin()
        foreignDevice(DEV_F, "FQ")
        device("relaunch", "scene" to "false")
        val before = osRecord()
        val armed = wake.wakes().heartbeatsScheduled
        assertFalse(before.screenShown, "precondition: a background launch builds no screen")

        foreground() // awaited: the foreground's background time is held until its tail is done

        val after = osRecord()
        assertTrue(after.screenShown, "the foreground handler assembles the host first")
        assertTrue(
            after.downloadsStarted > before.downloadsStarted,
            "the foreground reconcile starts the photo's transfer",
        )
        assertTrue(wake.wakes().heartbeatsScheduled > armed, "foreground entry re-arms the heartbeat")
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
    fun every_foreground_asks_for_the_push_token_again() = behaviour {
        // Every activation asks again, to learn a rotated token.
        whenever { os.foreground() }
        then { times(1) { event<WorldEvent.PushRegistered>() } }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
    fun a_background_cold_start_asks_for_the_push_token() = behaviour {
        whenever { device("relaunch", "scene" to "false") }
        then { times(1) { event<WorldEvent.PushRegistered>() } }
    }

    @Test
    fun background_arms_nothing_and_builds_no_screen() = behaviour {
        given {
            joinedEvent()
            fellowMember(DEV_F, "FQ")
            backgroundLaunch()
        }
        // Leaving the foreground queues no background task and builds no screen.
        whenever { os.background() }
        then {
            never<WorldEvent.WakeScheduled>()
            never<WorldEvent.UiChanged>()
        }
    }

    // ---- Wake ------------------------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun both_heartbeat_tasks_wake_the_app_and_an_unknown_task_does_not() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
        }
        // Each heartbeat task hands the app its completion, which it releases once; an unknown one hands it none.
        whenever {
            heartbeat()
            os.backgroundTask(IDLE_HEARTBEAT_TASK)
            os.backgroundTask("app.example.not-registered")
        }
        then {
            times(2) { event<WorldEvent.CompletionReleased> { !it.again } }
            never<WorldEvent.CompletionReleased> { it.again }
            never<WorldEvent.UiChanged>()
        }
    }

    // ---- PushNotifications ----------------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun a_push_token_in_a_background_wake_is_registered_and_builds_no_screen() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
        }
        whenever { os.pushToken(TOKEN) }
        then {
            event<WorldEvent.DeviceRegistered> { it.token == TOKEN }
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "No wake arrives",
    )
    fun a_token_failure_registers_nothing() = behaviour {
        given { joinedEvent() }
        whenever { os.pushTokenFailure("no network") }
        then { never<WorldEvent.DeviceRegistered>() }
    }

    @Test
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun a_silent_push_releases_after_its_own_work_then_runs_the_tail_and_builds_no_screen() = behaviour {
        val event = given {
            joinedEvent().also {
                fellowMember(DEV_F, "FQ")
                backgroundLaunch()
            }
        }
        whenever { os.silentPush(event) }
        then {
            // Released after its own work — the union read — and then the tail, under the push's own background time.
            inOrder {
                event<WorldEvent.BackendReceived> { it.operation == UNION }
                event<WorldEvent.CompletionReleased>()
                event<WorldEvent.HoldEnded> { it.name == SILENT_PUSH_HOLD }
            }
            never<WorldEvent.CompletionReleased> { it.again }
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Only new photos are announced by a silent wake, and never only by it")
    fun a_silent_push_for_another_event_reaches_no_arm_and_joins_no_tail() = behaviour {
        given {
            joinedEvent()
            // A cold background launch first, so the join's own reconcile — still running in the process it ends —
            // cannot read the union; the push below is the only thing the new process is asked to do.
            backgroundLaunch()
            fellowMember(DEV_F, "FQ")
        }
        whenever { os.silentPush(OTHER_EVENT) }
        then {
            event<WorldEvent.CompletionReleased>()
            // The download arm's guard refuses it, and no tail runs for it, so nothing re-arms.
            never<WorldEvent.BackendReceived> { it.operation == UNION }
            never<WorldEvent.WakeScheduled>()
        }
    }

    @Test
    fun a_silent_push_naming_no_event_still_releases() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
            fellowMember(DEV_F, "FQ")
        }
        whenever { os.silentPush() }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            // It reaches no arm.
            never<WorldEvent.BackendReceived> { it.operation == UNION }
            never<WorldEvent.CompletionReleased> { it.again }
        }
    }

    // Parks a staged photo's import, which only the photo library's own reads see until its events reach the world
    // stream.
    @Test
    fun the_operating_systems_expiry_ends_every_hold_at_once_without_awaiting_the_work_in_flight() = rigTest {
        createAndJoin()
        foreignDevice(DEV_F, "FQ")
        reconcile()
        awaitDownloadQueued()
        library.suspendNextImport()
        stage(wait = false) // the staging requests the tail, whose import parks
        awaitImportParked()
        assertTrue(backgroundTime.holds().any { it == "OnDownloadStaged" }, "precondition: the import holds time")

        playedOs.expire()

        // At once — while the import is still parked: Apple's recipe, never the watchdog's.
        awaitNoHolds()
        library.resumeImport()
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
     * **The partial grant's selection observer opens on every start** (decision record
     * `changes/timely-background-receiving`, D6): a background start that builds no screen still reads the selection,
     * so the app's own wake uploads what waits — which a start that never learned its selection withheld, backlog
     * included.
     */
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Uploads work under limited access",
    )
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun a_background_start_reads_the_selection_and_shares_what_waits() = rigTest {
        permission(PhotoGrant.LIMITED)
        createAndJoin()
        addPhoto("A")
        // The member picks A while the app's uploader is held back: selected, nothing created — the work a cold start
        // inherits. (Under a partial grant the extension withholds by its own admission; only the app's uploader runs.)
        device("uploaders", "app" to "off")
        library.changeSelection(listOf("A"))
        eventually(read = { gallery().policy?.assets?.mapTo(mutableSetOf()) { it.assetId } }) { it == setOf("A") }
        // The selection change's own tail holds background time until it ends; it must end while the uploader is off,
        // or its top-up — running after the switch below — creates what this precondition says was not created.
        awaitNoHolds()
        // Back on, with A never discovered: under a partial grant only the selection's own read discovers.
        device("uploaders", "app" to "on")
        assertEquals(0, appUploads().created, "precondition: nothing was created")

        device("relaunch", "scene" to "false")
        assertTrue(osRecord().selectionObserved, "composition opens the observer, a background start included")

        // The start's own baseline read discovers A and creates it: a start that never read its selection withholds.
        assertEquals(listOf(primaryKey("A")), awaitAppUploads(1).live)
        assertFalse(osRecord().screenShown, "and the start built no screen")
    }

    @Test
    fun an_upload_that_ends_fills_its_freed_slot() = behaviour {
        val first = given {
            joinedEvent()
            sessionFull()
        }

        whenever { uploadSession.complete(first) }

        // The freed slot is filled from the backlog, by work the end starts and does not await: one end, one slot.
        then { times(1) { event<WorldEvent.SessionTransferCreated>() } }
    }

    @Test
    fun an_upload_that_ends_while_the_app_may_not_create_fills_no_slot() = behaviour {
        given {
            joinedEvent()
            sessionFull()
            step("the app's uploader off", act = { device("uploaders", "app" to "off") }, until = { settled { true } })
        }

        whenever { uploadSession.complete() }

        then { never<WorldEvent.SessionTransferCreated>() }
    }

    /**
     * More photos than the app's upload session holds live at once, and the app in front: the session full, the rest
     * waiting. Answers the key of the first transfer created.
     */
    private suspend fun Given.sessionFull(): String {
        lateinit var first: Seen<WorldEvent.SessionTransferCreated>
        step(
            "the session full, two photos waiting",
            act = {
                repeat(SESSION_CAP + 2) { takePhoto("P$it") }
                os.foreground()
            },
            until = {
                first = event<WorldEvent.SessionTransferCreated>()
                times(SESSION_CAP) { event<WorldEvent.SessionTransferCreated>() }
            },
        )
        return first.value.key
    }

    private companion object {
        /** The live transfers the app uploader's session holds at once. */
        const val SESSION_CAP = 4
        const val TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
        const val OTHER_EVENT = "33333333-3333-4333-8333-333333333333"

        /** The union read: the device API's `eventFiles`. */
        const val UNION = "eventFiles"
    }
}
