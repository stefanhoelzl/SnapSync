package app.snapsync.rig

import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.WorldEvent
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A device host's world (`docs/testing.md`, "How a test waits"): its events served from the last act on — so a wait
 * that follows an act misses nothing of it and replays nothing older — its history bounded, and its clock and settling
 * refused.
 */
class DeviceWorldTest {

    private val device = WorldEvents(WorldEvents.Serving.FROM_THE_LAST_ACT)

    @Test
    fun a_stream_starts_where_the_last_act_began() = runBlocking {
        device.tell(ui("before"))
        device.acted("backend.offline")
        device.tell(ui("after"))
        assertEquals(listOf(WorldEvent.Acted("backend.offline"), ui("after")), first(device, 2))
        // Read again, the same: a wait can be repeated until the next act.
        assertEquals(listOf(WorldEvent.Acted("backend.offline"), ui("after")), first(device, 2))
    }

    @Test
    fun an_act_lets_go_of_what_came_before_the_act_preceding_it() {
        device.tell(ui("0"))
        device.acted("a")
        device.tell(ui("1"))
        device.acted("b")
        device.tell(ui("2"))
        assertEquals(
            listOf(WorldEvent.Acted("a"), ui("1"), WorldEvent.Acted("b"), ui("2")),
            device.snapshot(),
        )
    }

    @Test
    fun a_stream_open_across_an_act_sees_every_event() = runBlocking {
        device.acted("a")
        val read = async { device.flow().take(4).toList() }
        yield()
        device.tell(ui("1"))
        device.acted("b")
        device.tell(ui("2"))
        assertEquals(
            listOf(WorldEvent.Acted("a"), ui("1"), WorldEvent.Acted("b"), ui("2")),
            withTimeout(TIMEOUT) { read.await() },
        )
    }

    @Test
    fun an_act_carries_its_callers_tag() = runBlocking {
        device.tagActs("mine")
        device.acted("backend.offline")
        assertEquals(WorldEvent.Acted("backend.offline", "mine"), first(device, 1).single())
        assertEquals(1, device.actsTagged("mine"))
    }

    @Test
    fun a_device_never_acted_on_keeps_a_bounded_history() {
        repeat(WorldEvents.KEPT_AT_MOST + 10) { device.tell(ui("$it")) }
        val kept = device.snapshot()
        assertEquals(WorldEvents.KEPT_AT_MOST, kept.size)
        assertEquals(ui("10"), kept.first())
    }

    @Test
    fun the_jvm_host_keeps_and_serves_everything() = runBlocking {
        val jvm = WorldEvents()
        jvm.tell(ui("0"))
        jvm.acted("a")
        jvm.acted("b")
        assertEquals(listOf(ui("0"), WorldEvent.Acted("a"), WorldEvent.Acted("b")), first(jvm, 3))
    }

    @Test
    fun an_announced_act_is_marked_before_it_runs_and_a_read_is_not() = runBlocking {
        val acts = RigActs.Announced(device)
        acts.act("os.silentPush") { device.tell(ui("pushed")) }
        acts.read { device.tell(ui("read")) }
        assertEquals(listOf(WorldEvent.Acted("os.silentPush"), ui("pushed"), ui("read")), device.snapshot())
    }

    @Test
    fun a_device_refuses_the_clock_and_settling_and_serves_the_events() = runBlocking {
        val world = HostWorld.onADevice(device)
        assertEquals(
            setOf("world.advance", "world.advanceTo", "world.whyNotSettled"),
            world.service.refused.keys,
        )
        assertTrue(world.service.refused.values.all { it.isNotBlank() })
        val served = DeviceWorld(device)
        assertFailsWith<LeverRefused> { served.whyNotSettled() }
        assertFailsWith<LeverRefused> { served.advance(1) }
        device.acted("a")
        assertEquals(WorldEvent.Acted("a"), withTimeout(TIMEOUT) { served.events().take(1).toList() }.single())
    }

    @Test
    fun the_decorated_screen_tells_every_state_it_is_shown() {
        val shown = mutableListOf<UiState>()
        val screen = object : Ui<UiState, UiIntent> {
            @CompositionOnly
            override fun inbound(handlers: UiHandlers<UiState, UiIntent>) = Unit

            override fun show(state: UiState) {
                shown += state
            }
        }
        RigUi(lazyOf(screen), shown = { device.tell(WorldEvent.UiChanged(it)) }).show(UiState(Layer.CreatingEvent))
        assertEquals(listOf(UiState(Layer.CreatingEvent)), shown)
        assertEquals(listOf(WorldEvent.UiChanged(UiState(Layer.CreatingEvent))), device.snapshot())
    }

    private suspend fun first(events: WorldEvents, n: Int) = withTimeout(TIMEOUT) { events.flow().take(n).toList() }

    /** A distinguishable event: a link the system UI was handed. */
    private fun ui(label: String): WorldEvent = WorldEvent.UrlOpened(label)

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
