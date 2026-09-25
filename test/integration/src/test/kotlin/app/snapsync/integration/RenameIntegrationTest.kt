package app.snapsync.integration

import app.snapsync.model.RenameState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Seam ↔ UI-state integration for the event rename (capability `manage-membership`), driven through the control
 * protocol's `/user/rename` over the real core — asserting **`UiState` AND backend outcomes**: the backend's event
 * carries the new name, the joined membership carries the ECHOED name (which is the heading the status screen
 * renders), and a failure destroys nothing.
 */
class RenameIntegrationTest {

    @Test
    fun a_rename_rewrites_the_backend_marker_and_the_heading_follows() = rigTest {
        val event = createAndJoin(name = "Weekend")
        assertEquals("Weekend", heading(), "the heading starts at the joined name")

        user("rename", "name" to "Ana's 30th")
        awaitRename { it == RenameState.Succeeded }

        // The backend outcome: the shared marker the OTHER members read is what changed.
        assertEquals("Ana's 30th", backendName(event), "the backend marker carries the new name")
        // The membership outcome: this device's membership followed, and nothing else moved. Its name IS the
        // heading the status screen renders.
        val membership = awaitState { it.joined?.membership?.name == "Ana's 30th" }.joined!!.membership
        assertEquals(event, membership.eventId, "same membership — a rename never re-joins")
    }

    @Test
    fun the_backend_TRIMMED_name_is_what_lands_everywhere() = rigTest {
        val event = createAndJoin(name = "Weekend")

        user("rename", "name" to "   Ana's 30th   ")
        awaitRename { it == RenameState.Succeeded }

        // One value, both places, no whitespace anywhere: the echo is the single source.
        assertEquals("Ana's 30th", backendName(event))
        awaitState { it.joined?.membership?.name == "Ana's 30th" }
    }

    @Test
    fun a_rejected_name_fails_and_changes_nothing() = rigTest {
        val event = createAndJoin(name = "Weekend")

        // Over the mini-edge's (and the real backend's) 100-character bound.
        user("rename", "name" to "x".repeat(101))
        val status = awaitRename { it is RenameState.Failed }

        assertEquals(RenameState.Failed("That name wasn't accepted. Try a shorter one."), status)
        assertEquals("Weekend", backendName(event), "the marker is untouched")
        assertEquals("Weekend", heading(), "the membership is untouched")
    }

    @Test
    fun a_rename_of_a_SWEPT_event_leaves_the_membership_joined_and_intact() = rigTest {
        val event = createAndJoin(name = "Weekend")
        val before = assertNotNull(state().joined).membership

        // The nightly sweep deleted the event — every subsequent request 404s.
        device("backend/sweep", "event" to event)

        user("rename", "name" to "Ana's 30th")
        val status = awaitRename { it is RenameState.Failed }

        // A 404 arrives as the GENERIC failure — it has no distinct meaning here by design.
        assertEquals(
            RenameState.Failed("Couldn't rename the event. Check your connection and try again."),
            status,
        )
        // THE INVARIANT: a 404 is ONE witness, and the self-leave needs two (capability `manage-membership`). The
        // membership must survive a rename against a swept event byte for byte — the config is the only record of
        // the join, and losing it is unrecoverable.
        assertEquals(before, state().joined?.membership, "the membership survives the 404 unchanged")
        assertEquals("Weekend", heading(), "…and so does the heading")
    }

    @Test
    fun the_reset_command_clears_the_latch_so_a_second_rename_starts_clean() = rigTest {
        val event = createAndJoin(name = "Weekend")

        user("rename", "name" to "First")
        awaitRename { it == RenameState.Succeeded }
        user("renameStatusConsumed")
        assertEquals(RenameState.Idle, state().joined?.renameState, "the latch is cleared")

        user("rename", "name" to "Second")
        awaitRename { it == RenameState.Succeeded }
        assertEquals("Second", backendName(event))
        awaitState { it.joined?.membership?.name == "Second" }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The heading the joined screen renders: the membership's name. */
    private suspend fun Rig.heading(): String? = state().joined?.membership?.name

    private suspend fun Rig.awaitRename(until: (RenameState) -> Boolean): RenameState =
        awaitState { s -> s.joined?.renameState?.let(until) == true }.joined!!.renameState

    /** The name the backend serves for [event]. */
    private suspend fun Rig.backendName(event: String): String? =
        deviceJson("backend/event", "event" to event)["name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}
