package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * Received photos and leaving, over the real stack, read off what the photo library, the operating system's jobs and
 * the backend record: an error body is never imported, an imported photo outlives a leave and is never sent back, and
 * leaving departs this device and nothing more.
 */
class DownloadLeaveIntegrationTest {

    /**
     * A `502` arrives as a *successful* transfer of an error body. Staged, it would become the store's truth: the import
     * would fail against it forever, the transfer never re-run, and nothing would say so.
     */
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
        scenario = "A broken download is retried",
    )
    fun a_rejected_transfer_imports_nothing_and_stays_pending_for_the_next_reconcile() = behaviour {
        given {
            joinedEvent()
            fellowMember(DEV_F, "FQ")
            itsDownloadStarted()
        }

        whenever { downloadSession.finish(status = 502, received = ERROR_BODY_BYTES) }
        then {
            never<WorldEvent.LibraryAssetCreated>() // an error body is never imported
            settled { true }
        }

        // Not terminal: the resource stayed un-staged, so the next reconcile starts the transfer again…
        whenever { os.foreground() }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        // …and the retry imports — the bytes were re-fetched.
        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == RECEIVED } }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving deletes no one's photos",
        scenario = "Received photos stay",
    )
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun an_imported_photo_outlives_a_leave_and_a_rejoin_never_sends_it_back() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                fellowMember(DEV_F, "FQ")
                itsDownloadStarted()
                step("it arrived", act = { downloadSession.finish() }, until = {
                    event<WorldEvent.LibraryAssetCreated> { it.asset == RECEIVED }
                    settled { true }
                })
            }
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            screen<Layer.CreateEvent>()
            settled { RECEIVED in it.state.libraryPhotos.orEmpty() } // the imported photo survives the leave
        }

        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }
        // The imported photo is still suppressed, so nothing is uploaded.
        whenever { cycle() }
        then { never<WorldEvent.UploadJobCreated>() }
    }

    /** Leaving is RENAME-ONLY: no reap, no byte collection — the nightly sweep does that. */
    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Leaving deletes no one's photos",
        scenario = "Shared photos outlive the member's leave",
    )
    @Verifies(spec = "event-lifetime", requirement = "A member who leaves keeps contributing what they shared")
    fun leaving_departs_this_device_and_keeps_the_event_its_bytes_and_the_union() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                uploaded("A")
                fellowMember(DEV_F, "FQ")
            }
        }

        // The backend leave is sent after the local teardown, without the screen waiting for it.
        whenever { tap(UiIntent.LeaveEvent) }
        then {
            event<WorldEvent.MemberLeft> { it.event == event }
            never<WorldEvent.EventDeleted>()
            // the union still serves the departed member's photos, its bytes kept
            never<WorldEvent.UnionChanged> { !it.served }
            settled { event in it.state.backendEvents.orEmpty() } // the event lives on
        }
    }

    /** The foreground's reconcile starts the fellow member's photo's download. */
    private suspend fun Given.itsDownloadStarted() = step("its download started", act = { os.foreground() }, until = {
        event<WorldEvent.DownloadStarted>()
        settled { true }
    })

    private companion object {
        /** The fellow member's photo, as the library holds it once imported. */
        const val RECEIVED = "imported-$DEV_F-FQ"

        /** What an error answer's body brings: a few bytes of text, not the photo. */
        const val ERROR_BODY_BYTES = 137L
    }
}
