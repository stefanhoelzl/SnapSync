package app.snapsync.presentation

import app.snapsync.model.eventStart
import app.snapsync.model.eventEnd
import app.snapsync.model.deletesAt
import app.snapsync.model.captureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.CaptureDate
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.Arrow
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinCommit
import app.snapsync.feature.membership.RenameStatus
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.feature.creation.CreationFailureReason
import app.snapsync.feature.creation.CreationStatus
import app.snapsync.feature.creation.EventCreator
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserCommands
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.model.PermissionStatus
import app.snapsync.feature.download.DownloadProgress
import app.snapsync.feature.download.InMemoryDownloadStatusSource
import app.snapsync.model.SyncStatus
import app.snapsync.model.SyncProgress
import app.snapsync.feature.status.SyncStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test

private const val EVENT_ID = "11111111-1111-4111-8111-111111111111"

/** Real-time budget for the liveness pin's awaits — generous, because it waits on real dispatchers. */
private val LIVENESS_TIMEOUT = 10.seconds

/** A membership always carries a cutoff (capability `photo-selection-policy`); no join can pass `null`. */
private val CUTOFF = captureCutoff("2026-07-06T14:32:11Z")
private val CEILING = captureCeiling("2026-07-13T14:32:11Z")
// Config seam + store as one fake: save writes the cell, which is exactly how the real Keychain
// adapter behaves. Defaults to present so the sync-state tests reach the joined layer.
private val SAMPLE_CONFIG = EventConfig(
    eventId = EVENT_ID,
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
    maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
)

// Joined-state helpers keep the assertions readable. Since the joined state carries its membership and
// the invite URL derived from it (capability `sync-status-screen`), an EXPECTED state has to carry them
// too — so these default to the same config the fake config source holds, and derive the URL the same
// way the reduction does.
private fun joined(
    health: SyncHealth,
    pendingSwitch: PendingSwitch? = null,
    config: EventConfig = SAMPLE_CONFIG,
    canChoosePhotos: Boolean = false,
    ended: Boolean = false,
    renameState: RenameState = RenameState.Idle,
) = UiState(
    Layer.Joined(
        membership = config,
        inviteUrl = encodeEventUrl(EventLinkPayload(config.eventId)),
        health = health,
        pendingSwitch = pendingSwitch,
        canChoosePhotos = canChoosePhotos,
        ended = ended,
        renameState = renameState,
    ),
)

/** The membership a commit provisions: the cutoff the member confirmed, under the loaded name. */
private fun committed(cutoff: String, name: String = "Anna's Birthday") =
    EventConfig(EVENT_ID, name, captureCutoff(cutoff), maxPhotoDate = CEILING)

/** The screen showing [layer] with no overlays — what every expectation in this file means. */
private fun screen(layer: Layer) = UiState(layer)

/**
 * The pending event and the phase a join surface reached — what these tests are about.
 *
 * They deliberately do NOT compare the whole state: a loaded phase also carries the range the reduction
 * resolved from the form, and restating that here would assert the resolution rules a second time, in the
 * one place that cannot notice when they change. `RangeResolutionTest` owns those.
 */
private fun assertJoining(state: UiState, eventId: String, phase: JoinPhase) {
    val layer = state.layer
    assertTrue(layer is Layer.JoiningEvent, "expected the join surface, got $layer")
    assertEquals(eventId, layer.eventId)
    assertEquals(phase, layer.phase)
}

/**
 * Choose a participation, then confirm — what the surface does.
 *
 * The commit carries nothing now: it commits what the reduction resolved from the form (capability
 * `sync-status-screen`), so a test that wants a particular direction or album opt-in has to make that
 * choice rather than hand the container a pre-resolved answer. The RANGE is left at its defaults, which
 * resolve to the full event window — the value these tests were passing explicitly.
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

private fun syncing(up: Arrow, down: Arrow = Arrow.HIDDEN, config: EventConfig = SAMPLE_CONFIG) =
    joined(SyncHealth.Syncing(up, down), config = config)

/** The direction-masking tests' membership: SAMPLE_CONFIG's window, receive-only. */
private val downloadOnly = SAMPLE_CONFIG.copy(direction = Direction.DownloadOnly)
private val inSync = joined(SyncHealth.InSync)
private val joinedLoading = joined(SyncHealth.Loading)
private fun needsAccess(p: PermissionStatus) = joined(SyncHealth.NeedsAccess(p))

private class FakeSyncStatusSource(initial: SyncStatus = SyncStatus.Ready(snapshot())) :
    SyncStatusSource {
    constructor(initial: SyncProgress) : this(SyncStatus.Ready(initial))

    private val flow = MutableStateFlow(initial)
    override val status: StateFlow<SyncStatus> = flow
    var value: SyncProgress
        get() = (flow.value as SyncStatus.Ready).progress
        set(v) {
            flow.value = SyncStatus.Ready(v)
        }
}

// A plain permission cell (no port interface): the host observes a bare StateFlow since the step-9
// split — presentation never names `ports/`, so neither do its fakes.
private class FakePermissionSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) {
    val permission = MutableStateFlow(initial)
}


private class FakeConfig(initial: EventConfig? = SAMPLE_CONFIG) {
    private val flow = MutableStateFlow(initial)
    val config: StateFlow<EventConfig?> = flow
    suspend fun save(config: EventConfig) {
        flow.value = config
    }
    suspend fun clear() {
        flow.value = null
    }
}

// The permission actions are bundle commands since step 9 (`requestAccess`/`openSettings`); this spy
// supplies their bound halves.
private class SpyRequester {
    var requests = 0
    var settingsOpens = 0

    fun request() {
        requests++
    }

    fun openSettings() {
        settingsOpens++
    }
}

private class SpyCreator : EventCreator {
    val created = mutableListOf<String>()
    val starts = mutableListOf<String>()
    val ends = mutableListOf<String>()
    override suspend fun create(name: String, startsAt: String, endsAt: String) {
        created += name
        starts += startsAt
        ends += endsAt
    }
}

private fun snapshot(
    pending: Int = 0,
    completed: Int = 0,
    total: Int = 0,
    failed: Int = 0,
    active: Boolean = true,
    estimatedRemaining: Duration? = null,
) = SyncProgress(pending, completed, total, failed, active, estimatedRemaining)

/** What the fixed clock below yields as a cutoff — the seed when `createdAt` is absent or unparseable. */
private val NOW_CUTOFF = CaptureDate("2026-07-09T12:00:00Z")


/**
 * The event's window ceiling (`endsAt` / the join `until`), a plausible week-long window after every
 * `startsAt` used below (CUTOFF, and the `2026-07-0x…` literals). Threaded through `JoinLoad.Found`,
 * the join phases, and `onConfirmJoin`/`onConfirmSwitch`'s `until` (capability event date-range).
 */
private val ENDS_AT = eventEnd("2026-07-13T14:32:11Z")

/** The event's server-derived retention deadline (capability `event-limits`), carried on every details
 *  load; the gate states it before confirm. */
private val DELETES_AT = deletesAt("2026-08-05T14:32:11Z")

/**
 * The **real** [CutoffFormatter] on a fixed instant in UTC. Deliberately not a hand-rolled fake: the
 * `createdAt` → cutoff normalization depends on the actual ISO-8601 codec (the backend mints `createdAt`
 * with milliseconds, which must be truncated to the second-precision invariant), and a fake formatter
 * would assert nothing about that.
 */
