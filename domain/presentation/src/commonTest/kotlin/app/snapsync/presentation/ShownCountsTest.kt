package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.EventConfig
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
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
    fun `no counts line showing carries no counts`() {
        assertEquals(emptyMap(), shownCounts(joinedState(counts = null)))
    }

    private fun joinedState(counts: SyncCounts?) = UiState(
        Layer.Joined(
            membership = EventConfig(
                eventId = "5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e",
                name = "Anna's Birthday",
                minPhotoDate = CaptureCutoff(CaptureDate.parse("2026-07-06T14:32:11Z")),
                maxPhotoDate = CaptureCeiling(CaptureDate.parse("2026-07-13T14:32:11Z")),
                endsAt = eventEnd("2099-12-31T00:00:00Z"),
                deletesAt = deletesAt("2099-12-31T00:00:00Z"),
            ).shown(),
            inviteUrl = "https://snapsync.stho.net/e/x",
            health = SyncHealth.InSync,
            today = LocalDate(2026, 7, 9),
            counts = counts,
        ),
    )
}
