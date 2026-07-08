package app.snapsync.integration

import app.snapsync.config.ConfigStore
import app.snapsync.config.EventConfig
import app.snapsync.config.Direction
import app.snapsync.config.EventLinkPayload
import app.snapsync.config.encodeConfigUrl
import app.snapsync.deviceid.DeviceIdentity
import app.snapsync.gallery.DeviceManifest
import app.snapsync.join.EventDetails
import app.snapsync.join.HttpEventDetailsSource
import app.snapsync.join.JoinEvent
import app.snapsync.join.JoinOutcome
import app.snapsync.join.ManifestDeviceEnroller
import app.snapsync.membership.LeaveEvent
import app.snapsync.permission.PermissionRequester
import app.snapsync.presentation.JoinLoad
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.UiState
import app.snapsync.world.World
import app.snapsync.world.foreignManifest
import app.snapsync.world.worldTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EVENT_E = "11111111-1111-4111-8111-111111111111"
private const val EVENT_F = "22222222-2222-4222-8222-222222222222"

/**
 * Seam ↔ UI-state integration for the join gate (capability `join-event`) over the real
 * `engine → status → presentation` stack driven against the world's mini-edge: the deeplink decode,
 * `GET /event/:id` details gate, register-only enrollment PUT, and the switch composition — asserting
 * both `UiState` and world outcomes (config provisioned, manifest membership landed).
 */
class JoinGateIntegrationTest {

