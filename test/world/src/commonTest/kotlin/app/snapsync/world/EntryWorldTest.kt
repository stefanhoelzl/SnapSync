package app.snapsync.world

import app.snapsync.model.EventLinkPayload
import app.snapsync.model.Layer
import app.snapsync.model.LinkDelivery
import app.snapsync.model.UiIntent
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.Completion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The app's entry ports — `Lifecycle`, `Links`, `PushNotifications`, `Ui` and `DevControls` — delivering to the REAL
 * composition's handlers over the world (`docs/architecture.md`, "Events arrive through `listen`"; capability
 * `sync-status`, "Each OS wake does its own work, then hands the rest to one opportunistic tail"). These were the
 * `PlatformEntriesContract` clauses while the operating system crossed one inbound port; each entry is an event port
 * now, so what its handler promises is pinned here, and each platform's delivery beside its adapter.
 *
 * Each test fires one entry and asserts what happened in the app behind it, so a crossed wire — an entry routed to the
 * wrong flow — produces the wrong outcome whatever the code is named. A completion is held to **"released exactly
 * once, after the wake's own work, and before the tail"**, and where the process's background time expires, to
 * **"released at once, the tail stopped, the hold ended"**.
 */
class EntryWorldTest {

    // Canonical UUIDs: the link codec refuses anything else, exactly as the edge does.
    private val invited = "11111111-1111-4111-8111-111111111111"
    private val joined = "22222222-2222-4222-8222-222222222222"

    private fun World.unjoined(): World = apply { store.registerEvent(invited, "Anna's Wedding") }

    private suspend fun World.joinedWithAForeignPhoto(): World = apply {
        provision(joined)
        addForeignDevice("DEV-F", joined, listOf(World.foreignAsset("FQ")))
    }

    private fun World.joinGateEventId(): String? =
        (statusHost.container.stateFlow.value.layer as? Layer.JoiningEvent)?.eventId

    private fun World.transientError(): String? = (statusHost.container.stateFlow.value.layer as? Layer.CreateEvent)?.error

    private val World.plannedForeignDownloads: Int get() = download.started.size

    // ---- Links ----------------------------------------------------------------------------------------------------

    @Test
    fun a_link_opens_the_join_gate() = worldTest {
        val w = World(this).unjoined()
        w.links.open(encodeEventUrl(EventLinkPayload(invited)))
        assertTrue(eventually { w.joinGateEventId() == invited }, "the gate opens on the event")
    }

    @Test
    fun a_link_that_decodes_to_nothing_shows_the_error_and_opens_no_gate() = worldTest {
        val w = World(this).unjoined()
        w.links.open("https://example.invalid/not-an-invite")
        assertTrue(eventually { w.transientError() != null }, "a link that decodes to nothing says so")
        assertNull(w.joinGateEventId(), "and opens no gate")
    }

    @Test
    fun a_delivery_that_is_not_a_web_link_opens_nothing_but_still_assembles_the_host() = worldTest {
        val w = World(this).unjoined()
        w.links.deliver(LinkDelivery("onSceneContinueActivity", isWebLink = false, activityType = "handoff", url = null))
        assertTrue(w.hostAssembled(), "a link is a person reaching the app: its handler is host-first")
        settle()
        assertNull(w.joinGateEventId(), "a Handoff activity is not an event link")
        assertNull(w.transientError(), "and is no invalid one either")
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    @Test
    fun foreground_assembles_the_host_reconciles_then_runs_the_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        w.lifecycle.foreground()
        assertTrue(w.hostAssembled(), "the foreground handler is host-first")
        assertTrue(eventually { w.plannedForeignDownloads > 0 }, "the foreground reconcile plans the photo")
        assertTrue(eventually { w.operatorEngine.topUps > 0 }, "and the tail tops up after the own work")
        assertTrue(eventually { w.heartbeatsScheduled > 0 }, "foreground entry re-arms the heartbeat")
        assertTrue(eventually { w.backgroundTimeHolds.value.isEmpty() }, "its hold ends with its tail")
    }

    @Test
    fun the_push_token_is_asked_for_at_launch_and_at_every_foreground() = worldTest {
        val w = World(this)
        assertEquals(1, w.pushNotifications.registrations, "composing the app asks once — a cold start, either kind")
        w.lifecycle.foreground()
        w.lifecycle.foreground()
        assertEquals(3, w.pushNotifications.registrations, "and every activation asks again, to learn a rotated token")
    }

