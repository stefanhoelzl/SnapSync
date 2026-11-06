package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The counts a bug report says the screen showed (capability `privacy-security`): exactly the counts line's. */
class ShownCountsTest {

    @Test
    fun `a counts line is carried as done over total — and a direction switched off as off`() {
        val state = joinedState(
            counts = SyncCounts(shared = DirectionCount.Progress(3, 10), received = DirectionCount.Off),
        )

        assertEquals(
            mapOf(DiagnosticKeys.SHOWN_SHARED to "3/10", DiagnosticKeys.SHOWN_RECEIVED to "off"),
            shownCounts(state),
        )
    }

    @Test
    fun `receiving switched off keeping what arrived is carried as off with the count kept`() {
        val state = joinedState(counts = SyncCounts(DirectionCount.Progress(3, 3), DirectionCount.Kept(40)))

        assertEquals("off, 40 kept", shownCounts(state)[DiagnosticKeys.SHOWN_RECEIVED])
    }

    @Test
    fun `no counts line showing carries no counts`() {
        assertEquals(emptyMap(), shownCounts(joinedState(counts = null)))
    }

    private fun joinedState(counts: SyncCounts?) = UiState(
        Layer.Joined(
            membership = TestMembership(
                eventId = testEventId("5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e"),
                name = "Anna's Birthday",
                minPhotoDate = CaptureCutoff(timestamp("2026-07-06T14:32:11Z")),
                maxPhotoDate = CaptureCeiling(timestamp("2026-07-13T14:32:11Z")),
                endsAt = EventEnd(timestamp("2099-12-31T00:00:00Z")),
                deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
            ).shown(),
            inviteUrl = "https://snapsync.stho.net/e/x",
            health = SyncHealth.InSync,
            today = LocalDate(2026, 7, 9),
            counts = counts,
        ),
    )
}