    @Test
    fun first_join_loads_details_then_enrolls_and_joins() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.store.registerEvent(EVENT_E, "Anna's Wedding") // exists, but not yet joined
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            assertEquals(
                UiState.JoiningEvent(EVENT_E, JoinPhase.Ready("Anna's Wedding", "2026-01-01T00:00:00Z")),
                host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready },
            )

            host.onConfirmJoin(null, Direction.Both, false)
            host.await { it is UiState.Joined } // config flipped present → joined layer

            // World outcomes: config provisioned + a register-only EMPTY manifest deposited (membership).
            assertEquals(EVENT_E, w.configSource.config.value?.eventId)
            val manifest = w.store.manifestOf(EVENT_E, w.ownDeviceId)
            assertTrue(manifest != null && manifest.assets.isEmpty(), "enrollment writes an empty manifest")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_missing_event_blocks_the_join() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World() // EVENT_E is NOT registered
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase == JoinPhase.NotFound }

            assertNull(w.configSource.config.value)
            assertNull(w.store.manifestOf(EVENT_E, w.ownDeviceId))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_load_failure_is_retryable() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            w.backendOffline = true
            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase == JoinPhase.LoadFailed }

            w.backendOffline = false
            host.onRetryLoad()
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_failed_enrollment_does_not_join() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }

            w.backendOffline = true // enrollment PUT now fails
            host.onConfirmJoin(null, Direction.Both, false)
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.CommitFailed }

            assertNull(w.configSource.config.value) // not joined
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_switch_leaves_the_current_event_then_joins_the_new_one() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision(EVENT_E, "Summer Trip")          // already joined to E
            w.store.registerEvent(EVENT_F, "Anna's Wedding") // F exists to switch to
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_F))
            host.await { (it as? UiState.Joined)?.pendingSwitch?.phase is JoinPhase.Ready }

            host.onConfirmSwitch(null, Direction.Both)
            host.await { it is UiState.Joined && it.pendingSwitch == null && w.configSource.config.value?.eventId == EVENT_F }

            assertEquals(EVENT_F, w.configSource.config.value?.eventId) // switched
            assertTrue(w.store.manifestOf(EVENT_F, w.ownDeviceId) != null) // enrolled in the new event
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_switch_does_not_block_on_the_departed_events_delete() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision(EVENT_E, "Summer Trip")              // already joined to E
            w.store.registerEvent(EVENT_F, "Anna's Wedding") // F exists to switch to

            // Wire the switch's leave to the REAL LeaveEvent with E's DELETE gated so it never completes.
            val deleteGate = CompletableDeferred<Unit>()
            val leaveEvent = LeaveEvent(
                config = w.configStore,
                configSource = w.configSource,
                disableExtension = {},
                notifyLeave = { deleteGate.await() /* hangs */ },
                scope = scope,
            )
            val host = joinHost(w, scope, leave = { leaveEvent.leave() })

            host.onOpenUrl(deeplink(EVENT_F))
            host.await { (it as? UiState.Joined)?.pendingSwitch?.phase is JoinPhase.Ready }

            host.onConfirmSwitch(null, Direction.Both)
            // The new event's join completes even though E's DELETE is still pending — the switch never
            // waits on the departed event's fire-and-forget backend notify.
            host.await {
                it is UiState.Joined && it.pendingSwitch == null && w.configSource.config.value?.eventId == EVENT_F
            }
            assertTrue(w.store.manifestOf(EVENT_F, w.ownDeviceId) != null) // enrolled in the new event
            assertFalse(deleteGate.isCompleted)                            // E's DELETE never gated the join
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun re_scanning_the_joined_event_does_not_clobber_the_manifest() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.provision(EVENT_E, "Anna's Wedding")
            // A real (non-empty) manifest already written by a prior upload cycle.
            w.store.putManifest(EVENT_E, w.ownDeviceId, foreignManifest(w.ownDeviceId, listOf(World.foreignAsset("A"))))
            val host = joinHost(w, scope)
            host.await { it is UiState.Joined }

            host.onOpenUrl(deeplink(EVENT_E)) // same event → no-op, no enrollment PUT

            // Still joined, and the real manifest was NOT clobbered to empty.
            assertEquals(EVENT_E, w.configSource.config.value?.eventId)
            val manifest = w.store.manifestOf(EVENT_E, w.ownDeviceId)
            assertTrue(manifest != null && manifest.assets.isNotEmpty(), "the real manifest must survive a re-scan")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autoJoin_auto_confirms_without_a_confirmation() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            host.onOpenUrl(encodeConfigUrl(EventLinkPayload(EVENT_E, autoJoin = true)))
            host.await { it is UiState.Joined } // straight to joined, no confirm tap

            assertEquals(EVENT_E, w.configSource.config.value?.eventId)
            assertTrue(w.store.manifestOf(EVENT_E, w.ownDeviceId) != null)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun join_persists_the_capture_date_cutoff_through_the_provision_path() = worldTest {
        // Regression: the chosen cutoff must survive decode → autoConfirm → commitJoin → join →
        // provision → config. A wiring that drops it (as an early iOS build did) leaves the extension
        // whole-library. An autoJoin deeplink carries the dev/test cutoff explicitly.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World()
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            val cutoff = "2020-01-01T00:00:00Z"
            host.onOpenUrl(encodeConfigUrl(EventLinkPayload(EVENT_E, autoJoin = true, minPhotoDate = cutoff)))
            host.await { it is UiState.Joined }

            assertEquals(cutoff, w.configSource.config.value?.minPhotoDate, "the cutoff must be persisted in config")
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun deeplink(eventId: String) = encodeConfigUrl(EventLinkPayload(eventId))

    private fun joinHost(
        w: World,
        scope: CoroutineScope,
        leave: suspend () -> Unit = { w.leave() },
    ): StatusContainerHost {
        val joinEvent = JoinEvent(
            configSource = w.configSource,
            deviceIdentity = object : DeviceIdentity { override fun deviceId() = w.ownDeviceId },
            details = HttpEventDetailsSource(w.client, w.host),
            enroller = ManifestDeviceEnroller(w.manifestUploader),
            provision = { cfg -> w.provision(cfg.eventId, cfg.name, cfg.minPhotoDate) },
        )
        return StatusContainerHost(
            syncSource = w.syncStatusSource(scope),
            permissionSource = w.permission,
            requester = NoOpJoinRequester,
            configSource = w.configSource,
            store = NoOpJoinConfigStore,
            scope = scope,
            loadJoinDetails = { id -> joinEvent.loadDetails(id).toJoinLoad() },
            commitJoin = { id, name, cutoff, direction, saveToAlbum -> joinEvent.join(id, name, cutoff, direction, saveToAlbum) != JoinOutcome.EnrollFailed },
            leave = leave,
        )
    }

    private fun EventDetails.toJoinLoad(): JoinLoad = when (this) {
        is EventDetails.Found -> JoinLoad.Found(name, createdAt)
        EventDetails.NotFound -> JoinLoad.NotFound
        EventDetails.Failed -> JoinLoad.Failed
    }

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

private object NoOpJoinRequester : PermissionRequester {
    override fun request() = Unit
    override fun openSettings() = Unit
}

private object NoOpJoinConfigStore : ConfigStore {
    override suspend fun save(config: EventConfig) = Unit
    override suspend fun clear() = Unit
}
