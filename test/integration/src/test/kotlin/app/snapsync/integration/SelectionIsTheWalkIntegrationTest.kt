package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Under a partial grant the selection is the walk (decision record `changes/selection-is-the-walk`), over the REAL
 * composed core, driven through the control protocol.
 *
 * The first test replays the downgrade measured on an SE2 (iOS 26.6, 2026-09-22): four uploads in flight under a
 * full grant, access narrowed to two of the four photos, and every object landing on the backend while the
 * withheld extension was presented nothing — so the uploads stayed unacknowledged and the screen read `Syncing`
 * until full access returned. The landing without an acknowledgement is the backend's `deposit` lever, with the jobs
 * never completed. Under the partial grant it is the app's uploader that runs — the extension withholds by its own
 * admission — so each test switches the uploaders as the phase needs.
 */
class SelectionIsTheWalkIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Switching between full and limited access never re-uploads",
        scenario = "Narrowing to a selection",
    )
    fun a_downgrade_withdraws_the_unselected_and_the_foreground_settles_the_selected() = rigTest {
        val all = listOf("A", "B", "C", "D")
        // The OS-driven cycle alone under the full grant: its four jobs are the ones the downgrade strands.
        extensionUploadsOnly()
        permission("GRANTED")
        val event = createAndJoin()
        for (id in all) addPhoto(id)

        // Four jobs in flight under the full grant.
        cycle()
        assertEquals(all.map(::primaryKey).toSet(), jobs().live.toSet(), "all four in flight")

        // The network returns: every object lands, and no acknowledgement ever reaches the app.
        for (id in all) device("backend/deposit", "asset" to id)

        // Access narrows to two of the four, and the selection is read. Under a partial grant the extension withholds,
        // so the app's uploader is the one that runs: its selection change walks the read selection.
        permission("LIMITED")
        device("uploaders", "app" to "on")
        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")

        // The de-selected photos leave the manifest, in flight or not.
        eventually(read = { manifest(event)?.keys }) { it == setOf("A", "B") }
        // Still unacknowledged: the selected two are outstanding on the screen (a state read is the screen's pull).
        awaitHealth { it is SyncHealth.Syncing }

        // Foreground: the backend is asked, and the selected uploads whose bytes it holds settle — and the screen
        // reaches In sync over the selection, instead of Syncing until access returns.
        foreground()
        awaitInSync()
        assertEquals(setOf("A", "B"), manifest(event)?.keys, "the settle lists no withdrawn photo")
    }

    @Test
    @Verifies(spec = "photo-access", requirement = "A selection the app has not yet looked at withdraws nothing")
    fun an_unread_selection_withholds_the_cycle_and_deletes_nothing() = rigTest {
        // A cold launch under a partial grant: the selection has not been read yet. Collapsed to an empty
        // selection, the enqueue resolved every waiting row against it and deleted each one as gone.
        extensionUploadsOnly() // the full-grant cycle that declares the row is the OS-driven one
        permission("GRANTED")
        val event = createAndJoin()
        addPhoto("A")
        device("jobs/limit", "n" to "0") // discovered and declared, no job: the row the old collapse deleted
        cycle()
        assertEquals(setOf("A"), manifest(event)?.keys, "declared on discovery")
        assertEquals(0, jobs().created)

        // No selection read yet — and under a partial grant only the app's uploader may create.
        permission("LIMITED")
        device("uploaders", "app" to "on")
        foreground() // the foreground's tail runs the app's uploader, whose admission withholds on an unread selection

        assertEquals(setOf("A"), manifest(event)?.keys, "no row is deleted: the declaration stands")
        assertEquals(0, appUploads().created, "and nothing is created while the selection is unread")

        // Once the selection is read the app's uploader runs over it.
        device("selection/change", "assets" to "A")
        awaitSelection("A")
        assertEquals(listOf(primaryKey("A")), awaitAppUploads(1).live)
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "Deselecting under limited access withdraws",
    )
    fun re_selecting_a_withdrawn_photo_shares_it_again() = rigTest {
        // Under a partial grant the app's uploader is the one that runs (the extension withholds), each selection
        // change's tail walking the read selection.
        permission("LIMITED")
        val event = createAndJoin()
        addPhoto("A")
        addPhoto("B")

        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")
        awaitAppUploads(2)
        completeAppUploads()
        assertTrue(primaryKey("B") in objects())
        assertEquals(2, appUploads().created)

        device("selection/change", "assets" to "A")
        awaitSelection("A")
        eventually(read = { manifest(event)?.keys }) { it == setOf("A") }

        device("selection/change", "assets" to "A,B")
        awaitSelection("A", "B")
        assertEquals(listOf(primaryKey("B")), awaitAppUploads(1).live, "uploaded again")
        assertEquals(3, appUploads().created)
        eventually<Set<String>?>(read = { manifest(event)?.keys }) { it == setOf("A", "B") }
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
