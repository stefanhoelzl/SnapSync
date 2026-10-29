package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShareCount
import kotlin.test.Test

/**
 * The join-time **shareable-count preview** over the **real** stack: the count the join surface shows must equal
 * the set the real `UploadCycle` uploads for the same cutoff — the one-universe requirement. Same `snapSyncApp`
 * core the device shells call; only PhotoKit is faked. The preview is read where a member reads it: on the open join
 * gate, before the commit.
 */
class ShareableCountIntegrationTest {

    @Test
    @Verifies(spec = "join-event", requirement = "The join screen shows how many photos will be shared")
    @Verifies(
        spec = "photo-sharing",
        requirement = "What the member is told is shared is exactly what is shared",
        scenario = "The preview matches the upload",
    )
    fun the_preview_count_equals_the_set_the_cycle_uploads() = behaviour {
        given {
            extensionUploadsOnly()
            photoTaken("CAM") // an ordinary camera photo — admitted
            photoTaken("SHOT", LibraryPhotoKind.SCREENSHOT) // excluded by subtype
            photoTaken("WA", LibraryPhotoKind.LOW_RES) // 1.9 MP → below the 3 MP floor, excluded
        }

        // The preview for the same cutoff the cycle applies: exactly the camera photo.
        whenever { create() }
        then { shareCount(ShareCount.Ready(1)) } // the preview counts only the admitted photo

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }

        // …and the cycle uploads exactly that set — the count is not a separate, looser rule.
        whenever { cycle() }
        then {
            times(1) { event<WorldEvent.UploadJobCreated>() }
            settled { it.state.inFlight?.jobs == listOf(primaryJob("CAM")) }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen shows how many photos will be shared",
        scenario = "The count follows the range",
    )
    fun a_later_candidate_cutoff_previews_fewer_photos() = behaviour {
        given {
            photoTaken("OLD", date = "2026-05-20T00:00:00Z")
            photoTaken("NEW", date = "2026-06-10T00:00:00Z")
            createdEvent()
        }

        // A candidate cutoff between the two dates admits only NEW — the preview tracks the chosen cutoff.
        whenever { setRange(Choices(cutoff = "2026-05-16T00:00:00Z")) }
        then { shareCount(ShareCount.Ready(2)) }
        whenever { setRange(Choices(cutoff = "2026-06-01T00:00:00Z")) }
        then { shareCount(ShareCount.Ready(1)) }
        whenever { setRange(Choices(cutoff = "2026-06-12T00:00:00Z")) }
        then { shareCount(ShareCount.Ready(0)) }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen shows how many photos will be shared",
        scenario = "Zero is explained",
    )
    fun a_range_holding_none_of_the_member_s_photos_previews_a_ready_zero() = behaviour {
        // A counted zero, not an absent count: the join screen words exactly this state as "0 photos from your gallery"
        // with the note that new photos will be shared as they are taken (`ParticipationSections`, `JoinScreenTest`).
        given { photoTaken("OLD", date = "2026-04-20T10:00:00Z") } // weeks before the event's range

        whenever { create() }
        then { shareCount(ShareCount.Ready(0)) }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen shows how many photos will be shared",
        scenario = "No count without access",
    )
    fun no_count_without_a_usable_grant() = behaviour {
        given { photos("CAM") }

        // Counted under the grant — so an absent count below is the grant's doing.
        whenever { create() }
        then { shareCount(ShareCount.Ready(1)) }

        whenever { library.permission(PhotoGrant.DENIED) }
        then { shareCount(ShareCount.Unavailable) } // a denied grant yields no count

        whenever { library.permission(PhotoGrant.GRANTED) }
        then { shareCount(ShareCount.Ready(1)) }
        whenever { library.permission(PhotoGrant.NOT_DETERMINED) }
        then { shareCount(ShareCount.Unavailable) } // an unresolved grant yields no count
    }

    /** The create form for the default event, filled and confirmed: the join gate opens on it. */
    private suspend fun Acts.create() = createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
}

/** The open join gate's shareable-count preview reads [expected]. */
private fun Expectations.shareCount(expected: ShareCount) =
    screen<Layer.JoiningEvent> { it.range?.shareCount == expected }
