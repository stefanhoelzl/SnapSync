package app.snapsync.integration

import app.snapsync.model.eventStart
import app.snapsync.model.eventEnd
import app.snapsync.model.deletesAt
import app.snapsync.model.captureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.Direction
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.DeviceManifest
import app.snapsync.feature.membership.LeaveEvent
import app.snapsync.model.UserCommands
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.presentation.Layer
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.EventDetails
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.step
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
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

/** A membership always carries a cutoff (capability `photo-selection-policy`). */
private val CUTOFF = captureCutoff("2026-01-01T00:00:00Z")

/** The event window ceiling the mini-edge stamps (startsAt + 30d). */
private val ENDS = captureCeiling("2026-01-31T00:00:00Z")

/**
 * Seam ↔ UI-state integration for the join gate (capability `join-event`) over the real
 * `engine → status → presentation` stack driven against the world's mini-edge: the deeplink decode,
 * `GET /event/:id` details gate, register-only enrollment PUT, and the switch composition — asserting
 * both `UiState` and world outcomes (config provisioned, manifest membership landed).
 */
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
            val phase = ((host.await {
                ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready
            }.layer as Layer.JoiningEvent).phase as JoinPhase.Detailed).event

            assertEquals(eventStart("2026-01-01T00:00:00Z"), phase.startsAt, "a synthesized millisecond startsAt is truncated")
            assertTrue(!phase.startsAt.at.iso.contains('.'), "a cutoff never carries fractional seconds")

            // Confirm with exactly what the surface showed — the round-trip through the real screen.
            host.confirmJoinAs()
            host.await { it.layer is Layer.Joined }

            assertEquals(
                CaptureCutoff(phase.startsAt.at),
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
            // The event and the phase the gate reached — not the whole state: a loaded phase also carries
            // the range the reduction resolved, and restating that here would assert the resolution rules
            // a second time. `RangeResolutionTest` owns those.
            val gate = host.await {
                ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready
            }.layer as Layer.JoiningEvent
            assertEquals(EVENT_E, gate.eventId)
            assertEquals(
                phaseAt(
                    JoinPhase.Detailed.Step.Ready,
                    "Anna's Wedding",
                    eventStart("2026-01-01T00:00:00Z"),
                    eventEnd("2026-01-31T00:00:00Z"),
                    // The world edge derives it exactly as the real one does:
                    // `max(createdAt, startsAt) + 30d` (capability `event-limits`).
                    deletesAt("2026-01-31T00:00:00Z"),
                ),
                gate.phase,
            )

            host.confirmJoinAs()
            host.await { it.layer is Layer.Joined } // config flipped present → joined layer

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
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase == JoinPhase.NotFound }

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
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase == JoinPhase.LoadFailed }

            w.backendOffline = false
            host.onRetryLoad()
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready }
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
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready }

            w.backendOffline = true // enrollment PUT now fails
            host.confirmJoinAs()
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.CommitFailed }

            assertNull(w.configSource.config.value) // not joined
        } finally {
            scope.cancel()
        }
    }

    /**
     * The switch's confirm leaves and **nothing else**; the join that follows is the regular surface, so
     * the member configures the new membership like any joiner. This is the regression the capability
     * exists for: the old compact dialog could only ever produce `Direction.Both` with the album off, and
     * re-scanning the joined event short-circuits as `AlreadyJoined`, so there was no route to any other
     * shape at all. Here the switch lands a **receive-only, album-on** membership.
     */
    @Test
    fun a_switch_leaves_the_current_event_then_joins_the_new_one_as_configured() = worldTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.provision(EVENT_E, "Summer Trip")          // already joined to E
            w.store.registerEvent(EVENT_F, "Anna's Wedding") // F exists to switch to
            val host = joinHost(w, scope)

            host.onOpenUrl(deeplink(EVENT_F))
            host.await { ((it as UiState).layer as? Layer.Joined)?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }

            // Confirm = the leave alone. E is gone and the SAME pending join is now the full-screen
            // surface for F, where the choices are made.
            host.onConfirmSwitch()
            host.await { it.layer is Layer.JoiningEvent && w.configSource.config.value == null }

            host.confirmJoinAs(Direction.DownloadOnly, saveToAlbum = true)
            host.await { it.layer is Layer.Joined && w.configSource.config.value?.eventId == EVENT_F }

            val cfg = w.configSource.config.value
            assertEquals(EVENT_F, cfg?.eventId)                            // switched
            assertEquals(Direction.DownloadOnly, cfg?.direction)           // the member's direction, not Both
            assertEquals(true, cfg?.saveToAlbum)                           // the member's album opt-in
            assertTrue(w.store.manifestOf(EVENT_F, w.ownDeviceId) != null) // enrolled in the new event
            assertTrue(w.store.isDeparted(EVENT_E, w.ownDeviceId))        // and departed the old
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
            host.await { ((it as UiState).layer as? Layer.Joined)?.pendingSwitch?.phase?.step == JoinPhase.Detailed.Step.Ready }

            host.onConfirmSwitch()
            // The join surface appears even though E's DELETE is still pending: the leave's local teardown
            // never waits on the departed event's fire-and-forget backend notify.
            host.await { it.layer is Layer.JoiningEvent && w.configSource.config.value == null }

            host.confirmJoinAs()
            // …and the new event's join completes with E's DELETE still hanging.
            host.await { it.layer is Layer.Joined && w.configSource.config.value?.eventId == EVENT_F }
            assertTrue(w.store.manifestOf(EVENT_F, w.ownDeviceId) != null) // enrolled in the new event
            assertFalse(deleteGate.isCompleted)                            // E's DELETE never gated either step
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun the_same_link_delivered_twice_enrolls_once() = worldTest {
        // The platform delivers one opened link MORE THAN ONCE (capability `event-link`): measured on
        // build 687, the scene delegate's connection and SwiftUI's `.onOpenURL` both fired for the same
        // URL — ~130 ms apart on an iOS 18.7.9 cold launch, and 8 ms apart on iOS 26.6 while running.
        // Both hooks stay live because neither is reliable on every OS, so "exactly once" is enforced by
        // the gate rather than by the hook arrangement.
        //
        // autoJoin is the sharpest case: it auto-confirms with no surface and no tap, so before the
        // duplicate rungs it was the one path a doubled delivery would double-PROVISION.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val w = World(this)
            w.store.registerEvent(EVENT_E, "Anna's Wedding")
            val host = joinHost(w, scope)
            val link = encodeEventUrl(EventLinkPayload(EVENT_E, autoJoin = true))

            host.onOpenUrl(link)
            host.await { it.layer is Layer.Joined }
            // A real manifest, as a prior upload cycle would have written it.
            w.store.putManifest(EVENT_E, w.ownDeviceId, foreignManifest(w.ownDeviceId, listOf(World.foreignAsset("A"))))

            host.onOpenUrl(link) // the duplicate delivery
            host.await { it.layer is Layer.Joined }

            // Joined once, to that event — and the second delivery re-enrolled nothing: a second
            // provision republishes an EMPTY manifest, so a surviving non-empty one is the oracle.
            assertEquals(EVENT_E, w.configSource.config.value?.eventId)
            val manifest = w.store.manifestOf(EVENT_E, w.ownDeviceId)
            assertTrue(manifest != null && manifest.assets.isNotEmpty(), "a duplicate delivery must not re-provision")
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
            host.await { it.layer is Layer.Joined }

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
            host.await { it.layer is Layer.Joined } // straight to joined, no confirm tap

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
            host.await { it.layer is Layer.Joined }

            assertEquals(captureCutoff(cutoff), w.configSource.config.value?.minPhotoDate, "the cutoff must be persisted in config")
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
            host.await { it.layer is Layer.Joined }

            val config = w.configSource.config.value
            assertEquals(captureCutoff(startsAt), config?.minPhotoDate, "the 2001 cutoff must not survive the clamp")
            assertEquals(eventStart(startsAt), config?.startsAt)
            assertTrue(
                config!!.minPhotoDate.at >= config.startsAt.at,
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
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready }
            host.confirmJoinAs()
            host.await { it.layer is Layer.Joined }
            assertEquals(EVENT_E, w.configSource.config.value?.eventId)

            // The nightly sweep deletes the event out from under a still-active member, and time moves
            // past the deadline the membership persisted at join. BOTH witnesses now hold.
            w.store.sweepEvent(EVENT_E)
            w.nowMillis = Instant.parse("2027-01-01T00:00:00Z").toEpochMilliseconds()

            w.core.foregroundFlow.run()
            host.await { it.layer is Layer.CreateEvent }
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
            host.await { ((it as UiState).layer as? Layer.JoiningEvent)?.phase?.step == JoinPhase.Detailed.Step.Ready }
            host.confirmJoinAs()
            host.await { it.layer is Layer.Joined }
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
            StatusSources(
                sync = w.syncStatusSource,
                permission = w.permission.permission,
                config = w.configSource.config,
            ),
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

/**
 * A loaded join phase at [step]. The four event facts are stated ONCE on the phase now (capability
 * `join-event`), so a test builds the details and says which step is showing.
 */
private fun phaseAt(
    step: JoinPhase.Detailed.Step,
    name: String,
    startsAt: EventStart,
    endsAt: EventEnd,
    deletesAt: DeletesAt,
) = JoinPhase.Detailed(EventDetails(name, startsAt, endsAt, deletesAt), step)

/**
 * Choose a participation, then confirm — what the surface does.
 *
 * The commit carries nothing now: it commits what the reduction resolved from the form (capability
 * `sync-status-screen`). The RANGE is left at its defaults, which resolve to the full event window.
 */
private fun StatusContainerHost.confirmJoinAs(
    direction: Direction = Direction.Both,
    saveToAlbum: Boolean = false,
) {
    form.onShareOn(direction.includesUpload)
    form.onReceiveOn(direction.includesDownload)
    form.onSaveToAlbum(saveToAlbum)
    onConfirmJoin()
}
