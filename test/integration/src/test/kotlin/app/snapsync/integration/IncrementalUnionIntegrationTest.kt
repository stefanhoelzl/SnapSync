package app.snapsync.integration

import app.snapsync.control.Verifies
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A push reads only what is new, and a withdrawn photo stops coming** (decision record `changes/incremental-union`,
 * D6–D7), counted where the outside can see it: the backend's union reads, and the photo library.
 */
class IncrementalUnionIntegrationTest {

    @Test
    fun a_burst_of_pushes_for_what_was_already_read_reads_the_union_once() = rigTest {
        val event = createAndJoin()
        os("app", "onPushToken", "DEADBEEF") // a registered member is woken
        eventually(read = { registrations() }) { it == 1 }
        settleDownloads()
        foreignDevice("DEV-F", "FA", event = event) // the backend wakes this member, naming the new position
        val before = unionReads(event)

        os("app", "onSilentPush", event)
        eventually(read = { unionReads(event) }) { it == before + 1 }
        os("app", "onSilentPush", event)
        os("app", "onSilentPush", event)
        settleDownloads()

        assertEquals(before + 1, unionReads(event), "the pushes after the first announce nothing it has not read")
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Deleting or no longer sharing a photo withdraws it")
    fun a_photo_withdrawn_before_it_arrived_never_arrives_and_one_received_stays() = rigTest {
        val event = createAndJoin()
        foreignDevice("DEV-F", "KEPT", "RECEIVED", event = event)
        reconcile()
        stage() // both arrive
        val withBoth = libraryTotal()

        foreignDevice("DEV-F", "KEPT", "LATE", event = event) // RECEIVED withdrawn, LATE shared
        reconcile() // plans LATE
        foreignDevice("DEV-F", "KEPT", event = event) // LATE withdrawn before it arrived
        reconcile() // the opening's full read drops it
        stage()

        assertEquals(withBoth, libraryTotal(), "LATE never arrived, and RECEIVED stays in the library")
    }

    private suspend fun Rig.unionReads(event: String): Int =
        deviceJson("backend/reads", "event" to event).getValue("union").jsonPrimitive.int
}