    @Test
    fun background_arms_nothing_and_assembles_no_host() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        w.lifecycle.background()
        settle()
        assertEquals(0, w.heartbeatsScheduled, "leaving the foreground queues no background task")
        assertFalse(w.hostAssembled(), "and builds no screen")
    }

    // ---- PushNotifications ----------------------------------------------------------------------------------------

    @Test
    fun a_push_token_reaches_the_registration_and_assembles_no_host() = worldTest {
        val w = World(this).unjoined()
        w.pushNotifications.deliverToken(TOKEN)
        assertEquals(TOKEN, w.pushTokens.token.value)
        assertFalse(w.hostAssembled(), "a token delivered in a background wake builds no screen")
    }

    @Test
    fun a_token_failure_is_logged_and_changes_nothing() = worldTest {
        val w = World(this).unjoined()
        w.pushNotifications.deliverTokenFailure("no network")
        assertNull(w.pushTokens.token.value)
        assertTrue(w.logs.warnings().any { "no network" in it }, "the platform's reason is in the log")
    }

    @Test
    fun a_silent_push_releases_after_its_own_work_then_runs_the_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = CountingCompletion { w.plannedForeignDownloads > 0 && w.operatorEngine.topUps == 0 }
        w.pushNotifications.deliverMessage(mapOf<Any?, Any?>("eventId" to joined), completion)
        completion.assertReleasedOnceAfterTheWork()
        assertTrue(eventually { w.operatorEngine.topUps > 0 }, "the tail runs after the release")
        assertTrue(eventually { w.backgroundTimeHolds.value.isEmpty() }, "and the push's hold ends with it")
        assertFalse(w.hostAssembled(), "a silent push builds no screen")
    }

    @Test
    fun a_silent_push_for_another_event_joins_no_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = CountingCompletion { true }
        w.pushNotifications.deliverMessage(mapOf<Any?, Any?>("eventId" to "not-the-joined-event"), completion)
        completion.assertReleasedOnceAfterTheWork()
        assertEquals(0, w.plannedForeignDownloads, "the download arm's guard refuses it")
        assertEquals(0, w.operatorEngine.topUps, "and no tail runs for it")
        assertTrue(eventually { w.backgroundTimeHolds.value.isEmpty() }, "its hold ends at once")
    }

    @Test
    fun a_silent_push_without_an_event_still_releases() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = CountingCompletion { true }
        w.pushNotifications.deliverMessage(emptyMap<Any?, Any?>(), completion)
        completion.assertReleasedOnceAfterTheWork()
        assertEquals(0, w.plannedForeignDownloads, "a push naming no event reaches no arm")
        assertEquals(0, w.operatorEngine.topUps, "and joins no tail")
    }

    @Test
    fun background_time_expiry_stops_the_tail_and_ends_the_hold_at_once() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val finishUnit = CompletableDeferred<Unit>()
        w.operatorEngine.nextUnitGate = finishUnit
        val completion = CountingCompletion { w.plannedForeignDownloads > 0 }
        w.pushNotifications.deliverMessage(mapOf<Any?, Any?>("eventId" to joined), completion)
        completion.assertReleasedOnceAfterTheWork()
        assertTrue(eventually { w.operatorEngine.topUps == 1 }, "the tail's top-up is in flight")
        w.expireBackgroundTime()
        // At once — while the unit is still in flight: Apple's recipe, never the watchdog's.
        assertTrue(eventually { w.backgroundTimeHolds.value.isEmpty() }, "the hold is ended without awaiting the unit")
        finishUnit.complete(Unit)
        settle()
        assertEquals(0, w.operatorEngine.walks, "the unit in flight completed, and no further unit started")
    }

    // ---- Ui -------------------------------------------------------------------------------------------------------

    @Test
    fun a_live_screen_assembles_the_host_and_is_shown_its_state_before_its_first_frame() = worldTest {
        val w = World(this)
        assertNull(w.ui.shown.value, "nothing is shown before a screen exists")
        w.ui.live()
        assertNotNull(w.ui.shown.value, "the live handler shows the current state synchronously")
        w.ui.live()
        assertNotNull(w.ui.shown.value, "and is idempotent — a rebuilt screen is shown it again")
    }

    @Test
    fun an_intent_reaches_the_status_container() = worldTest {
        val w = World(this).unjoined()
        w.ui.live()
        w.ui.tap(UiIntent.ReportBugOpen)
        assertTrue(eventually { w.ui.shown.value?.overlays?.reportingBug == true }, "the container reduced the tap")
    }

    // ---- DevControls ----------------------------------------------------------------------------------------------

    @Test
    fun the_dev_reset_voids_the_membership() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        w.devControls.reset()
        assertNull(w.config.config.value, "the reset clears the membership locally")
    }

    /**
     * An operating-system completion handler as a test hands one over: it counts its releases and records whether
     * [workDone] held at the first — "released after the work" is a statement about that instant.
     */
    private class CountingCompletion(private val workDone: () -> Boolean) : Completion {
        private var releases = 0
        private var doneAtRelease: Boolean? = null

        override fun complete() {
            if (doneAtRelease == null) doneAtRelease = workDone()
            releases++
        }

        override fun onExpired(action: () -> Unit) = Unit

        suspend fun assertReleasedOnceAfterTheWork() {
            assertTrue(eventually { releases > 0 }, "the completion is released")
            // Give a second release the chance to happen before asserting there was none.
            settle()
            assertEquals(1, releases, "the completion is released exactly once")
            assertEquals(true, doneAtRelease, "the completion is released after the wake's own work, and before its tail")
        }
    }

    private companion object {
        const val TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    }
}

/** Polls [condition] until it holds or ten seconds pass; `true` if it held. */
private suspend fun eventually(condition: () -> Boolean): Boolean =
    withTimeoutOrNull(10.seconds) { while (!condition()) delay(10.milliseconds) } != null

/** A short pause, for asserting that something did NOT happen after a thing that did. */
private suspend fun settle() = delay(200.milliseconds)
