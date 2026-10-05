package app.snapsync.flow

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The silent-push flow: the push's own work — the download arm — and nothing else (capability `receiving-photos`,
 * "Silent-push receive seam"). The upload arm is not a receiver any more: its work is the tail's, which the inbound
 * port's implementation requests after this flow returns, for the active event only. [SilentPush.run] takes the raw
 * `userInfo` (the field extraction is the tested `model/` codec).
 */
class SilentPushTest {

    private class Recorder {
        val seen = mutableListOf<String>()
        var reloads = 0
        var attestations = 0
    }

    private fun flow(
        recorder: Recorder,
        download: suspend (String, Long?) -> Unit = { id, seq -> recorder.seen += "down:$id" + (seq?.let { "@$it" } ?: "") },
    ) =
        SilentPush(
            reloadConfig = { recorder.reloads++ },
            refreshAttestation = { recorder.attestations++ },
            downloadReceiver = download,
        )

    @Test
    fun `a payload with an eventId reloads config then runs the download arm`() = runTest {
        val r = Recorder()
        flow(r).run(mapOf<Any?, Any?>("eventId" to "E7"))

        assertEquals(listOf("down:E7"), r.seen)
        assertEquals(1, r.reloads, "the membership is re-read before the guards read it")
        assertEquals(1, r.attestations, "a background wake is a token-renewal chance")
    }

    @Test
    fun `the position a push announces reaches the download arm as APNs and FCM carry it`() = runTest {
        val r = Recorder()
        flow(r).run(mapOf<Any?, Any?>("eventId" to "E", "seq" to 42L))
        flow(r).run(mapOf<Any?, Any?>("eventId" to "E", "seq" to "43"))
        flow(r).run(mapOf<Any?, Any?>("eventId" to "E", "seq" to "no"))
        assertEquals(listOf("down:E@42", "down:E@43", "down:E"), r.seen)
    }

    @Test
    fun `a failing download arm is contained`() = runTest {
        // The push's handler must still be released, and its tail still joined: the imports it drains are staged
        // already, and a union read that threw has nothing to say about them.
        val r = Recorder()
        flow(r, download = { _, _ -> error("the union read blew up") }).run(mapOf<Any?, Any?>("eventId" to "E")) // must not throw
        assertEquals(1, r.reloads)
    }

    @Test
    fun `a payload without an eventId runs no receiver`() = runTest {
        val r = Recorder()
        flow(r).run(mapOf<Any?, Any?>("aps" to "alert"))

        assertTrue(r.seen.isEmpty(), "no receiver runs for a push with no usable eventId")
        assertEquals(0, r.reloads)
    }
}
