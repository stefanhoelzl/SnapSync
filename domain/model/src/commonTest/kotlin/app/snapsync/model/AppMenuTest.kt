package app.snapsync.model

import kotlinx.datetime.LocalDate
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
        val start = LocalDateTime(2026, 7, 6, 0, 0)
        val end = LocalDateTime(2026, 7, 13, 0, 0)
        val range = RangeView(
            shareOn = true,
            receiveOn = true,
            saveToAlbum = true,
            albumStyle = AlbumStyle.COLLECTION,
            preset = RangeChoice.WHOLE_EVENT,
            from = start,
            until = end,
            windowStart = start,
            windowEnd = end,
            nowAvailable = true,
            today = LocalDate(2026, 7, 6),
            commitEnabled = true,
            shareCount = ShareCount.Unavailable,
        )
        fun phase(step: JoinPhase.Detailed.Step) = JoinPhase.Detailed("Anna's Birthday", step)
        val offered = listOf(
            Layer.UpdateRequired(),
            Layer.CreateEvent(),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.Loading)),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.LoadFailed)),
            Layer.JoiningEvent(eventId = "E", stage = JoinStage.Unloaded(JoinPhase.NotFound)),
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(phase(JoinPhase.Detailed.Step.Ready), range),
            ),
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(phase(JoinPhase.Detailed.Step.CommitFailed), range),
            ),
        )
        val membership = JoinedEvent(
            eventId = "11111111-1111-4111-8111-111111111111",
            name = "Anna's Birthday",
            startsAt = start,
            endsAt = end,
            sharesFrom = start,
            sharesUntil = end,
            shares = true,
            receives = true,
            savesToAlbum = true,
        )
        val joined = Layer.Joined(
            membership = membership,
            inviteUrl = "$LINK_ORIGIN/join#v=3&d=x",
            health = SyncHealth.InSync,
            today = LocalDate(2026, 7, 6),
        )
        for (layer in offered + joined + joined.copy(closed = true)) assertTrue(layer.offersMenu, "$layer")
        assertFalse(joined.copy(surface = JoinedSurface.Reconfigure(range)).offersMenu)
        assertFalse(Layer.CreatingEvent.offersMenu)
        assertFalse(
            Layer.JoiningEvent(
                eventId = "E",
                stage = JoinStage.Loaded(phase(JoinPhase.Detailed.Step.Committing), range),
            ).offersMenu,
        )
    }
}
