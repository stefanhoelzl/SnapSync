package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.CreateDraftSession
import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The create screen's draft follows the app's foreground life**, driven at the OS boundary: the real lifecycle
 * entry points and the mocked clock. Every return to the foreground is a new activation — an untouched start moves
 * to now — and only a return after 15 minutes or more away starts a fresh draft.
 */
@Verifies(spec = "create-event", requirement = "A long absence starts a fresh draft")
class CreateDraftIntegrationTest {

    @Test
    fun a_return_within_15_minutes_keeps_the_draft_and_a_later_one_starts_afresh() = rigTest {
        device("clock/advance", "to" to T0)
        os("app", "onForeground")
        val first = awaitDraft { it.activation >= 1 }

        os("app", "onBackground")
        device("clock/advance", "to" to T0_PLUS_14_MIN)
        os("app", "onForeground")
        assertEquals(
            CreateDraftSession(first.activation + 1, first.epoch),
            awaitDraft { it.activation > first.activation },
        )

        os("app", "onBackground")
        device("clock/advance", "to" to T0_PLUS_29_MIN)
        os("app", "onForeground")
        assertEquals(
            CreateDraftSession(first.activation + 2, first.epoch + 1),
            awaitDraft {
                it.activation > first.activation + 1
            },
        )
    }

    private suspend fun Rig.awaitDraft(until: (CreateDraftSession) -> Boolean): CreateDraftSession =
        (awaitState { s -> (s.ui.layer as? Layer.CreateEvent)?.draft?.let(until) == true }.ui.layer as Layer.CreateEvent).draft

    private companion object {
        const val T0 = "2026-06-10T12:00:00Z"
        const val T0_PLUS_14_MIN = "2026-06-10T12:14:00Z"
        const val T0_PLUS_29_MIN = "2026-06-10T12:29:00Z"
    }
}
