package app.snapsync.integration

import app.snapsync.model.Direction
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.DeviceManifest
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.model.UserCommands
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.presentation.CutoffFormatter
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
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
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
/** A membership always carries a cutoff (capability `photo-selection-policy`). */
private const val CUTOFF = "2026-01-01T00:00:00Z"

/** The event window ceiling the mini-edge stamps (startsAt + 30d). */
private const val ENDS = "2026-01-31T00:00:00Z"

class JoinGateIntegrationTest {

    @Test
    fun the_join_gate_normalizes_a_legacy_events_millisecond_startsAt_and_commits_what_it_showed() = worldTest {
        // A LEGACY event — registered with no `startsAt`, as every marker written before start dates
        // existed. The mini-edge synthesizes one from `createdAt`, which (faithfully to the real backend's
        // `toISOString()`) carries MILLISECONDS. The loaded phase must therefore show a SECOND-PRECISION
        // value (the `photo-selection-policy` format invariant the iOS fetch predicate depends on), and
        // confirming must persist precisely what the surface displayed.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            val phase = (host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }
                as UiState.JoiningEvent).phase as JoinPhase.Ready

            assertEquals("2026-01-01T00:00:00Z", phase.startsAt, "a synthesized millisecond startsAt is truncated")
            assertTrue(!phase.startsAt.contains('.'), "a cutoff never carries fractional seconds")

            // Confirm with exactly what the surface showed — the round-trip through the real screen.
            host.onConfirmJoin(phase.startsAt, phase.endsAt, Direction.Both, false)
            host.await { it is UiState.Joined }

            assertEquals(
                phase.startsAt,
                w.configSource.config.value?.minPhotoDate,
                "the persisted cutoff is the one the join surface displayed",
            )
            assertEquals(
                phase.startsAt,
                w.configSource.config.value?.startsAt,
                "and the event's start is persisted alongside it, as the floor",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun first_join_loads_details_then_enrolls_and_joins() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding") // exists, but not yet joined
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            assertEquals(
                UiState.JoiningEvent(EVENT_E, JoinPhase.Ready(
                        "Anna's Wedding",
                        "2026-01-01T00:00:00Z",
                        "2026-01-31T00:00:00Z",
                        // The world edge derives it exactly as the real one does:
                        // `max(createdAt, startsAt) + 30d` (capability `event-limits`).
                        "2026-01-31T00:00:00Z",
                    )),
                host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready },
            )

