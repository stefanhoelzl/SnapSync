package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.CreateForm
import app.snapsync.model.Layer
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * **The create screen's draft follows the app's foreground life** (capability `create-event`, "The start follows the
 * clock until the host chooses the range" and "A long absence starts a fresh draft"), driven at the OS boundary: the
 * real lifecycle entry points and the mocked clock. Every return to the foreground moves an untouched start to now and
 * keeps what the host typed; only a return after 15 minutes or more away starts a fresh draft.
 */
@Verifies(spec = "create-event", requirement = "A long absence starts a fresh draft")
class CreateDraftIntegrationTest {

    @Test
    fun a_return_within_15_minutes_keeps_the_draft_and_a_later_one_starts_afresh() = rigTest {
        device("clock/advance", "to" to T0)
        os("app", "onForeground")
        user("createName", "name" to "Party")
        val first = awaitForm { it.name == "Party" }

        os("app", "onBackground")
        device("clock/advance", "to" to T0_PLUS_14_MIN)
        os("app", "onForeground")
        val kept = awaitForm { it.picker.from == local(T0_PLUS_14_MIN) }
        assertEquals(first.draft to "Party", kept.draft to kept.name, "the same draft, the name kept")

        os("app", "onBackground")
        device("clock/advance", "to" to T0_PLUS_29_MIN)
        os("app", "onForeground")
        val fresh = awaitForm { it.draft > first.draft }
        assertEquals("" to local(T0_PLUS_29_MIN), fresh.name to fresh.picker.from, "a fresh draft, at now")
    }

    private suspend fun Rig.awaitForm(until: (CreateForm) -> Boolean): CreateForm =
        (awaitState { s -> (s.ui.layer as? Layer.CreateEvent)?.form?.let(until) == true }.ui.layer as Layer.CreateEvent).form

    /** [iso] as the device's wall clock states it — the mocked clock's zone, UTC. */
    private fun local(iso: String): LocalDateTime = Instant.parse(iso).toLocalDateTime(TimeZone.UTC)

    private companion object {
        const val T0 = "2026-06-10T12:00:00Z"
        const val T0_PLUS_14_MIN = "2026-06-10T12:14:00Z"
        const val T0_PLUS_29_MIN = "2026-06-10T12:29:00Z"
    }
}
