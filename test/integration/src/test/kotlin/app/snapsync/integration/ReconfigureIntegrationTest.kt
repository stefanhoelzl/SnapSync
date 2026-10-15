package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.TransferEnd
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Seam ↔ UI-state integration for the in-place reconfigure, driven through the
 * control protocol's settings taps (`Person.reconfigure`) over the real core — asserting **`UiState` AND observable outcomes**:
 * enabling share uploads, album-on gathers what is already held, and turning receive off cancels in-flight downloads.
 */
class ReconfigureIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Each direction runs only if the member chose it",
        scenario = "Receive-only uploads nothing",
    )
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings changes take effect immediately",
        scenario = "Turning sharing on starts right away",
    )
    fun enabling_share_on_a_download_only_membership_starts_uploading_in_place() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent(Choices(direction = Direction.DownloadOnly)).also { photos("A") }
        }

        // Download-only: the cycle uploads nothing (the privacy invariant).
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
            never<WorldEvent.ManifestPublished> { "A" in it.added }
        }

        // Reconfigure IN PLACE to Both — no leave, same eventId.
        lateinit var both: Seen<Layer.Joined>
        whenever { reconfigure(Choices(direction = Direction.Both)) }
        then {
            both = screen<Layer.Joined> { it.membership.shares && it.membership.receives }
            never<WorldEvent.MemberLeft>()
            never<WorldEvent.MemberJoined>()
        }
        assertEquals(event, both.value.membership.eventId, "same membership, never left")

        // Now the very same stack uploads the own photo.
        whenever {
            uploadAll()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "A" } // enabling share uploads the own photo
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
        }
    }

    @Test
    @Verifies(
        spec = "event-album",
        requirement = "Turning the album on gathers what is already there",
        scenario = "Turning the album on later",
    )
    @Verifies(spec = "event-album", requirement = "The album holds the event as this device holds it")
    fun turning_the_album_on_gathers_already_synced_photos_and_places_new_ones() = behaviour {
        given {
            extensionUploadsOnly()
            // Every membership carries a name; it titles the album.
            joinedEvent(Choices(saveToAlbum = false))
            photos("A")
            // A uploads while the album is OFF — so it is a genuinely already-synced photo.
            step("A uploaded, with no album while opted out", act = { uploadAll() }, until = {
                event<WorldEvent.BytesStored> { it.asset == "A" }
                settled { it.state.albums.orEmpty().isEmpty() }
            })
        }

        // Reconfigure the album ON: the album is ensured, and the gather places the
        // already-synced A — detached, so the Save returned before it ran.
        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == "A" }
            settled { it.state.albums?.map { album -> album.title to album.photos } == listOf(Rig.EVENT_NAME to listOf("A")) }
        }

        // A NEW photo synced after the toggle is placed too, at its first enqueue — before the upload completed.
        whenever {
            takePhoto("B")
            cycle()
        }
        then {
            event<WorldEvent.LibraryJoinedAlbum> { it.asset == "B" }
            never<WorldEvent.BytesStored>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings changes take effect immediately",
        scenario = "Turning receiving off stops downloads",
    )
    fun turning_receive_off_cancels_in_flight_downloads_and_imports_nothing() = behaviour {
        given {
            joinedEvent() // Both
            fellowMember(DEV_F, "FQ")
            // An in-flight download, which RECEIVE is turned off before it is staged.
            step("its download started", act = { os.foreground() }, until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            })
        }

        whenever { reconfigure(Choices(direction = Direction.UploadOnly)) }
        then {
            screen<Layer.Joined> { it.membership.shares && !it.membership.receives }
            event<WorldEvent.DownloadEnded> { it.outcome.how == TransferEnd.How.CANCELLED }
            settled { true }
        }

        // The in-flight download was cancelled, so staging imports nothing, and the now-gated reconcile enqueues
        // nothing further.
        whenever {
            downloadSession.finish()
            os.foreground()
            downloadSession.finish()
            advance(STATUS_POLL)
        }
        then {
            never<WorldEvent.DownloadStarted>()
            never<WorldEvent.LibraryAssetCreated>() // receive-off cancels in-flight downloads; nothing imports
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Deleting or no longer sharing a photo withdraws it")
    @Verifies(
        spec = "sync-status",
        requirement = "One status line in a fixed priority",
        scenario = "Neither sharing nor receiving is said plainly",
    )
    fun switching_both_directions_off_withdraws_the_photos_and_says_so() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("A")
                step("A shared, and listed to the event", act = { uploadAll() }, until = {
                    event<WorldEvent.ManifestPublished> { "A" in it.added }
                    event<WorldEvent.BytesStored> { it.asset == "A" }
                    settled { true }
                })
            }
        }

        // Neither direction, from the settings, no Save: the member stays in the event and nothing moves.
        lateinit var neither: Seen<Layer.Joined>
        whenever { reconfigure(Choices(direction = Direction.Neither)) }
        then {
            neither = screen<Layer.Joined> { !it.membership.shares && !it.membership.receives }
            never<WorldEvent.MemberLeft>()
        }
        assertEquals(event, neither.value.membership.eventId, "same membership, never left")

        // The next cycle lists nothing: the member's photos are withdrawn from the event.
        whenever { cycle() }
        then { event<WorldEvent.ManifestPublished> { it.event == event && "A" in it.withdrawn } }

        // And the joined screen says so, ahead of everything else.
        whenever {
            os.foreground()
            advance(STATUS_POLL)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.Inactive } }
    }

    private companion object {
        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