            host.onConfirmJoin(CUTOFF, ENDS, Direction.Both, false)
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
            val w = World(this) // EVENT_E is NOT registered
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
            val w = World(this)
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
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }

            w.backendOffline = true // enrollment PUT now fails
            host.onConfirmJoin(CUTOFF, ENDS, Direction.Both, false)
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
            val w = World(this)
            w.provision(EVENT_E, "Summer Trip")          // already joined to E
            w.store.registerEvent(EVENT_F, "Anna's Wedding") // F exists to switch to
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_F))
            host.await { (it as? UiState.Joined)?.pendingSwitch?.phase is JoinPhase.Ready }

            host.onConfirmSwitch(CUTOFF, ENDS, Direction.Both)
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
            val w = World(this)
            w.provision(EVENT_E, "Summer Trip")              // already joined to E
            w.store.registerEvent(EVENT_F, "Anna's Wedding") // F exists to switch to

            // Wire the switch's leave to the REAL LeaveEvent with E's DELETE gated so it never completes.
            val deleteGate = CompletableDeferred<Unit>()
            val leaveEvent = LeaveEvent(
                config = w.configStore,
                configSource = w.configSource,
                stopUploads = {},
                notifyLeave = { deleteGate.await() /* hangs */ },
                scope = scope,
            )
            val host = joinHost(w, scope, leave = { leaveEvent.leave() })

            host.onOpenUrl(deeplink(EVENT_F))
            host.await { (it as? UiState.Joined)?.pendingSwitch?.phase is JoinPhase.Ready }

            host.onConfirmSwitch(CUTOFF, ENDS, Direction.Both)
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
            val w = World(this)
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
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)

            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_E, autoJoin = true)))
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
        //
        // The cutoff here is ABOVE the event's start, so the floor binds nothing and it lands verbatim.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding", startsAt = "2026-01-01T00:00:00Z")
            val host = joinHost(w, scope)

            val cutoff = "2026-06-15T00:00:00Z"
            host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_E, autoJoin = true, minPhotoDate = cutoff)))
            host.await { it is UiState.Joined }

            assertEquals(cutoff, w.configSource.config.value?.minPhotoDate, "the cutoff must be persisted in config")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_hostile_autoJoin_deeplink_cannot_widen_the_membership_below_the_event_start() = worldTest {
        // THE attack the floor closes, proven end-to-end through the real stack.
        //
        // `minPhotoDate` is decoded from ANY event link — it is documented as a dev/test key, but
        // nothing stops an attacker putting it in a QR. Before the floor, a QR carrying `autoJoin=true`
        // plus a distant-past cutoff auto-confirmed a join at near-whole-library scope WITHOUT A TAP:
        // every photo the guest had ever taken would upload into a stranger's event.
        //
        // The clamp lives in `JoinEvent`, which every entry path funnels through — including this headless
        // one, which has no surface on which a user could notice anything was wrong.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            val startsAt = "2026-01-01T00:00:00Z"
            w.store.registerEvent(EVENT_E, "Anna's Wedding", startsAt = startsAt)
            val host = joinHost(w, scope)

            host.onOpenUrl(
                encodeEventUrl(
                    EventLinkPayload(EVENT_E, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z"),
                ),
            )
            host.await { it is UiState.Joined }

            val config = w.configSource.config.value
            assertEquals(startsAt, config?.minPhotoDate, "the 2001 cutoff must not survive the clamp")
            assertEquals(startsAt, config?.startsAt)
            assertTrue(
                config!!.minPhotoDate >= config.startsAt,
                "the floor invariant holds for every reachable membership",
            )
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun deeplink(eventId: String) = encodeEventUrl(EventLinkPayload(eventId))

    // ── The membership self-leave (capability `leave-event`) ────────────────────────────────────────
    //
    // The one path that destroys user state without a tap. It runs over the REAL composition — the same
    // `Foreground` flow and `MembershipRefresh` rule the iOS shell wires — so these prove the WIRING, not
    // just the rule (`MembershipRefreshTest` covers the verdict matrix in isolation).

    @Test
    fun a_swept_event_returns_the_device_to_unjoined_on_the_next_foreground() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)
            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }
            host.onConfirmJoin(CUTOFF, ENDS, Direction.Both, false)
            host.await { it is UiState.Joined }
            assertEquals(EVENT_E, w.configSource.config.value?.eventId)

            // The nightly sweep deletes the event out from under a still-active member, and time moves
            // past the deadline the membership persisted at join. BOTH witnesses now hold.
            w.store.sweepEvent(EVENT_E)
            w.nowMillis = Instant.parse("2027-01-01T00:00:00Z").toEpochMilliseconds()

            w.core.foregroundFlow.run()
            host.await { it is UiState.CreateEvent }
            assertNull(
                w.configSource.config.value,
                "the membership is torn down and the device is back at the setup gate",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun a_transient_details_failure_never_tears_the_membership_down() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)
            host.onOpenUrl(deeplink(EVENT_E))
            host.await { (it as? UiState.JoiningEvent)?.phase is JoinPhase.Ready }
            host.onConfirmJoin(CUTOFF, ENDS, Direction.Both, false)
            host.await { it is UiState.Joined }
            val joined = w.configSource.config.value

            // The event is very much alive; the backend just cannot be reached. Past the deadline too, so
            // ONLY the confirmed-absence witness is missing — exactly the systemic-fault shape.
            w.store.offline = true
            w.nowMillis = Instant.parse("2027-01-01T00:00:00Z").toEpochMilliseconds()

            w.core.foregroundFlow.run()
            // A NEGATIVE assertion, so it needs a bounded wait rather than an await-until: give the
            // flow's escaping launch real time to do the wrong thing, and assert it never does.
            assertNull(
                withTimeoutOrNull(500) { w.configSource.config.first { it == null } },
                "a fetch that could not tell is never destructive",
            )
            assertEquals(joined, w.configSource.config.value, "the membership is untouched")
        } finally {
            scope.cancel()
        }
    }

    private fun joinHost(
        w: World,
        scope: CoroutineScope,
        leave: suspend () -> Unit = { w.leave() },
    ): StatusContainerHost =
        // The COMPOSED join gate (migration step 10): `w.joinEvent` and `commitJoin` are
        // `AppCore`'s — the same use-case + bundle command the iOS shell wires — over the world's
        // mini-edge; only the leave edge stays injectable (the switch test gates it).
        StatusContainerHost(
            syncSource = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
            scope = scope,
            loadJoinDetails = { id -> w.joinEvent.loadDetails(id).toJoinLoad() },
            commands = UserCommands(
                leave = leave,
                commitJoin = w.userCommands.commitJoin,
            ),
            cutoffFormatter = fixedCutoffFormatter(),
        )

    private suspend fun StatusContainerHost.await(predicate: (UiState) -> Boolean): UiState =
        withTimeout(5_000) { container.stateFlow.first(predicate) }
}

/** A real formatter on a fixed UTC instant — the host requires one since step 9 (no system default). */
private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse("2026-07-09T12:00:00Z") },
    zone = TimeZone.UTC,
)
