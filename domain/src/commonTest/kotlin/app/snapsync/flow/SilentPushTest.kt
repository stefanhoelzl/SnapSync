package app.snapsync.flow

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The silent-push flow: the cross-arm fan-out absorbed from the former `FanOutPushReceiver`, and —
 * since migration step 12 — the whole-payload entry ([SilentPush.run] takes the raw `userInfo`; the
 * field extraction that used to be an untestable Swift `guard` is the tested `model/` codec). A push
 * wakes **every** arm's receiver, in order, and one throwing arm never robs the others of the scarce,
 * short-budgeted wake (losing the whole wake to one arm's bad day is how a member stops receiving
 * photos). The per-arm active-event and direction guards are the receivers' own, tested in
 * `feature/upload` and `feature/download`.
 */
class SilentPushTest {

    private class Recorder {
        val seen = mutableListOf<String>()
        var reloads = 0
        var attestations = 0
    }

    private fun kotlinx.coroutines.CoroutineScope.flow(
        recorder: Recorder,
        receivers: List<suspend (String) -> Unit>,
    ) = SilentPush(
        scope = this,
        reloadConfig = { recorder.reloads++ },
        refreshAttestation = { recorder.attestations++ },
        receivers = receivers,
    )

    @Test
    fun `the fan out wakes every arm`() = runTest {
        val r = Recorder()
        flow(
            r,
            listOf(
                { eventId -> r.seen += "up:$eventId" },
                { eventId -> r.seen += "down:$eventId" },
            ),
        ).fanOut("E")

        assertEquals(listOf("up:E", "down:E"), r.seen)
    }

    @Test
    fun `one failing arm never robs the others of the wake`() = runTest {
        // A push is a scarce, short-budgeted wake. If the upload arm throws, the DOWNLOAD arm must still
        // reconcile — losing the whole wake to one arm's bad day is how a member stops receiving photos.
        val r = Recorder()
        flow(
            r,
            listOf(
                { _ -> error("this arm blew up") },
                { eventId -> r.seen += "down:$eventId" },
            ),
        ).fanOut("E") // must not throw

        assertTrue("down:E" in r.seen, "the surviving arm still got its wake")
    }

    @Test
    fun `a payload with an eventId reloads config and fans out`() = runTest {
        val r = Recorder()
        flow(r, listOf({ eventId -> r.seen += "arm:$eventId" }))
            .run(mapOf<Any?, Any?>("eventId" to "E7"))
        advanceUntilIdle()

        assertEquals(listOf("arm:E7"), r.seen)
        assertEquals(1, r.reloads, "the membership is re-read before the receivers' guards read it")
        assertEquals(1, r.attestations, "a background wake is a token-renewal chance")
    }

    @Test
    fun `a payload without an eventId fans out to no arm`() = runTest {
        val r = Recorder()
        flow(r, listOf({ eventId -> r.seen += "arm:$eventId" }))
            .run(mapOf<Any?, Any?>("aps" to "alert"))
        advanceUntilIdle()

        assertTrue(r.seen.isEmpty(), "no receiver runs for a push with no usable eventId")
    }
}
