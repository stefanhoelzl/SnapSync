package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app menu's model half (capability `sync-status`): where it is offered, and where its links point. */
class AppMenuTest {

    @Test
    fun the_links_are_pages_of_the_invite_links_own_site() {
        assertEquals(LINK_ORIGIN, AppLink.WEBSITE.url)
        assertEquals("$LINK_ORIGIN/#privacy", AppLink.PRIVACY_POLICY.url)
    }

    @Test
    fun the_menu_is_offered_everywhere_but_the_settings_surface_and_a_join_or_create_in_progress() {
        val details = EventDetails(
            "Anna's Birthday",
            eventStart("2026-07-06T00:00:00Z"),
            eventEnd("2026-07-13T00:00:00Z"),
            deletesAt("2026-08-05T00:00:00Z"),
        )
        val range = ResolvedRange(
            windowStart = LocalDateTime(2026, 7, 6, 0, 0),
            windowEnd = LocalDateTime(2026, 7, 13, 0, 0),
            from = LocalDateTime(2026, 7, 6, 0, 0),
            until = LocalDateTime(2026, 7, 13, 0, 0),
            chosenFrom = captureCutoff("2026-07-06T00:00:00Z"),
            chosenUntil = captureCeiling("2026-07-13T00:00:00Z"),
            direction = Direction.Both,
            commitEnabled = true,
            nowAvailable = true,
            shareCount = ShareCount.Unavailable,
        )
        val offered = listOf(
            Layer.UpdateRequired(),
            Layer.CreateEvent(),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.Loading)),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.LoadFailed)),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.NotFound)),
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(joinPhase(JoinPhase.Detailed.Step.Ready, details), range),
            ),
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(joinPhase(JoinPhase.Detailed.Step.CommitFailed, details), range),
            ),
        )
        val membership = EventConfig(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Anna's Birthday",
            minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
            maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
            endsAt = eventEnd("2099-12-31T00:00:00Z"),
            deletesAt = deletesAt("2099-12-31T00:00:00Z"),
        )
        val joined = Layer.Joined(
            membership = membership,
            inviteUrl = "$LINK_ORIGIN/join#v=3&d=x",
            health = SyncHealth.InSync,
        )
        for (layer in offered + joined + joined.copy(closed = true)) assertTrue(layer.offersMenu, "$layer")
        assertFalse(joined.copy(surface = JoinedSurface.Reconfigure(RangeForm(), range)).offersMenu)
        assertFalse(Layer.CreatingEvent.offersMenu)
        assertFalse(
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(joinPhase(JoinPhase.Detailed.Step.Committing, details), range),
            ).offersMenu,
        )
    }
}