private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse(NOW_CUTOFF.iso) },
    zone = TimeZone.UTC,
)

/** A clock the test can move, so the not-started tick can be watched retiring itself. */
private class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private fun movableCutoffFormatter(clock: MovableClock) =
    CutoffFormatter(now = clock::now, zone = TimeZone.UTC)

private fun host(
    source: FakeSyncStatusSource,
    scope: CoroutineScope,
    permission: FakePermissionSource = FakePermissionSource(),
    requester: SpyRequester = SpyRequester(),
    configFake: FakeConfig = FakeConfig(),
    loadJoinDetails: suspend (String) -> JoinLoad = { JoinLoad.Failed },
    commitJoin: suspend (
        String, String, EventStart, EventEnd, DeletesAt, CaptureCutoff, CaptureCeiling, Direction, Boolean,
    ) -> JoinCommit = { _, _, _, _, _, _, _, _, _ -> JoinCommit.Failed },
    leave: suspend () -> Unit = {},
    attested: MutableStateFlow<Boolean> = MutableStateFlow(true),
    onIntentError: (Throwable) -> Unit = {},
) = StatusContainerHost(
    StatusSources(source, permission.permission, configFake.config, attested = attested),
    scope,
    loadJoinDetails = loadJoinDetails,
    commands = UserCommands(
        leave = leave, commitJoin = commitJoin,
        requestAccess = requester::request, openSettings = requester::openSettings,
    ),
    cutoffFormatter = fixedCutoffFormatter(),
    diagnostics = StatusDiagnostics(onIntentError = onIntentError),
)

