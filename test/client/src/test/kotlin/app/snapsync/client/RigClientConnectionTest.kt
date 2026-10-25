package app.snapsync.client

import app.snapsync.levers.ClockLevers
import app.snapsync.levers.HostLevers
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.ScreenLevers
import app.snapsync.rig.JvmRigHost
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The client's connection, against the JVM host: a host that went and came back on the same port — as a device's app
 * a caller stopped and started again does — is reached again, and what was never sent is sent once it can be.
 */
class RigClientConnectionTest {

    @Test
    fun a_service_held_across_a_restarted_host_reaches_the_new_one() = runBlocking {
        val port = freePort()
        RigClient("http://127.0.0.1:$port").use { client ->
            val host = client.levers(HostLevers)
            JvmRigHost.start("mock", port).use { host.state() }
            JvmRigHost.start("mock", port).use { assertTrue(host.state().ui.layer is Layer.CreateEvent) }
        }
    }

    @Test
    fun a_call_made_before_the_host_is_up_goes_out_once_it_is() = runBlocking {
        val port = freePort()
        RigClient("http://127.0.0.1:$port").use { client ->
            val answer = async { client.levers(HostLevers).state() }
            delay(1.seconds)
            JvmRigHost.start("mock", port).use { assertTrue(answer.await().ui.layer is Layer.CreateEvent) }
        }
    }

    @Test
    fun a_refusal_is_the_callers_and_is_not_sent_again() = runBlocking {
        JvmRigHost.start().use { host ->
            RigClient("http://127.0.0.1:${host.port}").use { client ->
                // The JVM host's loop owns the clock: the clock service is refused there, at once.
                val refused = assertFailsWith<LeverRefused> { client.levers(ClockLevers).set("2026-01-01T00:00:00Z") }
                assertTrue(refused.message.orEmpty().isNotBlank(), "a refusal names its reason")
            }
        }
    }

    @Test
    fun a_screen_wait_outlives_a_restarted_host() = runBlocking {
        val port = freePort()
        RigClient("http://127.0.0.1:$port").use { client ->
            val named = async {
                client.awaitScreen(30.seconds, "the draft named 'after'") { ui ->
                    (ui.layer as? Layer.CreateEvent)?.form?.name == "after"
                }
            }
            JvmRigHost.start("mock", port).use {
                client.levers(ScreenLevers).tap(UiIntent.CreateName("before"))
                delay(500.milliseconds)
            }
            JvmRigHost.start("mock", port).use {
                client.levers(ScreenLevers).tap(UiIntent.CreateName("after"))
                assertEquals("after", (named.await().layer as Layer.CreateEvent).form.name)
            }
        }
    }

    @Test
    fun a_screen_already_shown_answers_the_wait_at_once() = runBlocking {
        JvmRigHost.start().use { host ->
            RigClient("http://127.0.0.1:${host.port}").use { client ->
                client.levers(ScreenLevers).tap(UiIntent.CreateName("shown"))
                val shown = client.awaitScreen(1.seconds, "the draft named 'shown'") { ui ->
                    (ui.layer as? Layer.CreateEvent)?.form?.name == "shown"
                }
                assertEquals("shown", (shown.layer as Layer.CreateEvent).form.name)
            }
        }
    }

    @Test
    fun a_screen_wait_that_never_holds_fails_naming_what_it_waited_for() = runBlocking {
        JvmRigHost.start().use { host ->
            RigClient("http://127.0.0.1:${host.port}").use { client ->
                val failed = assertFailsWith<AssertionError> {
                    client.awaitScreen(1.seconds, "a screen that never comes") { false }
                }
                val message = failed.message.orEmpty()
                assertTrue(message.startsWith("a screen that never comes: not within 1s"), message)
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
