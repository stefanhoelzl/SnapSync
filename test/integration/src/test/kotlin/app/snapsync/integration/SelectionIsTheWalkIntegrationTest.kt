package app.snapsync.integration

import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Under a partial grant the selection is the walk (capability `photo-access`; decision record
 * `changes/selection-is-the-walk`), over the REAL composed core, driven through the control protocol.
 *
 * The first test replays the downgrade measured on an SE2 (iOS 26.6, 2026-09-22): four uploads in flight under a
 * full grant, access narrowed to two of the four photos, and every object landing on the backend while the
 * withheld extension was presented nothing — so the uploads stayed unacknowledged and the screen read `Syncing`
 * until full access returned. The host has no extension; the landing without an acknowledgement is the backend's
 * `deposit` lever, with the jobs never completed.
 */
class SelectionIsTheWalkIntegrationTest {

    @Test
    fun a_downgrade_withdraws_the_unselected_and_the_foreground_settles_the_selected() = rigTest {
        val all = listOf("A", "B", "C", "D")
        permission("GRANTED")
        val event = createAndJoin()
        for (id in all) addPhoto(id)

        // Four jobs in flight under the full grant.
        cycle()
        assertEquals(all.map(::primaryKey).toSet(), jobs().live.toSet(), "all four in flight")

        // The network returns: every object lands, and no acknowledgement ever reaches the app.
        for (id in all) device("backend/deposit", "asset" to id)

        // Access narrows to two of the four, and the selection is read.
        permission("LIMITED")
        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")

        // The next cycle walks the read selection: the de-selected photos leave, in flight or not.
        cycle()
        assertEquals(setOf("A", "B"), manifest(event)?.keys, "the manifest lists only the selection")
        // Still unacknowledged: the selected two are outstanding on the screen.
        refresh()
        awaitHealth { it is SyncHealth.Syncing }

        // Foreground: the backend is asked, and the selected uploads whose bytes it holds settle — and the screen
        // reaches In sync over the selection, instead of Syncing until access returns.
        os("app", "onForeground")
        refresh()
        awaitInSync()
        assertEquals(setOf("A", "B"), manifest(event)?.keys, "the settle lists no withdrawn photo")
    }

    @Test
    fun an_unread_selection_withholds_the_cycle_and_deletes_nothing() = rigTest {
        // A cold launch under a partial grant: the selection has not been read yet. Collapsed to an empty
        // selection, the enqueue resolved every waiting row against it and deleted each one as gone.
        permission("GRANTED")
        val event = createAndJoin()
        addPhoto("A")
        device("jobs/limit", "n" to "0") // discovered and declared, no job: the row the old collapse deleted
        cycle()
        assertEquals(setOf("A"), manifest(event)?.keys, "declared on discovery")
        assertEquals(0, jobs().created)

        permission("LIMITED") // no selection read yet
        device("jobs/limit", "n" to UNLIMITED)
        cycle()

        assertEquals(setOf("A"), manifest(event)?.keys, "no row is deleted: the declaration stands")
        assertEquals(0, jobs().created, "and nothing is created while the selection is unread")

        // Once the selection is read the cycle runs over it.
        device("selection/change", "assets" to "A")
        awaitSelection("A")
        cycle()
        assertEquals(listOf(primaryKey("A")), jobs().live)
    }

    @Test
    fun re_selecting_a_withdrawn_photo_shares_it_again() = rigTest {
        permission("LIMITED")
        val event = createAndJoin()
        addPhoto("A")
        addPhoto("B")

        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")
        uploadAll()
        assertTrue(primaryKey("B") in objects())
        assertEquals(2, jobs().created)

        device("selection/change", "assets" to "A")
        awaitSelection("A")
        cycle()
        assertEquals(setOf("A"), manifest(event)?.keys, "withdrawn")

        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")
        cycle()
        assertEquals(listOf(primaryKey("B")), jobs().live, "uploaded again")
        assertEquals(3, jobs().created)
        assertEquals(setOf("A", "B"), manifest(event)?.keys, "and listed again")
    }

    private companion object {
        const val UNLIMITED = "2147483647"
    }
}

/**
 * Wait until the app's own candidate read answers exactly [ids] — the selection snapshot has landed, so the next
 * cycle's discovery is fed it.
 */
private suspend fun Rig.awaitSelection(vararg ids: String) {
    eventually(read = { gallery().policy?.assets?.mapTo(mutableSetOf()) { it.assetId } }) { it == ids.toSet() }
}
