package app.snapsync.integration

import app.snapsync.feature.membership.RenameFailureReason
import app.snapsync.feature.membership.RenameStatus
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.RenameState
import app.snapsync.presentation.StatusContainerHost
import kotlinx.coroutines.flow.mapNotNull
import app.snapsync.presentation.UiState
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Seam ↔ UI-state integration for the event rename (capability `event-rename`), driven against the REAL
 * core over the world (`:test:world`) through the composed `UserCommands.rename` — asserting **`UiState`
 * AND world outcomes**: the mini-edge's marker carries the new name, the membership config carries the
 * ECHOED name, the screen-level heading value follows, and a failure destroys nothing.
 *
 * Runs on JVM and `iosSimulatorArm64`.
 */
class RenameIntegrationTest {

    @Test
    fun a_rename_rewrites_the_backend_marker_and_the_heading_follows() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", name = "Weekend")
            val host = statusHost(w, scope)
            assertEquals("Weekend", host.joinedName(), "the heading starts at the joined name")

            w.userCommands.rename("E", "Ana's 30th")
            host.awaitRename { it == RenameState.Succeeded }

            // The world outcome: the shared marker the OTHER members read is what changed.
            assertEquals("Ana's 30th", w.store.nameOf("E"), "the backend marker carries the new name")
            // The membership outcome: this device's config followed, and nothing else moved.
            val config = assertNotNull(w.configSource.config.value)
            assertEquals("Ana's 30th", config.name)
            assertEquals("E", config.eventId, "same membership — a rename never re-joins")
            // The UI outcome: the screen-level heading value the status screen renders.
            assertEquals("Ana's 30th", host.awaitName("Ana's 30th"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_backend_TRIMMED_name_is_what_lands_everywhere() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", name = "Weekend")
            val host = statusHost(w, scope)

            w.userCommands.rename("E", "   Ana's 30th   ")
            host.awaitRename { it == RenameState.Succeeded }

            // One value, three places, no whitespace anywhere: the echo is the single source.
            assertEquals("Ana's 30th", w.store.nameOf("E"))
            assertEquals("Ana's 30th", w.configSource.config.value?.name)
            assertEquals("Ana's 30th", host.awaitName("Ana's 30th"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_rejected_name_fails_and_changes_nothing() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", name = "Weekend")
            val host = statusHost(w, scope)

            // Over the mini-edge's (and the real backend's) 100-character bound.
            w.userCommands.rename("E", "x".repeat(101))
            val status = host.awaitRename { it is RenameState.Failed }

            assertEquals(RenameState.Failed("That name wasn't accepted. Try a shorter one."), status)
            assertEquals("Weekend", w.store.nameOf("E"), "the marker is untouched")
            assertEquals("Weekend", w.configSource.config.value?.name, "the config is untouched")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_rename_of_a_SWEPT_event_leaves_the_membership_joined_and_intact() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", name = "Weekend")
            val before = assertNotNull(w.configSource.config.value)
            val host = statusHost(w, scope)

            // The nightly sweep deleted the event — every subsequent request 404s.
            w.store.sweepEvent("E")

            w.userCommands.rename("E", "Ana's 30th")
            val status = host.awaitRename { it is RenameState.Failed }

            // A 404 arrives as the GENERIC failure — it has no distinct meaning here by design.
            assertEquals(
                RenameState.Failed("Couldn't rename the event. Check your connection and try again."),
                status,
            )
            // THE INVARIANT: a 404 is ONE witness, and the self-leave needs two (capability
            // `leave-event`). The membership must survive a rename against a swept event byte for byte —
            // the config is the only record of the join, and losing it is unrecoverable.
            assertEquals(before, w.configSource.config.value, "the membership survives the 404 unchanged")
            assertEquals("Weekend", host.joinedName(), "…and so does the heading")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_rename_landing_after_a_switch_does_not_rename_the_new_membership() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E1", name = "Weekend")
            val host = statusHost(w, scope)

            // The member switches while the dialog is still open, then confirms the OLD event's rename.
            w.provision("E2", name = "Other Event")
            w.userCommands.rename("E1", "Ana's 30th")
            host.awaitRename { it == RenameState.Succeeded }

            // The backend rename is real — E1 IS renamed, for whoever is still in it.
            assertEquals("Ana's 30th", w.store.nameOf("E1"))
            // But this device's membership is E2 now, and the eventId guard kept it out of the write.
            assertEquals("E2", w.configSource.config.value?.eventId)
            assertEquals("Other Event", w.configSource.config.value?.name, "the new membership is untouched")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_reset_command_clears_the_latch_so_a_second_rename_starts_clean() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision("E", name = "Weekend")
            val host = statusHost(w, scope)

            w.userCommands.rename("E", "First")
            host.awaitRename { it == RenameState.Succeeded }
            w.userCommands.resetRename()
            assertEquals(RenameState.Idle, host.joinedRenameStatus(), "the latch is cleared")

            w.userCommands.rename("E", "Second")
            host.awaitRename { it == RenameState.Succeeded }
            assertEquals("Second", w.store.nameOf("E"))
            assertTrue(w.configSource.config.value?.name == "Second")
        } finally {
            scope.cancel()
        }
    }

    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
            rename = w.renameStatus,
        ),
        scope = scope,
        commands = w.userCommands,
        cutoffFormatter = fixedRenameCutoffFormatter(),
    )

    // The heading's name and the rename lifecycle are fields of the joined state now (capability
    // `sync-status-screen`), so these read what the screen renders rather than a sibling read-model.
    private fun StatusContainerHost.joined(): Layer.Joined? =
        container.stateFlow.value.layer as? Layer.Joined

    private fun StatusContainerHost.joinedName(): String? = joined()?.membership?.name

    private fun StatusContainerHost.joinedRenameStatus(): RenameState? = joined()?.renameState

    private suspend fun StatusContainerHost.awaitName(name: String): String? = withTimeout(5_000) {
        container.stateFlow.first { ((it as UiState).layer as? Layer.Joined)?.membership?.name == name }
            .let { ((it as UiState).layer as Layer.Joined).membership.name }
    }

    private suspend fun StatusContainerHost.awaitRename(predicate: (RenameState) -> Boolean): RenameState =
        withTimeout(5_000) {
            container.stateFlow
                .mapNotNull { ((it as UiState).layer as? Layer.Joined)?.renameState }
                .first(predicate)
        }
}

private fun fixedRenameCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
