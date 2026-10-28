package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

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
        requirement = "Switching between full and limited access re-uploads only what was de-selected",
        scenario = "Narrowing to a selection",
    )
    fun a_downgrade_withdraws_the_unselected_and_the_foreground_settles_the_selected() = behaviour {
        val all = listOf("A", "B", "C", "D")
        given {
            // The OS-driven cycle alone under the full grant: its four jobs are the ones the downgrade strands.
            extensionUploadsOnly()
            photoAccess(PhotoGrant.GRANTED)
            joinedEvent()
            photos(*all.toTypedArray())
            step("four jobs in flight under the full grant", act = { cycle() }, until = {
                all.forEach { id -> event<WorldEvent.ManifestPublished> { id in it.added } }
                settled { it.state.inFlight?.jobs?.toSet() == all.map(::primaryJob).toSet() }
            })
            // The network returns: every object lands, and no acknowledgement ever reaches the app — the OS finishes
            // all four jobs, and the extension is never invoked.
            step("every object landed, unacknowledged", act = { uploadQueue.completeJob() }, until = {
                all.forEach { id -> event<WorldEvent.BytesStored> { it.asset == id } }
                never<WorldEvent.UploadJobEnded>()
                settled { true }
            })
        }

        // Access narrows to two of the four, and the selection is read. Under a partial grant the extension withholds,
        // so the app's uploader is the one that runs: its selection change walks the read selection.
        whenever {
            library.permission(PhotoGrant.LIMITED)
            devControls.pin(app = true, extension = true)
            library.changeSelection(listOf("A", "B"))
            advance(5.seconds) // the screen's status poll
        }
        then {
            // The de-selected photos leave the manifest, in flight or not.
            event<WorldEvent.ManifestPublished> { "C" in it.withdrawn }
            event<WorldEvent.ManifestPublished> { "D" in it.withdrawn }
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn || "B" in it.withdrawn }
            // Still unacknowledged: the selected two are outstanding on the screen.
            screen<Layer.Joined> { it.health is SyncHealth.Syncing }
        }

        // Foreground: the backend is asked, and the selected uploads whose bytes it holds settle — and the screen
        // reaches In sync over the selection, instead of Syncing until access returns.
        whenever {
            os.foreground()
            advance(5.seconds)
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            // the settle lists no withdrawn photo
            never<WorldEvent.ManifestPublished> { "C" in it.added || "D" in it.added }
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn || "B" in it.withdrawn }
        }
    }

    @Test
    @Verifies(spec = "photo-access", requirement = "A selection the app has not yet looked at withdraws nothing")
    fun an_unread_selection_withholds_the_cycle_and_deletes_nothing() = behaviour {
        // A cold launch under a partial grant: the selection has not been read yet. Collapsed to an empty
        // selection, the enqueue resolved every waiting row against it and deleted each one as gone.
        given {
            extensionUploadsOnly() // the full-grant cycle that declares the row is the OS-driven one
            photoAccess(PhotoGrant.GRANTED)
            joinedEvent()
            photos("A")
            // discovered and declared, no job: the row the old collapse deleted
            step("the queue takes no job", act = { uploadQueue.jobLimit(0) }, until = {})
            step("A declared on discovery", act = { cycle() }, until = {
                event<WorldEvent.ManifestPublished> { "A" in it.added }
                never<WorldEvent.UploadJobCreated>()
                settled { true }
            })
        }

        // No selection read yet — and under a partial grant only the app's uploader may create. The foreground's tail
        // runs the app's uploader, whose admission withholds on an unread selection.
        whenever {
            library.permission(PhotoGrant.LIMITED)
            devControls.pin(app = true, extension = true)
            os.foreground()
        }
        then {
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn } // no row is deleted: the declaration stands
            never<WorldEvent.SessionTransferCreated>() // and nothing is created while the selection is unread
        }

        // Once the selection is read the app's uploader runs over it.
        whenever { library.changeSelection(listOf("A")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("A")) }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "Deselecting under limited access withdraws",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "Restoring or re-including a photo shares it again",
        scenario = "A photo re-added to the selection is shared again",
    )
    fun re_selecting_a_withdrawn_photo_shares_it_again() = behaviour {
        // Under a partial grant the app's uploader is the one that runs (the extension withholds), each selection
        // change's tail walking the read selection.
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            photos("A", "B")
            step("A and B selected", act = { library.changeSelection(listOf("A", "B")) }, until = {
                times(2) { event<WorldEvent.SessionTransferCreated>() }
                settled { true }
            })
            step("both uploaded", act = { uploadSession.complete() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "A" }
                event<WorldEvent.BytesStored> { it.asset == "B" }
                never<WorldEvent.SessionTransferCreated>()
                settled { true }
            })
        }

        whenever { library.changeSelection(listOf("A")) }
        then {
            event<WorldEvent.ManifestPublished> { "B" in it.withdrawn }
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn }
        }

        whenever { library.changeSelection(listOf("A", "B")) }
        then {
            // uploaded again — one transfer more, and declared again beside A
            times(1) { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }
            never<WorldEvent.SessionTransferCreated> { it.key != primaryKey("B") }
            event<WorldEvent.ManifestPublished> { "B" in it.added }
            never<WorldEvent.ManifestPublished> { "A" in it.withdrawn }
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("B")) }
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Switching between full and limited access re-uploads only what was de-selected",
        scenario = "A photo de-selected earlier returns on widening",
    )
    fun a_photo_de_selected_earlier_is_uploaded_again_on_widening() = behaviour {
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            photos("A", "B")
            step("A and B selected", act = { library.changeSelection(listOf("A", "B")) }, until = {
                times(2) { event<WorldEvent.SessionTransferCreated>() }
                settled { true }
            })
            step("both uploaded", act = { uploadSession.complete() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "A" }
                event<WorldEvent.BytesStored> { it.asset == "B" }
                settled { true }
            })
            step("B de-selected", act = { library.changeSelection(listOf("A")) }, until = {
                event<WorldEvent.ManifestPublished> { "B" in it.withdrawn }
                settled { true }
            })
        }

        // The de-selection forgot that B was shared, so full access finds it as a new photo; A, still shared, is not
        // sent again.
        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            times(1) { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }
    }
}
