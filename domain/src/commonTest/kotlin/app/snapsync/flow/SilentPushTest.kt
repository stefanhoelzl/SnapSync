package app.snapsync.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SilentPush.fanOut] — the cross-arm fan-out that the silent-push flow absorbed from the former
 * `FanOutPushReceiver`. A push wakes **every** arm's receiver, in order, and one throwing arm never
 * robs the others of the scarce, short-budgeted wake (losing the whole wake to one arm's bad day is how
 * a member stops receiving photos). The per-arm active-event and direction guards are the receivers'
 * own, tested in `feature/upload` and `feature/download`; this pins only the fan-out.
 */
class SilentPushTest {

    private fun flow(receivers: List<suspend (String) -> Unit>) =
        SilentPush(
            scope = CoroutineScope(EmptyCoroutineContext), // unused: fanOut is called directly, not run()
            protectedDataGate = { _, work -> work() },
            refreshAttestation = {},
            receivers = receivers,
        )

    @Test
    fun the_fan_out_wakes_every_arm() = runTest {
        val seen = mutableListOf<String>()
        flow(
            listOf(
                { eventId -> seen += "up:$eventId" },
                { eventId -> seen += "down:$eventId" },
            ),
        ).fanOut("E")

        assertEquals(listOf("up:E", "down:E"), seen)
    }

    @Test
    fun one_failing_arm_never_robs_the_others_of_the_wake() = runTest {
        // A push is a scarce, short-budgeted wake. If the upload arm throws, the DOWNLOAD arm must still
        // reconcile — losing the whole wake to one arm's bad day is how a member stops receiving photos.
        val seen = mutableListOf<String>()
        flow(
            listOf(
                { _ -> error("this arm blew up") },
                { eventId -> seen += "down:$eventId" },
            ),
        ).fanOut("E") // must not throw

        assertTrue("down:E" in seen, "the surviving arm still got its wake")
    }
}