/** A first join (config absent) whose details load succeeds — the gate the explainer is decided in. */
private fun TestScope.firstJoinGate(
    permission: PermissionStatus,
    requester: SpyRequester,
    configFake: FakeConfig = FakeConfig(null),
    commitJoin: suspend (
        String, String, EventStart, EventEnd, DeletesAt, CaptureCutoff, CaptureCeiling, Direction, Boolean,
    ) -> JoinCommit = { _, _, _, _, _, _, _, _, _ -> JoinCommit.Failed },
    // Counts details fetches — a switch's post-leave derivation must re-use the load, never re-run it.
    onLoad: () -> Unit = {},
    leave: suspend () -> Unit = {},
) = host(
    FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
    permission = FakePermissionSource(permission),
    requester = requester,
    configFake = configFake,
    loadJoinDetails = {
        onLoad()
        JoinLoad.Found("My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT)
    },
    commitJoin = commitJoin,
    leave = leave,
)

class StatusContainerHostTest {

    // ── the not-started clock line (capability `sync-status-screen`) ──────────────────────────────

    /** An event that has not begun: its start is after the fixed NOW_CUTOFF (2026-07-09T12:00:00Z). */
    private val futureStart = eventStart("2026-07-09T18:00:00Z")

    private fun notStartedConfig(startsAt: EventStart = futureStart) = EventConfig(
        eventId = EVENT_ID,
        name = "Anna's Birthday",
        // The floor guarantees this shape: `minPhotoDate == max(chosen, startsAt) == startsAt` pre-start.
        minPhotoDate = CaptureCutoff(startsAt.at), maxPhotoDate = CEILING,
        startsAt = startsAt,
    )

    @Test
    fun `a future event start reduces to NotStarted whatever the snapshot says`() = runTest {
        // The snapshot says there is work to do; the clock says the event has not begun. The clock wins,
        // because nothing of this member's CAN be syncing — the floor makes it impossible.
        val host = host(
            FakeSyncStatusSource(snapshot(pending = 3, total = 5)), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(notStartedConfig()),
        )
        assertEquals(joined(SyncHealth.NotStarted(futureStart), config = notStartedConfig()), host.container.stateFlow.value)
    }

    @Test
    fun `permission outranks the not-started state`() = runTest {
        // Permission is the only ACTIONABLE state, and it must be resolved BEFORE the event begins or the
        // member misses the start. Burying it behind the clock line would ambush them with a permission
        // prompt at the very moment the party starts.
        val host = host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.DENIED),
            configFake = FakeConfig(notStartedConfig()),
        )
        assertEquals(
            joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED), config = notStartedConfig()),
            host.container.stateFlow.value,
        )
    }

    @Test
    fun `a past event start reduces from the snapshot exactly as before`() = runTest {
        val host = host(
            FakeSyncStatusSource(snapshot(completed = 5, total = 5)), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(SAMPLE_CONFIG), // startsAt defaults to CUTOFF, which precedes now
        )
        assertEquals(inSync, host.container.stateFlow.value)
    }

    @Test
    fun `an event starting exactly now is already started`() = runTest {
        // The boundary: the comparison is `startsAt > now`, so the start instant itself is NOT "not
        // started" — consistent with the cutoff's own at-or-after (`creationDate >= cutoff`) inclusivity.
        val host = host(
            FakeSyncStatusSource(snapshot(completed = 0, total = 0)), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(notStartedConfig(startsAt = EventStart(NOW_CUTOFF))),
        )
        assertEquals(joined(SyncHealth.InSync, config = notStartedConfig(EventStart(NOW_CUTOFF))), host.container.stateFlow.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the foreground tick retires the clock line when the start passes`() = runTest {
        // NotStarted is the one health driven by WALL-CLOCK time, not the ledger — no snapshot emission
        // would ever retire it. Without the tick the clock line would sit there past the start until
        // something unrelated happened to re-emit.
        val clock = MovableClock(Instant.parse(NOW_CUTOFF.iso))
        val config = FakeConfig(notStartedConfig())
        val host = StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(snapshot(completed = 0, total = 0)),
                FakePermissionSource(PermissionStatus.GRANTED).permission, config.config,
            ),
            backgroundScope,
            cutoffFormatter = movableCutoffFormatter(clock),
        )
        host.test(this) {
            runOnCreate()

            // A minute passes; the event still has not begun, so the line stands.
            advanceTimeBy(61_000)
            assertEquals(
                joined(SyncHealth.NotStarted(futureStart), config = notStartedConfig()),
                containerHost.container.stateFlow.value,
            )

            // The start passes. The next tick re-derives from the snapshot — no ledger event required,
            // and no source has changed.
            clock.instant = Instant.parse("2026-07-09T18:00:01Z")
            advanceTimeBy(61_000)
            assertEquals(joined(SyncHealth.InSync, config = notStartedConfig()), containerHost.container.stateFlow.value)

            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `work in flight maps to Syncing with a pulsing up arrow`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 35, completed = 12, total = 47)
            expectState(syncing(up = Arrow.PULSING)) // completed<total, pending>0
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `work remaining but no jobs yet maps to Syncing with a static up arrow`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 5) // pending 0
            expectState(syncing(up = Arrow.STATIC))
            cancelAndIgnoreRemainingItems()
        }
    }

    // A joined host with an explicit participation direction + download progress, for arrow masking.
    private fun directionHost(
        source: FakeSyncStatusSource,
        scope: CoroutineScope,
        direction: Direction,
        download: DownloadProgress = DownloadProgress(0, 0),
    ): StatusContainerHost {
        val cfg = FakeConfig(EventConfig(EVENT_ID, "Anna's Birthday", CUTOFF, maxPhotoDate = CEILING, direction = direction))
        return StatusContainerHost(
            StatusSources(
                source, FakePermissionSource(PermissionStatus.GRANTED).permission, cfg.config,
                download = InMemoryDownloadStatusSource(download),
            ), scope,
            cutoffFormatter = fixedCutoffFormatter(),
        )
    }

    // ---- An opted-out arm hides itself through its ZERO TOTAL, not through a mask -----------------------
    //
    // Four tests used to live here asserting the direction mask: they fed a download-only membership
    // `total = 5` (a gallery of un-uploaded photos) and checked the arrow was force-hidden over the top.
    // That input is now UNREACHABLE — a non-contributing membership's N is 0 (capability
    // `photo-selection-policy`), and an upload-only membership never reconciles, so its download total is 0
    // (capability `photo-download`). They tested a mechanism that no longer exists against a state the system
    // can no longer produce.
    //
    // The contract they protected is not lost; it is proved across the three layers that actually own it:
    //  · `:domain` feature/status — a non-contributing membership totals 0, without walking the library
    //  · here                  — a zero total hides its arrow, and a NON-zero one shows it (below)
    //  · `:test:integration`   — a real download-only join reads "In sync" through the real stack
    // Which is the point: the direction contract is now kept by the counts, so this layer need not know it.

    @Test
    fun `an opted-out arm hides its arrow through a zero total`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        // A download-only membership as the system can actually present it: nothing to upload (N=0),
        // imports still arriving. No mask involved — `0 < 0` is false.
        directionHost(source, backgroundScope, Direction.DownloadOnly, DownloadProgress(2, 5, inFlight = 1))
            .test(this) {
                runOnCreate()
                source.value = snapshot(completed = 0, total = 0)
                expectState(syncing(up = Arrow.HIDDEN, down = Arrow.PULSING, config = downloadOnly))
                cancelAndIgnoreRemainingItems()
            }
    }

    @Test
    fun `both arms at zero read In sync whatever the membership`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        directionHost(source, backgroundScope, Direction.DownloadOnly, DownloadProgress(5, 5))
            .test(this) {
                runOnCreate()
                source.value = snapshot(completed = 0, total = 0) // contributes nothing → nothing outstanding
                expectState(joined(SyncHealth.InSync, config = downloadOnly))
                cancelAndIgnoreRemainingItems()
            }
    }

    /**
     * **The smoke detector** (capability `sync-status-screen`).
     *
     * If a non-contributing membership ever reports upload work, the arrow SHOWS. There is no mask left to
     * swallow it. This is the single most important assertion in this file, and it is the one the old mask
     * made impossible: for a full release cycle a download-only member's camera roll uploaded to a stranger's
     * event while this screen read a serene "In sync", because the one surface that could have told them was
     * the surface hiding it (capability `upload-lifecycle`).
     *
     * If the counts are right this state never occurs. If they are wrong, an arrow the member never asked for
     * is the only signal anyone gets — so the display must never assert a contract the system is not keeping.
     */
    @Test
    fun `an upload arrow shows if a non-contributing membership ever reports upload work`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        directionHost(source, backgroundScope, Direction.DownloadOnly, DownloadProgress(5, 5))
            .test(this) {
                runOnCreate()
                // The direction gate is NOT being honoured somewhere upstream: work is being reported for a
                // membership that promised to share nothing.
                source.value = snapshot(pending = 2, completed = 1, total = 5)
                // Surfaced, not concealed.
                expectState(syncing(up = Arrow.PULSING, down = Arrow.HIDDEN, config = downloadOnly))
                cancelAndIgnoreRemainingItems()
            }
    }

    // The create layer is the top rung: config absent, reduced from the creation status.
    private fun createHost(
        creation: CreationStatus,
        permission: FakePermissionSource = FakePermissionSource(PermissionStatus.GRANTED),
        creator: EventCreator = SpyCreator(),
        scope: CoroutineScope,
    ): StatusContainerHost {
        val config = FakeConfig(null)
        return StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(), permission.permission, config.config,
                creation = MutableCreationStatusSource(creation),
            ), scope,
            commands = UserCommands(create = { n, st, en -> scope.launch { creator.create(n, st.at.iso, en.at.iso) } }),
            cutoffFormatter = fixedCutoffFormatter(),
        )
    }

    @Test
    fun `config absent with idle creation shows the create input`() = runTest {
        val host = createHost(CreationStatus.Idle, scope = backgroundScope)
        assertEquals(screen(Layer.CreateEvent(error = null)), host.container.stateFlow.value)
    }

    @Test
    fun `config absent with in-flight creation shows the creating state`() = runTest {
        val host = createHost(CreationStatus.InFlight, scope = backgroundScope)
        assertEquals(screen(Layer.CreatingEvent), host.container.stateFlow.value)
    }

    @Test
    fun `config absent with an invalid-name failure shows the input with the name error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.INVALID_NAME), scope = backgroundScope)
        assertEquals(
            screen(Layer.CreateEvent(error = "That name wasn't accepted. Try a different one.")),
            host.container.stateFlow.value,)
    }

    @Test
    fun `config absent with a server failure shows the input with the server error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.SERVER), scope = backgroundScope)
        assertEquals(screen(Layer.CreateEvent(error = "Couldn't reach the server.")), host.container.stateFlow.value)
    }

    @Test
    fun `create layer ignores permission`() = runTest {
        val host = createHost(
            CreationStatus.Idle,
            permission = FakePermissionSource(PermissionStatus.DENIED),
            scope = backgroundScope,
        )
        // Config absent outranks a non-granted permission — still the create input, not the joined layer.
        assertEquals(screen(Layer.CreateEvent()), host.container.stateFlow.value)
    }

    @Test
    fun `onCreateEvent delegates to the creator`() = runTest {
        val creator = SpyCreator()
        val host = createHost(CreationStatus.Idle, creator = creator, scope = backgroundScope)
        host.test(this) {
            containerHost.onCreateEvent(
                "My Party",
                LocalDateTime(2026, 7, 14, 18, 0),
                LocalDateTime(2026, 7, 18, 18, 0),
            )
        }
        advanceUntilIdle()
        assertEquals(listOf("My Party"), creator.created)
        // The screen hands over LOCAL picks; the container converts them through the one cutoff codec, so
        // `:ui:screens` never touches a clock, a timezone, or a cutoff string.
        assertEquals(listOf("2026-07-14T18:00:00Z"), creator.starts)
        assertEquals(listOf("2026-07-18T18:00:00Z"), creator.ends)
    }

    // NB (migration step 9): the two tests below used to assemble the REAL `CreateEvent` use-case over
    // a stubbed `EventCreation` port. The armed presentation gate scans ALL of `ui/presentation/src`
    // (tests included), so this file may no longer name `ports/`; the mint itself is pinned in
    // `CreateEventTest` (feature/creation) and the full create→gate→join stack in
    // `:test:integration`'s `create_event_lifts_the_setup_gate`. What stays here is presentation's own
    // half: the minted id routes into the gate, and a failed status shows the error and opens no gate.

    @Test
    fun `a minted event routes into the join gate then confirming joins with the cutoff`() = runTest {
        val config = FakeConfig(null)
        val host = StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED).permission, config.config,
            ), backgroundScope,
            commands = UserCommands(
                commitJoin = { id, name, startsAt, _, _, cutoff, _, direction, _ ->
                    config.save(EventConfig(id, name, minPhotoDate = cutoff, maxPhotoDate = CEILING, startsAt = startsAt, direction = direction))
                    JoinCommit.Committed
                },
            ),
            loadJoinDetails = { JoinLoad.Found("My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT) },
            cutoffFormatter = fixedCutoffFormatter(),
        )
        host.test(this) {
            runOnCreate()
            // The create use-case's `onMinted` hook fires exactly this (non-auto-confirmed).
            containerHost.onEventCreated(EVENT_ID)
            // minted → routed into the gate → loaded, offering Join with the event's start as the default.
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            // Confirm provisions → config present + granted + snapshot total 0 → settled. The state now
            // carries the membership the commit persisted, so the expectation names it.
            expectState(joined(SyncHealth.InSync, config = committed("2026-07-06T00:00:00Z", "My Party")))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(captureCutoff("2026-07-06T00:00:00Z"), config.config.value?.minPhotoDate)
    }

    @Test
    fun `a failed create surfaces the inline error and opens no gate`() = runTest {
        val config = FakeConfig(null)
        val creationStatus = MutableCreationStatusSource()
        val host = StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED).permission,
                config.config, creation = creationStatus,
            ), backgroundScope,
            cutoffFormatter = fixedCutoffFormatter(),
        )
        host.test(this) {
            runOnCreate()
            // The use-case reports a transient failure through the status source and never fires
            // `onMinted` (CreateEventTest pins that) — the inline error shows and no gate opens.
            creationStatus.set(CreationStatus.Failed(CreationFailureReason.SERVER))
            expectState(screen(Layer.CreateEvent(error = "Couldn't reach the server.")))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a re-join shows the listing-derived snapshot and never a join state`() = runTest {
        // Seed Loading so the transition to the listing snapshot is a real change (equal states conflate).
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 12, total = 47)
            expectState(syncing(up = Arrow.STATIC)) // rising synced count, no join screen
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `empty library maps to In sync`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 3))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 0, total = 0)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `all present photos synced maps to In sync`() = runTest {
        // Seed a Syncing state so the transition to settled is a real change.
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 10))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 34, total = 34)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `overshoot clamps and maps to In sync`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 1, total = 10))
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 6, total = 5) // synced clamps to 5 >= total
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a newer snapshot replaces the displayed state entirely`() = runTest {
        val source = FakeSyncStatusSource()
        host(source, backgroundScope).test(this) {
            runOnCreate()
            source.value = snapshot(completed = 1, total = 10)
            expectState(syncing(up = Arrow.STATIC))
            source.value = snapshot(completed = 10, total = 10)
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `initial state derives from source values and never a guess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val container = host(source, backgroundScope).container

        assertEquals(inSync, container.stateFlow.value)
    }

    @Test
    fun `denied permission with config present folds into the NeedsAccess status line`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.DENIED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.DENIED), container.stateFlow.value)
    }

    @Test
    fun `not-determined permission with config present shows NeedsAccess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
    }

    @Test
    fun `a limited grant reduces from the snapshot and never to NeedsAccess`() = runTest {
        // A partial grant is a working state (capability `limited-photo-access`): the selection defines
        // the scope, so the settled snapshot reads In sync — not the permission-attention line — and the
        // joined layer carries the choose-more-photos resting affordance.
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.LIMITED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(
            joined(SyncHealth.InSync, canChoosePhotos = true),
            container.stateFlow.value,
        )
    }

    @Test
    fun `a full grant offers no choose-photos affordance`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(false, (container.stateFlow.value.layer as Layer.Joined).canChoosePhotos)
    }

    @Test
    fun `revoking permission mid-sync switches the status line to NeedsAccess`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.DENIED
            expectState(needsAccess(PermissionStatus.DENIED))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `absent config shows the create layer even when granted`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container =
            host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).container

        assertEquals(screen(Layer.CreateEvent()), container.stateFlow.value)
    }

    @Test
    fun `loading snapshot with config and granted permission maps to a joined loading`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(joinedLoading, container.stateFlow.value)
    }

    @Test
    fun `permission outranks a loading snapshot when config is present`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(needsAccess(PermissionStatus.NOT_DETERMINED), container.stateFlow.value)
    }

    @Test
    fun `absent config outranks a loading snapshot`() = runTest {
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val container =
            host(source, backgroundScope, permission = permission, configFake = FakeConfig(null)).container

        assertEquals(screen(Layer.CreateEvent()), container.stateFlow.value)
    }

    @Test
    fun `granting permission reveals the current sync state`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        host(source, backgroundScope, permission = permission).test(this) {
            runOnCreate()
            permission.permission.value = PermissionStatus.GRANTED
            expectState(inSync)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a second-precision event createdAt survives normalization unchanged`() = runTest {
        // A `createdAt` already at second precision round-trips through normalization unchanged
        // (capability `photo-selection-policy`).
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", eventStart("2026-07-06T14:32:11Z"), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", eventStart("2026-07-06T14:32:11Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the gate defaults the cutoff to the event start rather than to now`() = runTest {
        // The seed-from-createdAt (and its fall-back-to-now) is GONE. `startsAt` is always present on a
        // successful load — the backend synthesizes one for legacy markers and `HttpEventDirectory`
        // fails the load rather than invent one — so the default is simply the event's start. Normalizing
        // a millisecond-bearing value is that source's job now, and is tested there.
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", eventStart("2026-07-04T18:00:00Z"), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            // Note it is NOT NOW_CUTOFF (2026-07-09): the event's own start wins.
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", eventStart("2026-07-04T18:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `autoJoin with no explicit cutoff commits with the event start`() = runTest {
        // The headless dev launch has no surface on which an empty cutoff row could be noticed, so the
        // default matters most here (capability `photo-selection-policy`).
        val configFake = FakeConfig(null)
        var committedCutoff: CaptureCutoff? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, cutoff, _, _, _ ->
                committedCutoff = cutoff; configFake.save(EventConfig(id, name, cutoff, maxPhotoDate = CEILING)); JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true)))
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(CUTOFF, committedCutoff)
    }

    @Test
    fun `a hostile autoJoin deeplink cutoff reaches commitJoin raw so the use-case can clamp it`() = runTest {
        // `minPhotoDate` is decoded from ANY event link, so a QR carrying `autoJoin=true` + a
        // distant-past cutoff would auto-confirm a join at near-whole-library scope WITHOUT A TAP. The
        // container must NOT clamp it here and must NOT drop it: it passes the raw value across the seam,
        // together with the event's `startsAt`, and `JoinEvent` applies `max(chosen, startsAt)` — the one
        // site every entry path funnels through (see JoinEventTest).
        val configFake = FakeConfig(null)
        var seenStartsAt: EventStart? = null
        var seenCutoff: CaptureCutoff? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, startsAt, _, _, cutoff, _, _, _ ->
                seenStartsAt = startsAt
                seenCutoff = cutoff
                configFake.save(EventConfig(id, name, cutoff, maxPhotoDate = CEILING))
                JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(
                encodeEventUrl(
                    EventLinkPayload(EVENT_ID, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z"),
                ),
            )
            expectState(joined(SyncHealth.Loading, config = committed("2001-01-01T00:00:00Z")))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(captureCutoff("2001-01-01T00:00:00Z"), seenCutoff, "the hostile value is passed through, not swallowed")
        assertEquals(EventStart(CUTOFF.at), seenStartsAt, "...alongside the floor that will defeat it")
    }

    @Test
    fun `autoJoin honours an explicit dev cutoff over the createdAt default`() = runTest {
        val configFake = FakeConfig(null)
        var committedCutoff: CaptureCutoff? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", eventStart("2026-07-06T14:32:11Z"), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, cutoff, _, _, _ ->
                committedCutoff = cutoff; configFake.save(EventConfig(id, name, cutoff, maxPhotoDate = CEILING)); JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(
                encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true, minPhotoDate = "2026-05-05T05:05:05Z")),
            )
            expectState(joined(SyncHealth.Loading, config = committed("2026-05-05T05:05:05Z")))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(captureCutoff("2026-05-05T05:05:05Z"), committedCutoff)
    }

    @Test
    fun `a first-join deeplink opens the gate and confirming enrolls then joins`() = runTest {
        val configFake = FakeConfig(null)
        val enrolled = mutableListOf<String>()
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, _, _ -> enrolled += id; configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING)); JoinCommit.Committed },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            expectState(joinedLoading) // commit saved config -> present + granted + Loading snapshot
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(listOf(EVENT_ID), enrolled)
    }

    @Test
    fun `a 404 on details blocks the join with no commit`() = runTest {
        var commits = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.NotFound },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> commits++; JoinCommit.Committed },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, JoinPhase.NotFound)
            containerHost.confirmJoinAs() // inert when not Ready
            containerHost.onCancelJoin()
            expectState(screen(Layer.CreateEvent()))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, commits)
    }

    @Test
    fun `a load failure is retryable`() = runTest {
        var attempt = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { if (attempt++ == 0) JoinLoad.Failed else JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, JoinPhase.LoadFailed)
            containerHost.onRetryLoad()
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a full event lands on its own step rather than the retryable one`() = runTest {
        // Capacity does not heal, and the two failures reach DIFFERENT screens because of it: the
        // retryable one pins a Retry, this one offers only Cancel (capability `join-event`). Collapsed
        // into one step, a member at a full event could press Retry forever with nothing saying why.
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> JoinCommit.Full },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.EventFull, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a failed enrollment leaves a retryable commit-failed state and does not join`() = runTest {
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> JoinCommit.Failed },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.CommitFailed, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `re-scanning the already-joined event is a no-op`() = runTest {
        var loads = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { loads++; JoinLoad.Found("x", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, loads)
    }

    /**
     * The platform delivers the same link TWICE (capability `event-link`): measured on build 687, once on
     * an iOS 18.7.9 cold launch ~130 ms apart and again on iOS 26.6 both while running (8 ms) and cold
     * (105 ms), because the scene delegate and SwiftUI's `.onOpenURL` are both live and neither is
     * reliable alone. "Exactly once" is therefore enforced here, not assumed of the hooks.
     *
     * `loads` is the oracle rather than the state: a second `startPending` would reset the phase to
     * Loading and re-fetch, and the re-fetch is the visible cost.
     */
    @Test
    fun `the same link delivered twice starts one pending join`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        var loads = 0
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { loads++; JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            val link = encodeEventUrl(EventLinkPayload(other))
            containerHost.onOpenUrl(link)
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onOpenUrl(link).join()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(1, loads)
    }

    /**
     * A **different** link still supersedes — the duplicate rung keys on the event, not on "some link
     * already arrived", so switching from one invite to another remains possible without dismissing.
     */
    @Test
    fun `a different link supersedes the pending join`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val third = "33333333-3333-4333-8333-333333333333"
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(third)))
            // The synchronous fake resolves within the intent, so the Loading frame collapses into Ready.
            expectState(joined(SyncHealth.Loading, PendingSwitch(third, ready)))
            cancelAndIgnoreRemainingItems()
        }
    }

    /**
     * The same link after the member dismissed the surface is a fresh delivery — the boundary is what the
     * member is currently deciding, not a timer and not "this link was seen once".
     */
    @Test
    fun `the same link after a dismissal is acted on again`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        var loads = 0
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { loads++; JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
        ).test(this) {
            runOnCreate()
            val link = encodeEventUrl(EventLinkPayload(other))
            containerHost.onOpenUrl(link)
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onCancelSwitch().join()
            containerHost.onOpenUrl(link).join()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(2, loads)
    }

    /**
     * The sharpest case: `autoJoin=true` auto-confirms with no surface and no tap, so before the duplicate
     * rungs it was the one path a doubled delivery would double-PROVISION. The rungs are tested ahead of
     * the autoJoin rung for exactly this reason.
     */
    @Test
    fun `an autoJoin link delivered twice enrolls once`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(null)
        var joins = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, _, _ ->
                joins++
                configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING))
                JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            val link = encodeEventUrl(EventLinkPayload(other, autoJoin = true))
            containerHost.onOpenUrl(link).join()
            containerHost.onOpenUrl(link).join()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(1, joins)
    }

    /**
     * The switch's confirm runs the **leave and nothing else** (capability `join-event`). It commits no
     * join: once the leave clears the config, the surviving pending join reduces — through the reduction's
     * config-absent rung — to the regular full-screen join surface, where the member makes the choices the
     * old compact dialog made for them.
     */
    @Test
    fun `confirming a switch leaves and opens the regular join surface`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val order = mutableListOf<String>()
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, _, _ -> order += "join"; configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING)); JoinCommit.Committed },
            leave = { order += "leave"; configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onConfirmSwitch()
            // The leave alone: config gone, the SAME pending join now full-screen for the new event.
            assertJoining(awaitState(), other, ready)
            cancelAndIgnoreRemainingItems()
        }
        // The leave ran; no join was committed by the confirm — that is the member's next act.
        assertEquals(listOf("leave"), order)
        assertEquals(null, configFake.config.value)
    }

    /**
     * The member's choices now cross from the join surface a switch reveals — the whole point of the
     * change. The old compact dialog could only ever produce `Direction.Both` with the album off.
     */
    @Test
    fun `a switch joins with the member's chosen direction and album`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(SAMPLE_CONFIG)
        var joinedDirection: Direction? = null
        var joinedAlbum: Boolean? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, direction, album ->
                joinedDirection = direction
                joinedAlbum = album
                configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING))
                JoinCommit.Committed
            },
            leave = { configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            skipItems(1)
            containerHost.onConfirmSwitch()
            skipItems(1)
            // On the join surface the leave revealed: receive-only, album on — unreachable before.
            containerHost.confirmJoinAs(Direction.DownloadOnly, saveToAlbum = true)
            expectState(joined(SyncHealth.Loading, config = SAMPLE_CONFIG.copy(eventId = other, name = "New Event")))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(Direction.DownloadOnly, joinedDirection)
        assertEquals(true, joinedAlbum)
    }

    /**
     * `LeaveEvent` is best-effort: a failing `ConfigStore.clear()` is logged and swallowed. The phase is
     * therefore re-derived only once the config is confirmed gone, so a failed clear leaves the
     * confirmation exactly as it was and the member can simply confirm again. (Deriving BEFORE the leave
     * would strand `Joined(pendingSwitch = ExplainAccess)`, whose dialog branch renders nothing.)
     */
    @Test
    fun `a switch whose config clear fails keeps the confirmation presented`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        var commits = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> commits++; JoinCommit.Committed },
            leave = { /* the clear failed: config stays present, as LeaveEvent's swallow leaves it */ },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onConfirmSwitch()
            // Unchanged: still the joined layer with the same confirmation, ready to confirm again.
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, commits)
    }

    /** Cancelling on the surface the leave revealed ends with no event — the create layer. */
    @Test
    fun `cancelling after a switch's leave lands on the create layer`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(SAMPLE_CONFIG)
        var commits = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> commits++; JoinCommit.Committed },
            leave = { configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            skipItems(1)
            containerHost.onConfirmSwitch()
            skipItems(1)
            containerHost.onCancelJoin()
            expectState(screen(Layer.CreateEvent()))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, commits)
        assertEquals(null, configFake.config.value)
    }

    @Test
    fun `autoJoin auto-confirms without a pending UI state`() = runTest {
        val configFake = FakeConfig(null)
        var committed: String? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, _, _ -> committed = id; configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING)); JoinCommit.Committed },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true)))
            // No JoiningEvent frame — auto-confirm goes straight from create to joined.
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(EVENT_ID, committed)
    }

    @Test
    fun `autoJoin logs the details-load abort - the headless negative oracle`() = runTest {
        // The documented on-device oracle (CLAUDE.md, spec `ios-app-shell`): a headless
        // `SNAPSYNC_EVENT_LINK` launch against a missing/invented event id must leave a
        // `debug.log` line naming the id and the outcome — the run's ONLY abort signal.
        val logged = Channel<String>(Channel.UNLIMITED)
        val containerHost = StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(SyncStatus.Loading),
                FakePermissionSource(PermissionStatus.GRANTED).permission, FakeConfig(null).config,
            ), backgroundScope,
            loadJoinDetails = { JoinLoad.NotFound },
            cutoffFormatter = fixedCutoffFormatter(),
            diagnostics = StatusDiagnostics(log = { logged.trySend(it) }),
        )
        containerHost.test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true)))
            assertEquals(
                "autoJoin aborted: details load did not succeed for $EVENT_ID (NotFound)",
                logged.receive(),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the interactive gate logs a failed details load - the dialog is invisible headlessly`() = runTest {
        // A `SNAPSYNC_EVENT_LINK` without `autoJoin=true` opens the interactive gate; a 404 there
        // parks on the NotFound dialog, which a headless run cannot see. The log line is the run's
        // only abort signal (found missing by device Session C: only the HTTP `→ 404` line appeared).
        val logged = Channel<String>(Channel.UNLIMITED)
        val containerHost = StatusContainerHost(
            StatusSources(
                FakeSyncStatusSource(SyncStatus.Loading),
                FakePermissionSource(PermissionStatus.GRANTED).permission, FakeConfig(null).config,
            ), backgroundScope,
            loadJoinDetails = { JoinLoad.NotFound },
            cutoffFormatter = fixedCutoffFormatter(),
            diagnostics = StatusDiagnostics(log = { logged.trySend(it) }),
        )
        containerHost.test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertEquals(
                "join gate: details load did not succeed for $EVENT_ID (NotFound)",
                logged.receive(),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the chosen participation direction crosses to commit on confirm`() = runTest {
        val configFake = FakeConfig(null)
        var committedDirection: Direction? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { id, name, _, _, _, _, _, direction, _ ->
                committedDirection = direction; configFake.save(EventConfig(id, name, CUTOFF, maxPhotoDate = CEILING, direction = direction)); JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs(Direction.DownloadOnly)
            expectState(joined(SyncHealth.Loading, config = downloadOnly))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(Direction.DownloadOnly, committedDirection)
        assertEquals(Direction.DownloadOnly, configFake.config.value?.direction)
    }

    @Test
    fun `autoJoin defaults the direction to Both`() = runTest {
        val configFake = FakeConfig(null)
        var committedDirection: Direction? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, direction, _ ->
                committedDirection = direction
                configFake.save(
                    EventConfig(EVENT_ID, name = "Anna's Birthday", minPhotoDate = CUTOFF, maxPhotoDate = CEILING),
                )
                JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true)))
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(Direction.Both, committedDirection)
    }

    @Test
    fun `autoJoin honors the dev direction override`() = runTest {
        val configFake = FakeConfig(null)
        var committedDirection: Direction? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            commitJoin = { _, _, _, _, _, _, _, direction, _ ->
                committedDirection = direction
                configFake.save(
                    EventConfig(EVENT_ID, name = "Anna's Birthday", minPhotoDate = CUTOFF, maxPhotoDate = CEILING),
                )
                JoinCommit.Committed
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(
                encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true, direction = "download")),
            )
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(Direction.DownloadOnly, committedDirection)
    }

    @Test
    fun `an invalid deeplink flashes the self-clearing transient error and changes nothing`() = runTest {
        // The set-then-clear choreography is presentation-owned (migration finale, step-12 D6): the
        // shell renders `transientError` verbatim and decides nothing.
        val source = FakeSyncStatusSource(SyncStatus.Loading)
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val h = host(source, backgroundScope, permission = permission, configFake = FakeConfig(null))
        // Driven through the orbit-test fixture so the intent event loop runs on THIS test's
        // virtual scheduler (its dispatcherOverride) — Orbit's public SettingsBuilder cannot pin
        // it, and calling the intent on the bare container WAS the twice-measured ios-test flake:
        // the default event loop is Dispatchers.Default, so while this test sat suspended in
        // `join()`, runTest's clock could auto-advance THROUGH the self-clear delay before the
        // first assert observed the set (set-then-clear conflated on a real thread's schedule).
        h.test(this) {
            // The reduction has to be collecting for the transient error to reach the state: it is an
            // INPUT to `reduceFrom` now, not a sibling read-model the shell polled.
            runOnCreate()
            containerHost.onOpenUrl("not a config link").join()
            runCurrent()
            assertEquals(
                "That QR code wasn't valid.",
                (containerHost.container.stateFlow.value.layer as Layer.CreateEvent).error,
            )
            // …and it self-clears a few seconds after it last appeared — the delay runs on this
            // test's virtual clock (the host scope is backgroundScope).
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(null, (containerHost.container.stateFlow.value.layer as Layer.CreateEvent).error)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `intents pass through to the requester`() = runTest {
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val requester = SpyRequester()
        host(FakeSyncStatusSource(), backgroundScope, permission = permission, requester = requester)
            .test(this) {
                containerHost.access.onRequestPermission()
                containerHost.access.onOpenSettings()
            }
        advanceUntilIdle()

        assertEquals(1, requester.requests)
        assertEquals(1, requester.settingsOpens)
    }

    @Test
    fun `onLeaveEvent invokes the injected leave action`() = runTest {
        var leaves = 0
        val configFake = FakeConfig()
        val containerHost = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, commands = UserCommands(leave = { leaves++ }),
            cutoffFormatter = fixedCutoffFormatter(),
        )
        containerHost.test(this) {
            containerHost.onLeaveEvent()
        }
        advanceUntilIdle()

        assertEquals(1, leaves)
    }

    @Test
    fun `onLeaveEvent with the default no-op leave is inert`() = runTest {
        host(FakeSyncStatusSource(), backgroundScope).test(this) {
            containerHost.onLeaveEvent()
        }
        advanceUntilIdle()
    }

    @Test
    fun `invite url derives from config and round-trips to the same event`() = runTest {
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val host = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        val invite = (host.container.stateFlow.value.layer as Layer.Joined).inviteUrl
        assertEquals(encodeEventUrl(EventLinkPayload(EVENT_ID)), invite)
        val decoded = decodeEventUrl(invite)
        assertTrue(decoded is ConfigDecodeResult.Success)
        assertEquals(EVENT_ID, decoded.payload.eventId)
    }

    @Test
    fun `no configured event means no joined state to carry an invite url`() = runTest {
        val configFake = FakeConfig(null)
        val host = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        // The invite URL has no home outside the joined state now, so "no event" IS "no invite URL":
        // there is no state that could carry one, rather than a state carrying a null (capability
        // `event-invite-qr`).
        assertTrue(host.container.stateFlow.value.layer !is Layer.Joined)
    }

    @Test
    fun `a rejected link while joined is told to the member rather than swallowed`() = runTest {
        // `onOpenUrl` decodes every delivered link, whatever layer is showing — so a member who scans a
        // bad QR while already joined used to get nothing: the message was set and the joined layer had
        // nowhere to render it. "Nothing happened" and "that code wasn't valid" are different answers.
        val h = host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED),
            configFake = FakeConfig(SAMPLE_CONFIG),
        )
        h.test(this) {
            runOnCreate()
            containerHost.onOpenUrl("not a config link").join()
            runCurrent()
            assertEquals(
                "That QR code wasn't valid.",
                (containerHost.container.stateFlow.value.layer as Layer.Joined).notice,
            )
            // …and it self-clears on the same window the create layer's does — one choreography, both layers.
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(null, (containerHost.container.stateFlow.value.layer as Layer.Joined).notice)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `the joined state carries the persisted config the name comes from`() = runTest {
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val host = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        // One name, one source: the heading, the rename prefill and the reconfigure header all read
        // this membership, so there is no second event-name value to drift from it.
        assertEquals("Anna's Birthday", (host.container.stateFlow.value.layer as Layer.Joined).membership.name)
    }

    @Test
    fun `onShareInvite hands the invite url to the injected share`() = runTest {
        val shared = mutableListOf<String>()
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val containerHost = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, commands = UserCommands(share = { shared += it }),
            cutoffFormatter = fixedCutoffFormatter(),
        )
        containerHost.test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()

        assertEquals(listOf(encodeEventUrl(EventLinkPayload(EVENT_ID))), shared)
    }

    @Test
    fun `onShareInvite with no configured event does not share`() = runTest {
        val shared = mutableListOf<String>()
        val configFake = FakeConfig(null)
        val containerHost = StatusContainerHost(
            StatusSources(FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config),
            backgroundScope, commands = UserCommands(share = { shared += it }),
            cutoffFormatter = fixedCutoffFormatter(),
        )
        containerHost.test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()

        assertTrue(shared.isEmpty())
    }

    @Test
    fun `onShareInvite with the default no-op share is inert`() = runTest {
        host(FakeSyncStatusSource(), backgroundScope).test(this) {
            containerHost.onShareInvite()
        }
        advanceUntilIdle()
    }

    @Test
    fun `observing NOT_DETERMINED never auto-requests`() = runTest {
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val requester = SpyRequester()
        host(FakeSyncStatusSource(), backgroundScope, permission = permission, requester = requester)
            .test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
        advanceUntilIdle()

        assertEquals(0, requester.requests)
    }

    // ---- the photo-access explainer (capability `join-event`) -----------------------------------------


    @Test
    fun `a first join with permission never asked explains before the dialog`() = runTest {
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.NOT_DETERMINED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        // CTA-only priming: rendering the explainer must not have raised the system dialog.
        assertEquals(0, requester.requests)
    }

    @Test
    fun `acknowledging the explainer requests permission and advances to the confirm surface`() = runTest {
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.NOT_DETERMINED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            containerHost.onAcknowledgeAccess()
            // The same name and cutoff cross over — ExplainAccess carries them solely to hand off to Ready.
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(1, requester.requests)
    }

    @Test
    fun `already-granted access skips the explainer`() = runTest {
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.GRANTED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    @Test
    fun `a limited grant skips the explainer`() = runTest {
        // The grant exists — there is no dialog to explain (capability `join-event`): LIMITED goes
        // straight to the confirm surface like GRANTED.
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.LIMITED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    /**
     * iOS raises the photo dialog at most once — from `DENIED`, `request()` is a silent no-op. An explainer
     * whose confirm produced no dialog would be a lie, so `DENIED` goes straight to the confirm surface and
     * meets the joined layer's Settings affordance after the join.
     */
    @Test
    fun `previously-denied access skips the explainer`() = runTest {
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.DENIED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    /**
     * THE BRANCH-KEEPER, first half. The loaded-phase derivation selects the explainer only when NO event
     * is configured, so while the switch confirmation is up — the previous event still configured — it
     * yields the confirm phase. This is what makes `SwitchDialog`'s `JoinPhase.Detailed && phase.step == JoinPhase.Detailed.Step.ExplainAccess -> Unit`
     * branch provably dead rather than merely unreached.
     */
    @Test
    fun `a switch does not explain before its leave`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val requester = SpyRequester()
        firstJoinGate(
            PermissionStatus.NOT_DETERMINED, requester,
            configFake = FakeConfig(SAMPLE_CONFIG), // still in the old event → the confirmation, not the explainer
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(
                joined(
                    SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED),
                    PendingSwitch(other, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT)),
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    /**
     * THE BRANCH-KEEPER, second half — and the reason the derivation runs at two points. Once the leave
     * clears the config, the SAME rule over the SAME already-loaded details now sees no event configured,
     * so a member who never granted photo access meets the explainer exactly as a first joiner would. No
     * re-fetch happens: the details come from the load the confirmation already did.
     */
    @Test
    fun `a switch explains after its leave when permission was never asked`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val requester = SpyRequester()
        val configFake = FakeConfig(SAMPLE_CONFIG)
        var loads = 0
        firstJoinGate(
            PermissionStatus.NOT_DETERMINED, requester,
            configFake = configFake,
            onLoad = { loads++ },
            leave = { configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            skipItems(1)
            containerHost.onConfirmSwitch()
            assertJoining(awaitState(), other, phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
        // Still CTA-only: reaching the explainer raises no system dialog on its own.
        assertEquals(0, requester.requests)
        // One fetch total — the post-leave derivation re-uses the details, it does not re-load them.
        assertEquals(1, loads)
    }

    /**
     * The second derivation is a no-op for every permission except `NOT_DETERMINED`: with access already
     * granted, the post-leave phase is the same confirm phase the confirmation was showing.
     */
    @Test
    fun `a granted switch re-derives to the same confirm phase`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val ready = phaseAt(JoinPhase.Detailed.Step.Ready, "New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT)
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", EventStart(CUTOFF.at), ENDS_AT, DELETES_AT) },
            leave = { configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(joined(SyncHealth.Loading, PendingSwitch(other, ready)))
            containerHost.onConfirmSwitch()
            assertJoining(awaitState(), other, ready)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `cancelling the explainer enrolls nothing and saves no config`() = runTest {
        val requester = SpyRequester()
        val configFake = FakeConfig(null)
        var commits = 0
        firstJoinGate(
            PermissionStatus.NOT_DETERMINED, requester, configFake = configFake,
            commitJoin = { _, _, _, _, _, _, _, _, _ -> commits++; JoinCommit.Committed },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.ExplainAccess, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            containerHost.onCancelJoin()
            expectState(screen(Layer.CreateEvent()))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, commits, "cancelling the explainer must not commit a join")
        assertEquals(null, configFake.config.value, "cancelling the explainer must not save a config")
    }

    @Test
    fun `a device that cannot verify itself reports Unattested rather than a cheerful Syncing`() = runTest {
        // Without this, a device whose token died shows "Syncing…" forever while every upload 401s. The
        // engine retries and loses nothing — but nothing ever arrives either, and nothing says so.
        val source = FakeSyncStatusSource()
        val attested = MutableStateFlow(true)
        host(source, backgroundScope, attested = attested).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 5, completed = 0, total = 5)
            expectState(syncing(up = Arrow.PULSING))

            attested.value = false // a renewal was attempted while the app was open — and it failed

            expectState(joined(SyncHealth.Unattested))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `it clears itself the moment the device can verify again`() = runTest {
        // This is why a user should essentially never see it: opening the app IS a wake, and every wake
        // renews. The state exists to catch the case where that renewal keeps failing.
        val source = FakeSyncStatusSource()
        val attested = MutableStateFlow(false)
        host(source, backgroundScope, attested = attested).test(this) {
            // The initial state is already Unattested (asserted by the test above), so nothing is emitted
            // until the flag flips — Orbit only re-emits on a CHANGE.
            runOnCreate()
            source.value = snapshot(pending = 5, completed = 0, total = 5)

            attested.value = true // the next wake renewed successfully

            expectState(syncing(up = Arrow.PULSING))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `missing permission outranks a missing token`() = runTest {
        // Without library access there is nothing to upload, so an unusable token is not yet the user's
        // problem — and two attention states at once would just be confusing.
        val source = FakeSyncStatusSource()
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        host(source, backgroundScope, permission = permission, attested = MutableStateFlow(false))
            .test(this) {
                // Starts Unattested (no token, granted permission); revoking access must OUTRANK it.
                runOnCreate()
                source.value = snapshot(pending = 5, completed = 0, total = 5)

                permission.permission.value = PermissionStatus.DENIED

                expectState(needsAccess(PermissionStatus.DENIED))
                cancelAndIgnoreRemainingItems()
}
    }

}

/**
 * The join gate's own half of the container's behaviour (capability `join-event`), split out because
 * [StatusContainerHostTest] outgrew its size ceiling. Same file, so both classes keep the file-private
 * fixtures — the split is by SUBJECT (what a scanned link does) rather than by an arbitrary line count.
 */
class StatusContainerHostJoinGateTest {

    // ── the gate never rests in a phase with no action (spec `join-event`) ───────────────────────

    /**
     * `Loading` and `Committing` pin NO action — `JoiningEventScreen`: "In-flight phases offer no actions"
     * — so a throw that parks the gate on one is a dead-end the member cannot leave without force-quitting.
     * These three pin the repair.
     *
     * Which phase a failed commit lands on turns on whether the membership was PERSISTED, because
     * `flow/Provision` saves the config at step 2 of 6 and the four steps after it are follow-up the next
     * foreground repeats.
     *
     * The gate is driven to `Ready` via `expectState` (which waits for it) and the settled state is read
     * afterwards, rather than expecting each emission: the injected seams here do not suspend, so the
     * `Loading` and `Committing` frames conflate away.
     */
    @Test
    fun `a commit that throws after the membership was persisted lands on the joined screen`() = runTest {
        val configFake = FakeConfig(null)
        firstJoinGate(
            PermissionStatus.GRANTED, SpyRequester(), configFake = configFake,
            // Exactly what `Provision` does: persist at step 2, then fail in one of the steps that follow.
            commitJoin = { _, _, _, _, _, _, _, _, _ ->
                configFake.save(SAMPLE_CONFIG)
                throw IllegalStateException("provision blew up after saveConfig")
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            // The pending join is discarded, so the persisted config alone decides the state: the joined
            // layer, with no overlay and no spinner. `Committing` conflates away — nothing here suspends.
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a commit that throws before the membership was persisted stays retryable`() = runTest {
        firstJoinGate(
            PermissionStatus.GRANTED, SpyRequester(), configFake = FakeConfig(null),
            commitJoin = { _, _, _, _, _, _, _, _, _ -> throw IllegalStateException("enroll blew up") },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.Ready, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            containerHost.confirmJoinAs()
            // The retryable phase, not a parked `Committing` spinner with no button on it.
            assertJoining(awaitState(), EVENT_ID, phaseAt(JoinPhase.Detailed.Step.CommitFailed, "My Party", eventStart("2026-07-06T00:00:00Z"), ENDS_AT, DELETES_AT))
            cancelAndIgnoreRemainingItems()
        }
    }

    /**
     * The `Loading` twin. Unreachable through the production binding — `HttpEventDirectory.fetch` is
     * `runCatching { … }.getOrDefault(Failed)` — but `loadJoinDetails` is a constructor seam, so the guard
     * is covered directly rather than left to a mutation that would survive the suite.
     */
    @Test
    fun `a details load that throws is retryable rather than a parked spinner`() = runTest {
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            configFake = FakeConfig(null),
            loadJoinDetails = { throw IllegalStateException("the details client threw") },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            assertJoining(awaitState(), EVENT_ID, JoinPhase.LoadFailed)
            cancelAndIgnoreRemainingItems()
        }
    }

    // ── container liveness (spec `sync-status-screen`) ───────────────────────────────────────────

    /**
     * The pin behind "A failing command never disables the status container", and the reason the
     * container configures an Orbit `exceptionHandler` at all.
     *
     * Orbit runs each intent as `runCatching { … }.exceptionOrNull()?.let { handler?.handleException(…)
     * ?: throw it }`. With **no** handler it re-throws, which cancels `RealContainer.intentJob` — a plain
     * `Job(parent)`, not a `SupervisorJob` — and from then on every `orbit()` call is a child of a
     * cancelled job and silently never runs. Every user tap crosses this one container, so the screen
     * would keep rendering its last state, look alive, and answer nothing for the rest of the process.
     *
     * ⚠️ It deliberately does **not** use `orbit-test`'s `test()` harness, which every other test here
     * uses. Measured while writing this: the harness substitutes an exception handler of its own when the
     * container carries none, so under it a later intent runs whether or not production configures one —
     * i.e. the harness MASKS exactly the defect this pins, and a `test()`-based version passes on the
     * container it is meant to be guarding. So this drives the real container on a real scope with real
     * dispatchers, and awaits real signals rather than the test scheduler.
     *
     * This is an **upstream-behaviour pin**: remove the `buildSettings` block and it fails, and an Orbit
     * upgrade that changes the semantics fails the build rather than quietly restoring the dead container.
     */
    @Test
    fun `a throwing command neither stops later commands nor kills the scope`() = runTest {
        withContext(Dispatchers.Default) {
            val boom = IllegalStateException("boom")
            val reported = CompletableDeferred<Throwable>()
            val laterCommandRan = CompletableDeferred<Unit>()
            // The container's own scope, shaped like the composition's: supervised, off the test scheduler.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val host = StatusContainerHost(
                    StatusSources(
                        FakeSyncStatusSource(), FakePermissionSource().permission, FakeConfig().config,
                    ), scope,
                    commands = UserCommands(
                        leave = { throw boom },
                        requestAccess = { laterCommandRan.complete(Unit) },
                    ),
                    cutoffFormatter = fixedCutoffFormatter(),
                    diagnostics = StatusDiagnostics(onIntentError = { reported.complete(it) }),
                )

                host.onLeaveEvent()
                // Let the failure LAND before issuing the next command. Queuing both first would prove
                // nothing: an intent's `Job` is created when `orbit()` is called, so a pair dispatched
                // together survives a cancellation arriving afterwards. The regression only shows up when
                // the later command is issued after the earlier one has already thrown.
                val throwable = withTimeout(LIVENESS_TIMEOUT) { reported.await() }
                assertEquals(boom, throwable, "the throwable must be reported, not swallowed")

                host.access.onRequestPermission()
                withTimeout(LIVENESS_TIMEOUT) { laterCommandRan.await() }

                assertTrue(scope.isActive, "the composition scope must survive")
            } finally {
                scope.cancel()
            }
        }
    }
}

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
