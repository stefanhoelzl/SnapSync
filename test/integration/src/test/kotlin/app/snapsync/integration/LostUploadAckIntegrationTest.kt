package app.snapsync.integration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A terminal upload outcome survives the process that learned it** (capability `sync-ledger`,
 * `ios-url-session-upload`; decision record `changes/fix-lost-upload-acks`).
 *
 * The defect these pin is Bugsink `SNAPSYNC-11`. iOS delivers a background-`URLSession` completion
 * exactly once — `URLSessionTask.State.completed` is documented as *"the task has completed (without
 * being canceled), and the task's delegate receives no further callbacks"* — and the adapter used to
 * park that outcome in an `ArrayList` for a later `UploadCycle` to drain. The drain is gated on a
 * single-flight cycle measured in the field at 27 minutes, 65 minutes and 4h49m, so a process death in
 * between lost the fact outright: the row still read `REQUESTED` with no live task, the stranded repair of
 * the time demoted it, and bytes that had already landed were sent again. One device uploaded the same
 * two photos three times over two days and its status screen said "uploading" throughout.
 *
 * **What makes this assertable without a device** is that the host can simulate the one thing that
 * matters — a process boundary. `/device/relaunch` ends the app and composes a new one over the same durable
 * state: every in-memory registry the adapter held is gone, and only what reached durable storage survives. If a
 * completion is ever parked in memory again, the relaunched app cannot see it and these fail.
 */
class LostUploadAckIntegrationTest {

    @Test
    fun a_completion_learned_by_a_dead_process_is_settled_and_never_re_uploaded() = rigTest {
        createAndJoin()
        addPhoto("A")

        // Cycle 1 creates the upload job; the "OS" then finishes the transfer.
        cycle()
        assertEquals(1, jobs().created, "precondition: one upload job")
        completeJobs()
        assertTrue(primaryKey("A") in objects(), "the bytes landed")

        // ── the process dies here. Everything the adapter held in memory goes with it. ──
        device("relaunch")
        state() // the relaunched app's host is assembled on first touch, as a scene connecting does

        cycle()
        refresh()

        // Nothing is left outstanding: the screen settles over the photo it uploaded.
        awaitInSync()
        assertEquals(
            1, jobs().created,
            "and its bytes are NEVER sent again — this is the whole defect: a lost acknowledgement used to read " +
                "as a lost upload, and the photo was re-uploaded on every relaunch",
        )
        assertEquals(setOf(primaryKey("A")), objects(), "the object landed once")
    }

    @Test
    fun a_platform_that_reports_within_the_cycle_settles_in_that_same_cycle_and_places_once() = rigTest {
        // The PhotoKit tier's shape: that tier has no callback outside the cycle — its adapter fetches the
        // finished jobs, records them settled and acknowledges in place. The album placement is not waiting for
        // that: it happened when the upload was first enqueued, so the completion adds nothing to the album and
        // nothing to do.
        val event = createAndJoin("saveToAlbum" to "true")
        addPhoto("A")

        cycle() // places, then creates the job
        assertEquals(listOf("A"), albumAssets(), "placed when the upload was enqueued, before any byte moved")
        completeJobs() // the "OS" finished it while we were away

        // ONE cycle: the drain records the completion, and the manifest publishes it.
        cycle()

        assertEquals(listOf("A"), albumAssets(), "placed in the event album exactly once")
        // The announcement IS the manifest write: on the versioned device API there is no notify route, so
        // publishing the device's asset set is the only thing that tells the event anything happened.
        assertEquals(setOf("A"), manifest(event)?.keys, "the settled upload reached the published manifest")
        assertEquals(1, jobs().created)
    }

    /** Every asset placed in any album this app created, in order. */
    private suspend fun Rig.albumAssets(): List<String> =
        deviceJson("album/contents").getValue("albums").jsonArray.flatMap { album ->
            album.jsonObject.getValue("assets").jsonArray.map { it.jsonPrimitive.content }
        }
}
