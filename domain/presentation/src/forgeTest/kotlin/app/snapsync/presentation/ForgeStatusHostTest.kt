package app.snapsync.presentation

import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventStart
import app.snapsync.model.eventEnd
import app.snapsync.model.deletesAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import app.snapsync.model.EventDetails
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.Overlays
import app.snapsync.model.RangeForm
import app.snapsync.model.SyncHealth
import app.snapsync.model.UiState

/** The shell binds the system clock/zone; the test binds a fixed instant (step 9: no system default). */
private fun fixedFormatter() = CutoffFormatter(
    now = { Instant.parse(NOW) },
    zone = TimeZone.UTC,
)

/** Before [EVENT_START], which is what makes `nowAvailable` false below. */
private const val NOW = "2026-07-09T12:00:00Z"

/**
 * [EVENT_START] and [EVENT_END] as the wall clock reads them under the fixed UTC zone — the window the
 * gate must resolve its range against. Written out rather than converted through the formatter under
 * test: an expectation derived by the code it checks asserts nothing.
 */
private val WINDOW_START = LocalDateTime.parse("2026-07-20T18:00")
private val WINDOW_END = LocalDateTime.parse("2026-07-25T18:00")

/**
 * The forge factory behind the forge binary (capability `sync-status`). Each recognized state
 * must reduce — through the REAL [StatusContainerHost], from forged sources only — to the intended
 * frame, with no backend, attestation token, or photo-library access (the factory constructs neither,
 * so a passing test proves their absence). An unrecognized name must be rejected so the shell can fall
 * back to the live stack.
 *
 * WHY THIS IS ASSERTED AT ALL, given the captures have no automated check (`docs/deployment.md`): what
 * is checked here is not the picture but the property the picture rests on — that a preset forges the
 * container's INPUTS and lets the real reduction produce the frame. A preset that fabricated a frame
 * directly would still screenshot beautifully, and would be depicting a state the app cannot be in.
 *
 * Read the frame out of `container.stateFlow`, never off the host: what the screen shows is [UiState] and
 * nothing beside it, so an assertion on a host property could pass while the screen rendered something
 * else. That is the rule `UiState`'s own KDoc exists to state, and the change that established it is the
 * one this test had to be rewritten for.
 */
class ForgeStatusHostTest {

    @Test
    fun `create forges the create landing screen`() = runTest {
        val host = assertNotNull(forgeStatusHost("create", backgroundScope, fixedFormatter()))
        // Config absent + idle creation → the create input, exactly as production reduces it.
        assertEquals(UiState(Layer.CreateEvent(error = null)), host.container.stateFlow.value)
    }

    @Test
    fun `joining forges the real join confirmation gate from an interactive invite link`() = runTest {
        val host = assertNotNull(forgeStatusHost("joining", backgroundScope, fixedFormatter()))
        // The factory dispatched the invite link as an Orbit intent, which runs on Orbit's own
        // (real) dispatcher rather than the test scheduler — so AWAIT the gate instead of advancing
        // virtual time. The gate reduced ITSELF from the forged inputs: an interactive event link + a
        // Found details load, with config absent (a first join, not a switch) and permission granted
        // (so readyOrExplain picks Ready rather than the access explainer).
        val layer = host.container.stateFlow
            .first { it.layer is Layer.JoiningEvent }
            .layer as Layer.JoiningEvent

        assertEquals(EVENT_ID, layer.eventId)
        // Details loaded AND the confirm offered. `Detailed` is what makes that pair inseparable — a step
        // that renders the event's facts cannot be constructed without them.
        assertEquals(
            JoinPhase.Detailed(
                event = EventDetails(
                    name = EVENT_NAME,
                    startsAt = eventStart(EVENT_START),
                    endsAt = eventEnd(EVENT_END),
                    deletesAt = deletesAt(EVENT_DELETES),
                ),
                step = JoinPhase.Detailed.Step.Ready,
            ),
            layer.phase,
        )

        // The range row the surface renders, RESOLVED by the reduction from the loaded window — not
        // forged. The default form takes the event's own start and end, so the chosen bounds are the
        // window's; asserting them is what proves the gate seeded itself from the details it fetched
        // rather than from `now` or from nothing.
        val range = assertNotNull(layer.range, "a loaded gate renders a range row, so it must resolve one")
        assertEquals(WINDOW_START, range.windowStart)
        assertEquals(WINDOW_END, range.windowEnd)
        assertEquals(range.windowStart, range.from, "the EVENT_START preset resolves to the window's start")
        assertEquals(range.windowEnd, range.until, "the EVENT_END preset resolves to the window's end")
        assertEquals(RangeForm(), layer.form, "nothing has been edited, so the form is at its defaults")
        // Both directions on → there is something to commit.
        assertTrue(range.commitEnabled)
        // "Now" is offered only inside the window, and the fixed clock sits before the event begins.
        assertFalse(range.nowAvailable)
    }

    @Test
    fun `in_sync forges the settled joined layer`() = runTest {
        val host = assertNotNull(forgeStatusHost("in_sync", backgroundScope, fixedFormatter()))
        // completed == total and the download arm empty → both arrows hidden → InSync, reached with the
        // benign default `attested` flow (always true) and download source (no imports).
        val layer = host.container.stateFlow.value.layer
        assertTrue(layer is Layer.Joined, "config present reduces to the joined layer")
        assertEquals(SyncHealth.InSync, layer.health)

        // The event's name and invite now live ON the reduced state — the screen reads them from here, so
        // this is what the capture actually depicts.
        assertEquals(EVENT_NAME, layer.membership.name)
        assertEquals(EVENT_ID, layer.membership.eventId)
        assertEquals(encodeEventUrl(EventLinkPayload(EVENT_ID)), layer.inviteUrl)
        // A granted grant offers no "Choose more photos" affordance (capability `photo-access`).
        assertFalse(layer.canChoosePhotos)
        // Nothing is drawn over a marketing capture.
        assertEquals(Overlays(), host.container.stateFlow.value.overlays)
    }

    @Test
    fun `an unrecognized state is rejected so the shell falls back to the live stack`() = runTest {
        assertNull(forgeStatusHost("not-a-state", backgroundScope, fixedFormatter()))
        assertNull(forgeStatusHost("", backgroundScope, fixedFormatter()))
    }
}
