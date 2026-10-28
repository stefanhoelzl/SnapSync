package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.EventKeyText
import app.snapsync.format.InviteLinkText
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **Only photos inside the range the member chose are shared** (`openspec/specs/photo-sharing/spec.md`), the scenarios
 * the capture range decides, one per test, over the real composed stack.
 *
 * A photo is *shared* here when its bytes are stored and the device's manifest declares it — what the event offers
 * every other member; one *kept private* reaches neither. The extension's cycles move the photos ([uploadAll]), and
 * the event is the default one (15 May – 14 June), wider than every range the member picks here.
 */
class CaptureRangeIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A photo taken exactly at the end is shared",
    )
    fun a_photo_taken_at_the_ranges_last_instant_is_shared_and_one_a_minute_later_is_not() = behaviour {
        // The member's range is the event's own dates, so its end is the event's — read in the host's zone (UTC) as
        // the create form gave it, not a custom end converted through the test's zone.
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("ATEND", date = RANGE_END)
            photoTaken("AFTEREND", date = A_MINUTE_AFTER_THE_END) // the control: the end is where the range stops
        }

        whenever { uploadAll() }
        then { sharedAlone("ATEND", kept = listOf("AFTEREND")) }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "The host cannot widen a member's choice",
    )
    fun a_range_narrower_than_the_events_dates_is_the_one_that_shares() = behaviour {
        // Both kept photos lie inside the event's own dates: only the member's later start and earlier end exclude them.
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(cutoff = NARROW_START, until = NARROW_END))
            photoTaken("INSIDE", date = INSIDE_BOTH)
            photoTaken("EARLY", date = BEFORE_THE_MEMBERS_START)
            photoTaken("LATE", date = AFTER_THE_MEMBERS_END)
        }

        whenever { uploadAll() }
        then { sharedAlone("INSIDE", kept = listOf("EARLY", "LATE")) }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "A link cannot widen a member's range",
    )
    fun a_range_an_invite_carries_is_ignored_and_the_join_screens_range_shares() = behaviour {
        // The link reaches from 2001 to 2099; the member joins on the join screen as it stands — the event's dates.
        given {
            extensionUploadsOnly()
            photoTaken("INSIDE")
            photoTaken("LINKONLY", date = BEFORE_THE_EVENT)
            val event = registeredEvent()
            val link = step("an invite carrying a range", act = { linkWithRange(event) }, until = {})
            step("its join gate open", act = { openLink(link) }, until = {
                screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed }
            })
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
        }

        whenever { uploadAll() }
        then { sharedAlone("INSIDE", kept = listOf("LINKONLY")) }
    }

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Only photos inside the range the member chose are shared",
        scenario = "An undated photo is not shared",
    )
    fun a_photo_the_library_holds_no_capture_date_for_stays() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("UNDATED", date = NO_CAPTURE_DATE)
            photoTaken("DATED")
        }

        whenever { uploadAll() }
        then { sharedAlone("DATED", kept = listOf("UNDATED")) }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** [eventId]'s whole invite — an encrypted event's key included — with a capture range, as links once carried one. */
    private suspend fun Acts.linkWithRange(eventId: String): String {
        val key = (InviteLinkText.parse(host.invite(eventId)) as InviteLinkVerdict.Invite).link.key
        return "$LINK_ORIGIN/join/$eventId#" + listOfNotNull(
            key?.let { "k=${EventKeyText.encode(it)}" },
            "minPhotoDate=2001-01-01T00:00:00Z",
            "maxPhotoDate=2099-01-01T00:00:00Z",
        ).joinToString("&")
    }

    /** [shared]'s bytes are stored, and none of [kept] is uploaded or declared to the event. */
    private fun Expectations.sharedAlone(shared: String, kept: List<String>) {
        event<WorldEvent.BytesStored> { it.asset == shared }
        kept.forEach { id ->
            never<WorldEvent.BytesStored> { it.asset == id }
            never<WorldEvent.ManifestPublished> { id in it.added }
        }
    }

    private companion object {
        /** The default event's end ([Rig.WINDOW_END]) as the host reads it: the last instant of the member's range. */
        const val RANGE_END = "2026-06-14T00:00:00Z"
        const val A_MINUTE_AFTER_THE_END = "2026-06-14T00:01:00Z"

        /** A range the member picked inside the event's dates, starting later and ending earlier. */
        const val NARROW_START = "2026-05-25T12:00:00Z"
        const val NARROW_END = "2026-05-31T12:00:00Z"
        const val INSIDE_BOTH = "2026-05-28T10:00:00Z"
        const val BEFORE_THE_MEMBERS_START = "2026-05-20T10:00:00Z"
        const val AFTER_THE_MEMBERS_END = "2026-06-01T10:00:00Z"

        /** Two weeks before the event's start: outside its dates, inside the range a link may carry. */
        const val BEFORE_THE_EVENT = "2026-05-01T10:00:00Z"

        /** A text the library cannot read as a time: a photo it holds no capture date for. */
        const val NO_CAPTURE_DATE = "undated"
    }
}
