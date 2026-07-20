package app.snapsync.presentation

import app.snapsync.model.Arrow
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
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
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
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

// Joined-state helpers keep the assertions readable.
private fun syncing(up: Arrow, down: Arrow = Arrow.HIDDEN) = UiState.Joined(SyncHealth.Syncing(up, down))
private val inSync = UiState.Joined(SyncHealth.InSync)
private val joinedLoading = UiState.Joined(SyncHealth.Loading)
private fun needsAccess(p: PermissionStatus) = UiState.Joined(SyncHealth.NeedsAccess(p))

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

// Config seam + store as one fake: save writes the cell, which is exactly how the real Keychain
// adapter behaves. Defaults to present so the sync-state tests reach the joined layer.
private val SAMPLE_CONFIG = EventConfig(eventId = EVENT_ID, name = "Anna's Birthday", minPhotoDate = CUTOFF)

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
    override fun create(name: String, startsAt: String) {
        created += name
        starts += startsAt
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
private const val NOW_CUTOFF = "2026-07-09T12:00:00Z"

/** A membership always carries a cutoff (capability `photo-selection-policy`); no join can pass `null`. */
private const val CUTOFF = "2026-07-06T14:32:11Z"

/**
 * The **real** [CutoffFormatter] on a fixed instant in UTC. Deliberately not a hand-rolled fake: the
 * `createdAt` → cutoff normalization depends on the actual ISO-8601 codec (the backend mints `createdAt`
 * with milliseconds, which must be truncated to the second-precision invariant), and a fake formatter
 * would assert nothing about that.
 */
private fun fixedCutoffFormatter() = CutoffFormatter(
    now = { Instant.parse(NOW_CUTOFF) },
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
    commitJoin: suspend (String, String, String, String, Direction, Boolean) -> Boolean =
        { _, _, _, _, _, _ -> false },
    leave: suspend () -> Unit = {},
    attested: AttestedSource = AlwaysAttested,
) = StatusContainerHost(
    source, permission.permission, configFake.config, scope,
    loadJoinDetails = loadJoinDetails,
    commands = UserCommands(
        leave = leave, commitJoin = commitJoin,
        requestAccess = requester::request, openSettings = requester::openSettings,
    ),
    cutoffFormatter = fixedCutoffFormatter(),
    attestedSource = attested,
)

class StatusContainerHostTest {

    // ── the not-started clock line (capability `sync-status-screen`) ──────────────────────────────

    /** An event that has not begun: its start is after the fixed NOW_CUTOFF (2026-07-09T12:00:00Z). */
    private val futureStart = "2026-07-09T18:00:00Z"

    private fun notStartedConfig(startsAt: String = futureStart) = EventConfig(
        eventId = EVENT_ID,
        name = "Anna's Birthday",
        // The floor guarantees this shape: `minPhotoDate == max(chosen, startsAt) == startsAt` pre-start.
        minPhotoDate = startsAt,
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
        assertEquals(UiState.Joined(SyncHealth.NotStarted(futureStart)), host.container.stateFlow.value)
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
            UiState.Joined(SyncHealth.NeedsAccess(PermissionStatus.DENIED)),
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
            configFake = FakeConfig(notStartedConfig(startsAt = NOW_CUTOFF)),
        )
        assertEquals(inSync, host.container.stateFlow.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the foreground tick retires the clock line when the start passes`() = runTest {
        // NotStarted is the one health driven by WALL-CLOCK time, not the ledger — no snapshot emission
        // would ever retire it. Without the tick the clock line would sit there past the start until
        // something unrelated happened to re-emit.
        val clock = MovableClock(Instant.parse(NOW_CUTOFF))
        val config = FakeConfig(notStartedConfig())
        val host = StatusContainerHost(
            FakeSyncStatusSource(snapshot(completed = 0, total = 0)),
            FakePermissionSource(PermissionStatus.GRANTED).permission,
            config.config, backgroundScope,
            cutoffFormatter = movableCutoffFormatter(clock),
        )
        host.test(this) {
            runOnCreate()

            // A minute passes; the event still has not begun, so the line stands.
            advanceTimeBy(61_000)
            assertEquals(
                UiState.Joined(SyncHealth.NotStarted(futureStart)),
                containerHost.container.stateFlow.value,
            )

            // The start passes. The next tick re-derives from the snapshot — no ledger event required,
            // and no source has changed.
            clock.instant = Instant.parse("2026-07-09T18:00:01Z")
            advanceTimeBy(61_000)
            assertEquals(inSync, containerHost.container.stateFlow.value)

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
        val cfg = FakeConfig(EventConfig(EVENT_ID, "Anna's Birthday", CUTOFF, direction = direction))
        return StatusContainerHost(
            source, FakePermissionSource(PermissionStatus.GRANTED).permission, cfg.config, scope,
            downloadSource = InMemoryDownloadStatusSource(download),
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
                expectState(syncing(up = Arrow.HIDDEN, down = Arrow.PULSING))
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
                expectState(inSync)
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
                expectState(syncing(up = Arrow.PULSING, down = Arrow.HIDDEN))
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
            FakeSyncStatusSource(), permission.permission, config.config, scope,
            creationStatusSource = MutableCreationStatusSource(creation),
            commands = UserCommands(create = creator::create),
            cutoffFormatter = fixedCutoffFormatter(),
        )
    }

    @Test
    fun `config absent with idle creation shows the create input`() = runTest {
        val host = createHost(CreationStatus.Idle, scope = backgroundScope)
        assertEquals(UiState.CreateEvent(error = null), host.container.stateFlow.value)
    }

    @Test
    fun `config absent with in-flight creation shows the creating state`() = runTest {
        val host = createHost(CreationStatus.InFlight, scope = backgroundScope)
        assertEquals(UiState.CreatingEvent, host.container.stateFlow.value)
    }

    @Test
    fun `config absent with an invalid-name failure shows the input with the name error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.INVALID_NAME), scope = backgroundScope)
        assertEquals(
            UiState.CreateEvent(error = "That name wasn't accepted. Try a different one."),
            host.container.stateFlow.value,
        )
    }

    @Test
    fun `config absent with a server failure shows the input with the server error`() = runTest {
        val host = createHost(CreationStatus.Failed(CreationFailureReason.SERVER), scope = backgroundScope)
        assertEquals(UiState.CreateEvent(error = "Couldn't reach the server."), host.container.stateFlow.value)
    }

    @Test
    fun `create layer ignores permission`() = runTest {
        val host = createHost(
            CreationStatus.Idle,
            permission = FakePermissionSource(PermissionStatus.DENIED),
            scope = backgroundScope,
        )
        // Config absent outranks a non-granted permission — still the create input, not the joined layer.
        assertEquals(UiState.CreateEvent(), host.container.stateFlow.value)
    }

    @Test
    fun `onCreateEvent delegates to the creator`() = runTest {
        val creator = SpyCreator()
        val host = createHost(CreationStatus.Idle, creator = creator, scope = backgroundScope)
        host.test(this) {
            containerHost.onCreateEvent("My Party", LocalDateTime(2026, 7, 14, 18, 0))
        }
        advanceUntilIdle()
        assertEquals(listOf("My Party"), creator.created)
        // The screen hands over a LOCAL pick; the container converts it through the one cutoff codec, so
        // `:ui:screens` never touches a clock, a timezone, or a cutoff string.
        assertEquals(listOf("2026-07-14T18:00:00Z"), creator.starts)
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
            FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED).permission,
            config.config, backgroundScope,
            commands = UserCommands(
                commitJoin = { id, name, startsAt, cutoff, direction, _ ->
                    config.save(EventConfig(id, name, cutoff, startsAt, direction))
                    true
                },
            ),
            loadJoinDetails = { JoinLoad.Found("My Party", "2026-07-06T00:00:00Z") },
            cutoffFormatter = fixedCutoffFormatter(),
        )
        host.test(this) {
            runOnCreate()
            // The create use-case's `onMinted` hook fires exactly this (non-auto-confirmed).
            containerHost.onEventCreated(EVENT_ID)
            // minted → routed into the gate → loaded, offering Join with the event's start as the default.
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")))
            containerHost.onConfirmJoin("2026-07-06T00:00:00Z", Direction.Both, false)
            expectState(inSync) // confirm provisions → config present + granted + snapshot total 0 → settled
            cancelAndIgnoreRemainingItems()
        }
        assertEquals("2026-07-06T00:00:00Z", config.config.value?.minPhotoDate)
    }

    @Test
    fun `a failed create surfaces the inline error and opens no gate`() = runTest {
        val config = FakeConfig(null)
        val creationStatus = MutableCreationStatusSource()
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource(PermissionStatus.GRANTED).permission,
            config.config, backgroundScope,
            creationStatusSource = creationStatus,
            cutoffFormatter = fixedCutoffFormatter(),
        )
        host.test(this) {
            runOnCreate()
            // The use-case reports a transient failure through the status source and never fires
            // `onMinted` (CreateEventTest pins that) — the inline error shows and no gate opens.
            creationStatus.set(CreationStatus.Failed(CreationFailureReason.SERVER))
            expectState(UiState.CreateEvent(error = "Couldn't reach the server."))
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
            UiState.Joined(SyncHealth.InSync, canChoosePhotos = true),
            container.stateFlow.value,
        )
    }

    @Test
    fun `a full grant offers no choose-photos affordance`() = runTest {
        val source = FakeSyncStatusSource(snapshot(completed = 34, total = 34))
        val permission = FakePermissionSource(PermissionStatus.GRANTED)
        val container = host(source, backgroundScope, permission = permission).container

        assertEquals(false, (container.stateFlow.value as UiState.Joined).canChoosePhotos)
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

        assertEquals(UiState.CreateEvent(), container.stateFlow.value)
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

        assertEquals(UiState.CreateEvent(), container.stateFlow.value)
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
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", "2026-07-06T14:32:11Z") },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", "2026-07-06T14:32:11Z")))
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
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", "2026-07-04T18:00:00Z") },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            // Note it is NOT NOW_CUTOFF (2026-07-09): the event's own start wins.
            expectState(
                UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", "2026-07-04T18:00:00Z")),
            )
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `autoJoin with no explicit cutoff commits with the event start`() = runTest {
        // The headless dev launch has no surface on which an empty cutoff row could be noticed, so the
        // default matters most here (capability `photo-selection-policy`).
        val configFake = FakeConfig(null)
        var committedCutoff: String? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { id, name, _, cutoff, _, _ ->
                committedCutoff = cutoff; configFake.save(EventConfig(id, name, cutoff)); true
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
        var seenStartsAt: String? = null
        var seenCutoff: String? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { id, name, startsAt, cutoff, _, _ ->
                seenStartsAt = startsAt
                seenCutoff = cutoff
                configFake.save(EventConfig(id, name, cutoff))
                true
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(
                encodeEventUrl(
                    EventLinkPayload(EVENT_ID, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z"),
                ),
            )
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals("2001-01-01T00:00:00Z", seenCutoff, "the hostile value is passed through, not swallowed")
        assertEquals(CUTOFF, seenStartsAt, "...alongside the floor that will defeat it")
    }

    @Test
    fun `autoJoin honours an explicit dev cutoff over the createdAt default`() = runTest {
        val configFake = FakeConfig(null)
        var committedCutoff: String? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", "2026-07-06T14:32:11Z") },
            commitJoin = { id, name, _, cutoff, _, _ ->
                committedCutoff = cutoff; configFake.save(EventConfig(id, name, cutoff)); true
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(
                encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true, minPhotoDate = "2026-05-05T05:05:05Z")),
            )
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals("2026-05-05T05:05:05Z", committedCutoff)
    }

    @Test
    fun `a first-join deeplink opens the gate and confirming enrolls then joins`() = runTest {
        val configFake = FakeConfig(null)
        val enrolled = mutableListOf<String>()
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { id, name, _, _, _, _ -> enrolled += id; configFake.save(EventConfig(id, name, CUTOFF)); true },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", CUTOFF)))
            containerHost.onConfirmJoin(CUTOFF, Direction.Both, false)
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
            commitJoin = { _, _, _, _, _, _ -> commits++; true },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.NotFound))
            containerHost.onConfirmJoin(CUTOFF, Direction.Both, false) // inert when not Ready
            containerHost.onCancelJoin()
            expectState(UiState.CreateEvent())
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
            loadJoinDetails = { if (attempt++ == 0) JoinLoad.Failed else JoinLoad.Found("Anna's Birthday", CUTOFF) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.LoadFailed))
            containerHost.onRetryLoad()
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", CUTOFF)))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `a failed enrollment leaves a retryable commit-failed state and does not join`() = runTest {
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(null),
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { _, _, _, _, _, _ -> false },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", CUTOFF)))
            containerHost.onConfirmJoin(CUTOFF, Direction.Both, false)
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.CommitFailed("Anna's Birthday", CUTOFF)))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `re-scanning the already-joined event is a no-op`() = runTest {
        var loads = 0
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = FakeConfig(SAMPLE_CONFIG),
            loadJoinDetails = { loads++; JoinLoad.Found("x", CUTOFF) },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectNoItems()
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, loads)
    }

    @Test
    fun `a switch scans a different event and confirming leaves then joins`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val order = mutableListOf<String>()
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("New Event", CUTOFF) },
            commitJoin = { id, name, _, _, _, _ -> order += "join"; configFake.save(EventConfig(id, name, CUTOFF)); true },
            leave = { order += "leave"; configFake.clear() },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(UiState.Joined(SyncHealth.Loading, PendingSwitch(other, JoinPhase.Ready("New Event", CUTOFF))))
            containerHost.onConfirmSwitch(CUTOFF, Direction.Both)
            // leave clears config + join saves the new one; conflated to the settled joined layer.
            expectState(joinedLoading)
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(listOf("leave", "join"), order)
        assertEquals(other, configFake.config.value?.eventId)
    }

    @Test
    fun `autoJoin auto-confirms without a pending UI state`() = runTest {
        val configFake = FakeConfig(null)
        var committed: String? = null
        host(
            FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
            permission = FakePermissionSource(PermissionStatus.GRANTED), configFake = configFake,
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { id, name, _, _, _, _ -> committed = id; configFake.save(EventConfig(id, name, CUTOFF)); true },
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
            FakeSyncStatusSource(SyncStatus.Loading), FakePermissionSource(PermissionStatus.GRANTED).permission,
            FakeConfig(null).config, backgroundScope,
            loadJoinDetails = { JoinLoad.NotFound },
            cutoffFormatter = fixedCutoffFormatter(),
            log = { logged.trySend(it) },
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
            FakeSyncStatusSource(SyncStatus.Loading), FakePermissionSource(PermissionStatus.GRANTED).permission,
            FakeConfig(null).config, backgroundScope,
            loadJoinDetails = { JoinLoad.NotFound },
            cutoffFormatter = fixedCutoffFormatter(),
            log = { logged.trySend(it) },
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
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { id, name, _, _, direction, _ ->
                committedDirection = direction; configFake.save(EventConfig(id, name, CUTOFF, direction = direction)); true
            },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("Anna's Birthday", CUTOFF)))
            containerHost.onConfirmJoin(CUTOFF, Direction.DownloadOnly, false)
            expectState(joinedLoading)
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
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { _, _, _, _, direction, _ -> committedDirection = direction; configFake.save(EventConfig(EVENT_ID, minPhotoDate = CUTOFF)); true },
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
            loadJoinDetails = { JoinLoad.Found("Anna's Birthday", CUTOFF) },
            commitJoin = { _, _, _, _, direction, _ -> committedDirection = direction; configFake.save(EventConfig(EVENT_ID, minPhotoDate = CUTOFF)); true },
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
        // `intent` returns its Job — joining it is what makes the assertion deterministic (the
        // container's event loop runs off the virtual scheduler); the self-clear delay then runs
        // on THIS test's virtual clock (the host scope is backgroundScope).
        h.onOpenUrl("not a config link").join()
        assertEquals("That QR code wasn't valid.", h.transientError.value)
        // …and it self-clears a few seconds after it last appeared.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(null, h.transientError.value)
    }

    @Test
    fun `intents pass through to the requester`() = runTest {
        val permission = FakePermissionSource(PermissionStatus.NOT_DETERMINED)
        val requester = SpyRequester()
        host(FakeSyncStatusSource(), backgroundScope, permission = permission, requester = requester)
            .test(this) {
                containerHost.onRequestPermission()
                containerHost.onOpenSettings()
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
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
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
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        assertEquals(encodeEventUrl(EventLinkPayload(EVENT_ID)), host.inviteUrl.value)
        val decoded = decodeEventUrl(host.inviteUrl.value!!)
        assertTrue(decoded is ConfigDecodeResult.Success)
        assertEquals(EVENT_ID, decoded.payload.eventId)
    }

    @Test
    fun `invite url is null when no event is configured`() = runTest {
        val configFake = FakeConfig(null)
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        assertEquals(null, host.inviteUrl.value)
    }

    @Test
    fun `event name derives from the persisted config`() = runTest {
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val host = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
            backgroundScope, cutoffFormatter = fixedCutoffFormatter(),
        )
        assertEquals("Anna's Birthday", host.eventName.value)
    }

    @Test
    fun `onShareInvite hands the invite url to the injected share`() = runTest {
        val shared = mutableListOf<String>()
        val configFake = FakeConfig(SAMPLE_CONFIG)
        val containerHost = StatusContainerHost(
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
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
            FakeSyncStatusSource(), FakePermissionSource().permission, configFake.config,
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

    /** A first join (config absent) whose details load succeeds — the gate the explainer is decided in. */
    private fun TestScope.firstJoinGate(
        permission: PermissionStatus,
        requester: SpyRequester,
        configFake: FakeConfig = FakeConfig(null),
        commitJoin: suspend (String, String, String, String, Direction, Boolean) -> Boolean =
            { _, _, _, _, _, _ -> false },
    ) = host(
        FakeSyncStatusSource(SyncStatus.Loading), backgroundScope,
        permission = FakePermissionSource(permission),
        requester = requester,
        configFake = configFake,
        loadJoinDetails = { JoinLoad.Found("My Party", "2026-07-06T00:00:00Z") },
        commitJoin = commitJoin,
    )

    @Test
    fun `a first join with permission never asked explains before the dialog`() = runTest {
        val requester = SpyRequester()
        firstJoinGate(PermissionStatus.NOT_DETERMINED, requester).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(
                UiState.JoiningEvent(EVENT_ID, JoinPhase.ExplainAccess("My Party", "2026-07-06T00:00:00Z")),
            )
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
            expectState(
                UiState.JoiningEvent(EVENT_ID, JoinPhase.ExplainAccess("My Party", "2026-07-06T00:00:00Z")),
            )
            containerHost.onAcknowledgeAccess()
            // The same name and cutoff cross over — ExplainAccess carries them solely to hand off to Ready.
            expectState(
                UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")),
            )
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
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")))
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
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")))
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
            expectState(UiState.JoiningEvent(EVENT_ID, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")))
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    /**
     * THE BRANCH-KEEPER. `readyOrExplain` gates the explainer on `config == null`, so a switch — which by
     * definition has a config — can never produce it. This is what makes `SwitchDialog`'s
     * `is JoinPhase.ExplainAccess -> Unit` branch provably dead rather than merely unreached.
     */
    @Test
    fun `a switch never explains even with permission never asked`() = runTest {
        val other = "22222222-2222-4222-8222-222222222222"
        val requester = SpyRequester()
        firstJoinGate(
            PermissionStatus.NOT_DETERMINED, requester,
            configFake = FakeConfig(SAMPLE_CONFIG), // already in an event → a switch, not a first join
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(other)))
            expectState(
                UiState.Joined(
                    SyncHealth.NeedsAccess(PermissionStatus.NOT_DETERMINED),
                    PendingSwitch(other, JoinPhase.Ready("My Party", "2026-07-06T00:00:00Z")),
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
        assertEquals(0, requester.requests)
    }

    @Test
    fun `cancelling the explainer enrolls nothing and saves no config`() = runTest {
        val requester = SpyRequester()
        val configFake = FakeConfig(null)
        var commits = 0
        firstJoinGate(
            PermissionStatus.NOT_DETERMINED, requester, configFake = configFake,
            commitJoin = { _, _, _, _, _, _ -> commits++; true },
        ).test(this) {
            runOnCreate()
            containerHost.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
            expectState(
                UiState.JoiningEvent(EVENT_ID, JoinPhase.ExplainAccess("My Party", "2026-07-06T00:00:00Z")),
            )
            containerHost.onCancelJoin()
            expectState(UiState.CreateEvent())
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
        val attested = MutableAttestedSource(true)
        host(source, backgroundScope, attested = attested).test(this) {
            runOnCreate()
            source.value = snapshot(pending = 5, completed = 0, total = 5)
            expectState(syncing(up = Arrow.PULSING))

            attested.set(false) // a renewal was attempted while the app was open — and it failed

            expectState(UiState.Joined(SyncHealth.Unattested))
            cancelAndIgnoreRemainingItems()
        }
    }

    @Test
    fun `it clears itself the moment the device can verify again`() = runTest {
        // This is why a user should essentially never see it: opening the app IS a wake, and every wake
        // renews. The state exists to catch the case where that renewal keeps failing.
        val source = FakeSyncStatusSource()
        val attested = MutableAttestedSource(false)
        host(source, backgroundScope, attested = attested).test(this) {
            // The initial state is already Unattested (asserted by the test above), so nothing is emitted
            // until the flag flips — Orbit only re-emits on a CHANGE.
            runOnCreate()
            source.value = snapshot(pending = 5, completed = 0, total = 5)

            attested.set(true) // the next wake renewed successfully

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
        host(source, backgroundScope, permission = permission, attested = MutableAttestedSource(false))
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
