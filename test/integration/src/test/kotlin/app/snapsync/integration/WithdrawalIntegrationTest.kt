package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

/**
 * **Deleting or no longer sharing a photo withdraws it**, and **restoring or re-including a photo shares it again**
 * (`openspec/specs/photo-sharing/spec.md`), the member's side, one scenario per test, over the real composed stack.
 *
 * A photo is *withdrawn* when the backend records it so: the device's manifest no longer declares it, and what the event
 * offers its members loses it ([WorldEvent.UnionChanged], `gained = false`) — so a member who has not received it never
 * does. That a member who already has it keeps their copy is the receiving side's (`IncrementalUnionIntegrationTest`).
 * The extension's cycles are the device looking at the library ([cycle]); the default event runs 15 May – 14 June.
 */
class WithdrawalIntegrationTest {

    // ---- withdrawn ------------------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "A deleted photo is withdrawn",
    )
    fun a_deleted_photo_leaves_what_the_event_offers() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("GONE")
            shared("KEPT")
        }

        whenever {
            library.remove("GONE")
            cycle()
        }
        then {
            withdrawn("GONE")
            never<WorldEvent.ManifestPublished> { "KEPT" in it.withdrawn }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "A photo deleted mid-upload is withdrawn",
    )
    fun a_photo_deleted_while_its_bytes_travel_is_withdrawn_and_stays_so_when_they_land() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("TRAVELLING")
            step("its upload under way", act = { cycle() }, until = {
                event<WorldEvent.UploadJobCreated> { it.job == primaryJob("TRAVELLING") }
                event<WorldEvent.ManifestPublished> { "TRAVELLING" in it.added }
                settled { true }
            })
        }

        whenever {
            library.remove("TRAVELLING")
            cycle()
        }
        then { event<WorldEvent.ManifestPublished> { "TRAVELLING" in it.withdrawn } }

        // The bytes reach the server afterwards: the event never offers the photo.
        whenever {
            uploadQueue.completeJob(primaryJob("TRAVELLING"))
            cycle()
        }
        then {
            never<WorldEvent.UnionChanged> { it.asset == "TRAVELLING" && it.gained }
            never<WorldEvent.ManifestPublished> { "TRAVELLING" in it.added }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "Turning sharing off withdraws the member's photos",
    )
    fun switching_to_receiving_only_withdraws_the_members_photos() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("MINE")
        }

        // From the settings, no Save: the member keeps receiving and stops sharing.
        whenever { reconfigure(Choices(direction = Direction.DownloadOnly)) }
        then { screen<Layer.Joined> { !it.membership.shares && it.membership.receives } }

        whenever { cycle() }
        then { withdrawn("MINE") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "A deletion noticed late is still withdrawn",
    )
    fun a_deletion_the_device_notices_days_later_is_withdrawn_then() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("OLD")
            // Deleted, and the phone does no work for days: no cycle runs until the next one below.
            step("OLD deleted, the phone idle for days", act = {
                os.background()
                library.remove("OLD")
                advanceTo(DAYS_LATER)
            }, until = { settled { true } })
        }

        whenever { cycle() }
        then { withdrawn("OLD") }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "A deletion after the event's end is still withdrawn",
    )
    fun a_deletion_a_week_after_the_range_ended_is_withdrawn() = behaviour {
        // The member's share was settled at the range's end; the event's photos are not deleted — nothing sweeps here.
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("EVENT")
            laterInThePocket(A_WEEK_AFTER_THE_END)
        }

        whenever {
            library.remove("EVENT")
            cycle()
        }
        then { withdrawn("EVENT") }
    }

    // ---- shared again ---------------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Restoring or re-including a photo shares it again",
        scenario = "A recovered photo is shared again",
    )
    fun a_photo_recovered_from_recently_deleted_is_uploaded_and_offered_again() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("BACK")
            step("BACK deleted, and withdrawn", act = {
                library.remove("BACK")
                cycle()
            }, until = {
                event<WorldEvent.UnionChanged> { it.asset == "BACK" && !it.gained }
                settled { true }
            })
        }

        // Recovered: the library holds the same photo, under the same identifier, again.
        whenever {
            takePhoto("BACK")
            uploadAll()
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("BACK") }
            event<WorldEvent.BytesStored> { it.asset == "BACK" }
            event<WorldEvent.UnionChanged> { it.asset == "BACK" && it.gained }
        }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Restoring or re-including a photo shares it again",
        scenario = "Narrow then widen re-uploads nothing",
    )
    fun a_photo_narrowed_out_and_widened_back_returns_without_its_bytes_travelling_again() = behaviour {
        // Absence is the claim, so it is a `never` up to the settle: no settled state records how often a photo left.
        given {
            extensionUploadsOnly()
            joinedEvent()
            shared("EARLY", date = EARLY)
            step("narrowed past EARLY, and withdrawn", act = {
                reconfigure(Choices(cutoff = NARROWED))
                awaitSharesFrom(Instant.parse(NARROWED).toLocalDateTime(zone))
                cycle()
            }, until = {
                event<WorldEvent.UnionChanged> { it.asset == "EARLY" && !it.gained }
                settled { true }
            })
        }

        whenever {
            reconfigure(Choices(range = RangeChoice.EVENT_START))
            awaitSharesFrom(LocalDateTime.parse(Rig.WINDOW_START))
            cycle()
        }
        then {
            event<WorldEvent.UnionChanged> { it.asset == "EARLY" && it.gained }
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The backend records [id] withdrawn: no longer declared, and no longer offered to the event's members. */
    private fun Expectations.withdrawn(id: String) {
        event<WorldEvent.ManifestPublished> { id in it.withdrawn }
        event<WorldEvent.UnionChanged> { it.asset == id && !it.gained }
    }

    /** The joined screen shows the member's range starting at [from] — the settings' change applied. */
    private suspend fun Acts.awaitSharesFrom(from: LocalDateTime) =
        awaitScreen("the range starting $from") { ui -> (ui.layer as? Layer.Joined)?.membership?.sharesFrom == from }

    private companion object {
        /** Days after the photo was deleted, still inside the event's dates. */
        const val DAYS_LATER = "2026-06-05T12:00:00Z"

        /** A week after the default event's range ended. */
        const val A_WEEK_AFTER_THE_END = "2026-06-21T12:00:00Z"

        /** A photo early in the event, and a start the member narrows to, past it. */
        const val EARLY = "2026-05-20T10:00:00Z"
        const val NARROWED = "2026-06-01T00:00:00Z"
    }
}
