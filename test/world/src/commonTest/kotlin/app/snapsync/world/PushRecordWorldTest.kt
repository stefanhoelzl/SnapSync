package app.snapsync.world

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mini-edge records the pushes it would send (capability `harness-world-model`, "The mini-edge records the
 * pushes it would send"), on the real backend's rule: a write that makes an asset newly servable wakes the
 * event's OTHER active members holding a token — recorded, never delivered, because the operator plays the OS.
 */
class PushRecordWorldTest {

    @Test
    fun a_foreign_members_completed_upload_is_pushed_to_a_registered_device() = worldTest {
        val w = World(this)
        val event = w.provisionMinted()
        registerToken(w, "DEADBEEF")

        w.addForeignDeviceMinted("DEV-F", listOf(World.foreignAsset("FQ")), event)

        val pushes = (w.neutral.pushesSent() as Answer.Available).value
        assertTrue(pushes.isNotEmpty(), "the completed foreign asset woke the registered member")
        assertTrue(pushes.all { it.deviceId == w.ownDeviceId && it.token == "DEADBEEF" && it.eventId == event })
    }

    @Test
    fun a_device_without_a_token_and_the_publisher_itself_are_never_pushed() = worldTest {
        val w = World(this)
        val event = w.provisionMinted()

        w.addForeignDeviceMinted("DEV-F", listOf(World.foreignAsset("FQ")), event)

        assertEquals(emptyList(), (w.neutral.pushesSent() as Answer.Available).value)
    }

    /** The composed registration writes the delivered token; touching the host is what installs it. */
    private suspend fun registerToken(w: World, token: String) {
        w.statusHost
        w.pushTokens.deliver(token)
        withTimeout(5_000) {
            while ((w.neutral.deviceConfigOf(w.ownDeviceId) as Answer.Available).value == null) yield()
        }
    }
}
