package app.snapsync.presentation

import app.snapsync.model.Arrow
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserCommands
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.feature.creation.CreationFailureReason
import app.snapsync.feature.creation.CreationStatus
import app.snapsync.feature.creation.CreationStatusSource
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.feature.download.DownloadProgress
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.InMemoryDownloadStatusSource
import app.snapsync.model.SyncStatus
import app.snapsync.model.SyncProgress
import app.snapsync.feature.status.SyncStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDateTime
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class StatusContainerHost(
    syncSource: SyncStatusSource,
    // The permission read-model, observed as a bare StateFlow (spec `module-architecture`, "Commands
    // cross one door": presentation observes read-model StateFlows directly and never names `ports/` —
    // the armed presentation gate enforces it; the shell/compose passes the port's flow in). A `val`
    // (not a bare param) because the join gate also reads its CURRENT value at the moment the details
    // load resolves, to decide whether to show the photo-access explainer (capability `join-event`).
    // That is a snapshot, not an observation — the phase advances only by user action.
    private val permission: StateFlow<PermissionStatus>,
    // The membership-config read-model, likewise a bare StateFlow handed in by the composition.
    private val config: StateFlow<EventConfig?>,
    private val scope: CoroutineScope,
    // The create-status read-model. The default makes the create layer inert (always-Idle source) so
    // non-iOS hosts and tests that don't exercise create construct unchanged; iOS injects the same
    // instance the create use-case drives.
    creationStatusSource: CreationStatusSource = MutableCreationStatusSource(),
    // The user-tap **command bundle** (spec `module-architecture`, "Commands cross one door"):
    // leave / create / commitJoin / share / requestAccess / openSettings — `model/` vocabulary whose
    // live instance is built only in `compose/` (`AppCore.userCommands`) — this container fires
    // commands solely through it and never references a feature command (or `ports/`, or `flow/`)
    // directly; the armed presentation gate enforces the import law. The default is fully inert (the
    // identity bundle the `UserCommands` type itself defines — a model-typed null object, not command
    // wiring, so constructing it here is outside the built-only-in-compose rule and the gate's
    // letter), so non-iOS hosts and tests that don't exercise a command construct unchanged.
    //
    // NB the CLAMP (`minPhotoDate = max(chosen, startsAt)`) is applied on the far side of the
    // `commitJoin` command, inside `JoinEvent` — this container passes the chosen value through raw,
    // so no entry path can reach a provision without the floor by forgetting to clamp here.
    private val commands: UserCommands = UserCommands(),
    // The join gate's details READ (capability `join-event`), injected as a plain lambda:
    // `loadJoinDetails` = `GET /events/:id` mapped to a block/retry/ready outcome. A query the gate
    // reduces on, not a command — so it stays an individual seam beside the bundle. The default is
    // inert (load fails) so hosts and tests that don't exercise join construct unchanged; iOS binds
    // it to the `JoinEvent` use-case's fetch composed with `feature/membership`'s `toJoinLoad`.
    private val loadJoinDetails: suspend (eventId: String) -> JoinLoad = { JoinLoad.Failed },
    // Supplies "now" as a cutoff string and converts a local pick (capability `photo-selection-policy`).
    // Injected — with NO default (migration step 9): a default would have to read the system clock
    // here, which is exactly the through-ports law violation this parameter repays. Production wires
    // the `Clock`/`TimeZoneSource` ports; tests pass a fixed instant and zone.
    private val cutoffFormatter: CutoffFormatter,
    // Dev-path abort logging: the headless negative oracle for a `SNAPSYNC_EVENT_LINK` run (autoJoin
    // has no UI to show a load/commit failure, and a gate parked on a failed details load has no one
    // watching its dialog). No-op by default; iOS wires it into `debug.log`.
    private val log: (String) -> Unit = {},
    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    // Exposed as a screen-level StateFlow (like `inviteUrl`), NOT folded into `UiState` — it's an
    // independent indicator that doesn't gate upload classification. Defaults to inert (always 0 of 0)
    // so non-iOS hosts/tests construct unchanged; iOS injects the store-backed source.
    downloadSource: DownloadStatusSource = InMemoryDownloadStatusSource(),
    // Attestation health (capability `device-attestation`): false only when this device has no valid
    // token AND the attempt to obtain one failed. Defaults to always-true so non-iOS hosts and every
    // existing test construct unchanged; iOS injects the flag the composition root sets from
    // `DeviceAttestation.ensureFresh()`.
    attestedSource: AttestedSource = AlwaysAttested,
    // Event-driven overlay for an in-progress join/switch confirmation (capability `join-event`). Not
    // derived from the level-triggered sources — the gate sets it on a decoded interactive event link and
    // clears it on commit/cancel, folded into the reduction as a sixth flow. Injected (defaulting to a
    // fresh internal instance) so the forge harness can forge any `JoinPhase` by writing this cell
    // directly; production and the full-stack harness accept the default and let the gate drive it.
    private val pending: MutablePendingJoinSource = MutablePendingJoinSource(),
) : ContainerHost<UiState, Nothing> {

    /**
     * "Now", re-emitted every minute **only** while the joined event has not begun (capability
     * `sync-status-screen`).
     *
     * `SyncHealth.NotStarted` is the one health that depends on **wall-clock time** rather than the
     * ledger, so no snapshot emission would ever retire it — without this, the clock line would sit there
     * past the start until something unrelated happened to re-emit.
     *
     * It **self-terminates**: the loop breaks the moment `now >= startsAt`, so a started event carries no
     * timer for the rest of its life, and an event with no config carries none at all. And because a
     * backgrounded iOS app is *suspended* — its coroutines do not run — a `delay`-based ticker on the
     * container scope is already foreground-only in practice; no lifecycle hook is needed to get that.
     *
     * Up to a minute of staleness is accepted: nothing of the member's can upload before the start in any
     * case (the floor guarantees it), so a briefly-late transition costs the label and nothing else.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val nowTick: Flow<String> =
        config
            .map { it?.let { c -> c.startsAt to c.endsAt } }
            .distinctUntilChanged()
            .flatMapLatest { bounds ->
                flow {
                    while (true) {
                        val now = cutoffFormatter.nowCutoff()
                        emit(now)
                        if (bounds == null) return@flow
                        val (startsAt, endsAt) = bounds
                        // Two wall-clock lines depend on this tick: NotStarted (until `startsAt`) and the
                        // "Event ended" marker (until `endsAt`). Keep ticking while EITHER boundary is still
                        // ahead; once both have passed, no clock-driven line can change, so the timer
                        // self-terminates. Canonical fixed-width UTC ⇒ lexicographic order IS chronological.
                        // (A backgrounded iOS app is suspended, so this is foreground-only in practice.)
                        val startPassed = now >= startsAt
                        val endPassed = endsAt == null || now >= endsAt
                        if (startPassed && endPassed) return@flow
                        delay(NOT_STARTED_TICK_MILLIS)
                    }
                }
            }

    override val container: Container<UiState, Nothing> =
        scope.container(
            // All seams hold their current truth synchronously, so the first state the screen can ever
            // render derives from real values — never a guess or a placeholder.
            reduceFrom(
                config.value,
                permission.value,
                syncSource.status.value,
                creationStatusSource.creationStatus.value,
                downloadSource.progress.value,
                pending.state.value,
                cutoffFormatter.nowCutoff(),
                attestedSource.attested.value,
            ),
        ) {
            intent {
                // The sources combine into a holder; each new value reduces straight to a UI state.
                // The only clock-driven input is `nowTick`, and it runs ONLY while an event has not begun
                // (see above) — every other re-emission is a real source change or a pending-join
                // transition.
                combine(
                    config,
                    permission,
                    syncSource.status,
                    creationStatusSource.creationStatus,
                    downloadSource.progress,
                    pending.state,
                    nowTick,
                    attestedSource.attested,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    reduceFrom(
                        values[0] as EventConfig?,
                        values[1] as PermissionStatus,
                        values[2] as SyncStatus,
                        values[3] as CreationStatus,
                        values[4] as DownloadProgress,
                        values[5] as PendingJoin?,
                        values[6] as String,
                        values[7] as Boolean,
                    )
                }
                    .collect { ui -> reduce { ui } }
            }
        }

    /**
     * The event's invite link, derived from the persisted config's `eventId` via
     * `encodeEventUrl(EventLinkPayload(eventId))` — the inverse of the decode run on a scanned QR.
     * One source feeding both the rendered QR and the share action so the two can never drift; `null`
     * whenever no event is configured. Deterministic — the same URL a scanner of the event's QR would
     * receive.
     */
    val inviteUrl: StateFlow<String?> =
        config
            .map { it?.inviteUrl() }
            .stateIn(scope, SharingStarted.Eagerly, config.value?.inviteUrl())

    /**
     * The transient invalid-link error (capability `event-link`): an event link arrived that the
     * decoder rejected, so the create screen flashes a self-clearing message on its inline error
     * line without changing persisted state. A screen-level `StateFlow` like [inviteUrl] — it never
     * enters `UiState`, and the self-clear choreography lives HERE, in presentation (spec
     * `module-architecture`, "Commands cross one door": multi-step interactions are
     * presentation-owned choreography, and interaction state dies with the UI). It replaced the
     * former one-shot side-effect channel at the migration finale: the channel had exactly one
     * consumer — the untested iOS shell, which carried the set-then-clear choreography as the last
     * decision in `MainViewController` (step-12 D6 assigned its drain here).
     *
     * A rejected link while the message is already showing re-arms the full window (the timer
     * restarts) — the deliberate reading of "self-clearing a few seconds after it LAST appeared".
     */
    val transientError: StateFlow<String?>
        get() = transientErrorState

    private val transientErrorState = MutableStateFlow<String?>(null)
    private var transientErrorClear: Job? = null

    private fun showTransientError() {
        transientErrorState.value = INVALID_LINK_MESSAGE
        transientErrorClear?.cancel()
        transientErrorClear = scope.launch {
            delay(TRANSIENT_ERROR_MILLIS)
            transientErrorState.value = null
        }
    }

    /**
     * The joined event's human-readable name for the screen title (fetched by id after joining, so it
     * may be `null` until a foreground refresh fills it). A screen-level param like [inviteUrl] — it
     * does not enter `UiState`, so the reduction gains no branch for it.
     */
    val eventName: StateFlow<String?> =
        config
            .map { it?.name }
            .stateIn(scope, SharingStarted.Eagerly, config.value?.name)

    /**
     * The joined membership's current settings for the reconfigure surface (capability
     * `reconfigure-membership`): the persisted [EventConfig], or `null` when no event is configured. A
     * screen-level param like [inviteUrl]/[eventName] — it does NOT enter `UiState`, so the reduction
     * gains no branch. The settings surface pre-fills its controls (direction / cutoff / album) from
     * this, seeds the cutoff preset off `minPhotoDate` vs `startsAt`, and Save fires [onReconfigure].
     */
    val membership: StateFlow<EventConfig?> = config

    /**
     * Create a new event with [name], starting at [startsAt] (event-creation-ui). Delegates to the
     * injected [EventCreator] (fire-and-forget): it mints the event and, on success, provisions it
     * through the same path a scanned QR uses (config goes present, the reduction leaves the create
     * layer). Permission is not consulted here — a missing grant surfaces afterward via
     * `PermissionBlocked`. The in-flight and failure outcomes arrive back through `CreationStatusSource`;
     * nothing is reduced here.
     *
     * [startsAt] arrives as the screen's **local** wall-clock pick and is converted here, through the same
     * [CutoffFormatter] the join surface uses — so the app has exactly one origin of "now" and one
     * local→UTC conversion, and `:ui:screens` stays free of any clock or timezone knowledge.
     */
    fun onCreateEvent(name: String, startsAt: LocalDateTime, endsAt: LocalDateTime) =
        intent { commands.create(name, cutoffFormatter.toCutoff(startsAt), cutoffFormatter.toCutoff(endsAt)) }

    fun onRequestPermission() = intent { commands.requestAccess() }

    /** The joined layer's "Choose more photos" tap (capability `limited-photo-access`) — presents the
     *  platform's limited-library picker; the selection outcome arrives via the selection seam. */
    fun onChoosePhotos() = intent { commands.choosePhotos() }

    fun onOpenSettings() = intent { commands.openSettings() }

    /**
     * Leave the configured event (confirmed in the UI before this fires). Delegates to the injected
     * leave action, which disables the producer and clears the persisted config (the extension resets
     * its own private ledger on its next cycle). The config going `null` makes the reduction fall back
     * to the setup gate — no new `UiState` and no reduction branch here.
     */
    fun onLeaveEvent() = intent { commands.leave() }

    /**
     * Share the event's invite link (the joined-layer share action). Hands the current invite URL
     * to the injected platform share; fire-and-forget — no result is observed, and `UiState` is
     * unaffected (the system share UI is presented over the screen, not part of it). Inert when no
     * event is configured (no URL) or no real share is bound (the no-op default).
     */
    fun onShareInvite() = intent { inviteUrl.value?.let { commands.share(it) } }

    /**
     * Apply a reconfigure of the joined membership (capability `reconfigure-membership`), confirmed on
     * the settings surface's Save. Delegates to the injected [UserCommands.reconfigure] with [eventId] —
     * the event the surface was opened for — so a switch that landed mid-edit makes the use-case a no-op
     * rather than overwriting a different membership. The clamp to the `startsAt` floor is applied on the
     * far side (inside `ReconfigureEvent`), like `commitJoin` — this passes the chosen values through raw.
     * Fire-and-forget; the change lands via the config read-model on the next cycle, so no `UiState`
     * branch here. Opening/closing the surface is screen-local navigation and never reaches this door.
     */
    fun onReconfigure(
        eventId: String,
        direction: Direction,
        minPhotoDate: String,
        maxPhotoDate: String?,
        saveToAlbum: Boolean,
    ) = intent { commands.reconfigure(eventId, direction, minPhotoDate, maxPhotoDate, saveToAlbum) }

    /**
     * An event link arrived (forwarded raw from the platform). Decode it with the shared codec; an
     * invalid link flashes the transient error without touching state. A valid link opens the **join
     * gate** (capability `join-event`): `autoJoin` auto-confirms headlessly, otherwise a first join
     * opens the full-screen confirmation and a different event while joined opens a switch
     * confirmation. Re-scanning the already-joined event is a no-op (never re-enrolls).
     */
    fun onOpenUrl(raw: String) = intent {
        when (val result = decodeEventUrl(raw)) {
            is ConfigDecodeResult.Failure -> showTransientError()
            is ConfigDecodeResult.Success -> {
                val eventId = result.payload.eventId
                val current = config.value
                when {
                    result.payload.autoJoin ->
                        autoConfirm(
                            eventId,
                            result.payload.minPhotoDate,
                            result.payload.maxPhotoDate,
                            result.payload.direction,
                            result.payload.saveToAlbum,
                        )
                    current == null -> startPending(eventId)               // first join → JoiningEvent
                    current.eventId != eventId -> startPending(eventId)    // switch → Joined.pendingSwitch
                    else -> Unit                                           // same event → no-op
                }
            }
        }
    }

    /** Retry the details fetch after a transient load failure. */
    fun onRetryLoad() = intent {
        val p = pending.state.value ?: return@intent
        pending.set(p.copy(phase = JoinPhase.Loading))
        loadInto(p.eventId)
    }

    /**
     * Confirm a first join with the chosen capture-date [cutoff], participation [direction], and album
     * choice [saveToAlbum] (capability `event-album`): enroll → provision (no leave).
     */
    fun onConfirmJoin(cutoff: String, until: String, direction: Direction, saveToAlbum: Boolean) =
        intent {
            commit(withLeave = false, cutoff = cutoff, until = until, direction = direction, saveToAlbum = saveToAlbum)
        }

    /**
     * Confirm a switch: leave the current event, then enroll → provision the new one with [cutoff]. The
     * compact switch dialog carries no direction/album picker, so the caller supplies [Direction.Both]
     * and album-off.
     */
    fun onConfirmSwitch(cutoff: String, until: String, direction: Direction) =
        intent { commit(withLeave = true, cutoff = cutoff, until = until, direction = direction, saveToAlbum = false) }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin(cutoff: String, until: String, direction: Direction, saveToAlbum: Boolean) =
        intent {
            commit(withLeave = false, cutoff = cutoff, until = until, direction = direction, saveToAlbum = saveToAlbum)
        }

    /**
     * The photo-access explainer was acknowledged ("I understand") — the **only** way the join gate
     * reaches the system permission dialog (capability `join-event`: CTA-only priming; no phase
     * auto-requests).
     *
     * Requests permission and advances to the confirm phase in one action. It does **not** await the
     * outcome: `request()` returns nothing and cannot suspend (capability `permission-gate`) — the grant
     * arrives only via `PhotoAccessStatusSource` — so the phase advances immediately and the system dialog
     * lands modally over the confirm surface, with the cutoff row already behind it. A no-op on any other
     * phase.
     */
    fun onAcknowledgeAccess() = intent {
        val p = pending.state.value ?: return@intent
        val ph = p.phase as? JoinPhase.ExplainAccess ?: return@intent
        commands.requestAccess()
        pending.set(p.copy(phase = JoinPhase.Ready(ph.name, ph.startsAt, ph.endsAt)))
    }

    /** Discard the pending join/switch, returning to the base screen. */
    fun onCancelJoin() = intent { pending.set(null) }

    /** Discard the pending switch, staying in the current event. */
    fun onCancelSwitch() = intent { pending.set(null) }

    /**
     * A create just minted [eventId] (capability `event-creation-ui`): route it into the **same**
     * pending-join gate a scanned QR uses — non-auto-confirmed — so the creator loads the event, picks a
     * capture-date cutoff, and confirms like any joiner. The `POST /events` already minted the event, so
     * the gate holds a real `eventId` and performs a real details load; provision happens on confirm.
     */
    fun onEventCreated(eventId: String) = intent { startPending(eventId) }

    // The gate's async work runs INLINE within the orbit intent (not on a side scope) so each pending
    // transition reduces through the container's own pipeline deterministically. A modal join is fine
    // to serialize; a real fetch suspends here, yielding a Loading frame before the result.
    private suspend fun startPending(eventId: String) {
        pending.set(PendingJoin(eventId, JoinPhase.Loading))
        loadInto(eventId)
    }

    /**
     * The event's `createdAt` **normalized** into a cutoff, falling back to **now** (capability
     * `photo-selection-policy`). A membership always carries a cutoff, so a missing or unparseable
     * `createdAt` — a malformed event marker — must not leave the join surface with an empty cutoff row
     * and an enabled confirm, which would join at whole-library scope and upload the whole camera roll to
     * the event.
     *
     * **Normalized, not verbatim.** The backend mints `createdAt` with `new Date().toISOString()`, which
     * always carries **milliseconds** (`2026-07-09T19:24:17.182Z`). The cutoff invariant is second
     * precision (`yyyy-MM-dd'T'HH:mm:ss'Z'`), and a fractional-second cutoff breaks the iOS walk's
     * `NSISO8601DateFormatter` (whose default options omit `.withFractionalSeconds`), silently costing the
     * bounded fetch. Round-tripping through the formatter both validates and truncates to the invariant.
     * Truncation drops sub-second precision *downward*, so the cutoff moves marginally earlier — the
     * inclusive direction, which is the safe one.
     *
     * Erring toward `now` shares too few photos, which the user can fix by re-joining with an earlier
     * date; erring toward whole-library cannot be undone.
     */
    private suspend fun loadInto(eventId: String) {
        val load = loadJoinDetails(eventId)
        // The headless negative oracle (mirrors autoConfirm's abort line): a gate parked on a failed
        // details load shows a dialog, but a `SNAPSYNC_EVENT_LINK` launch has no one watching the
        // screen — without this line, `debug.log` shows only the HTTP `404` and the run reads as if
        // the link applied (the documented invented-UUID trap).
        if (load !is JoinLoad.Found) log("join gate: details load did not succeed for $eventId ($load)")
        val phase = when (load) {
            // No seed-from-createdAt and no fallback-to-now any more: `startsAt` is ALWAYS present on a
            // successful load (the backend synthesizes one for legacy markers, and the details source
            // fails the load rather than invent one), so the default is simply the event's start. The
            // photo-access explainer still gates the Ready phase on a first join.
            is JoinLoad.Found -> readyOrExplain(load.name, load.startsAt, load.endsAt)
            JoinLoad.NotFound -> JoinPhase.NotFound
            JoinLoad.Failed -> JoinPhase.LoadFailed
        }
        // Only apply if this fetch is still the active pending target (not cancelled/superseded).
        pending.state.value?.let { if (it.eventId == eventId) pending.set(it.copy(phase = phase)) }
    }

    /**
     * The loaded-details phase: the confirm surface, or the **photo-access explainer** ahead of it
     * (capability `join-event`). The explainer is entered on exactly two conditions, read as a snapshot
     * here and never re-derived:
     *
     * - **a first join** — `config == null`. A *switch* (config present) is confirmed over the joined
     *   layer as a compact dialog, which is no place for an explanation, and anyone switching is already
     *   sitting on the joined layer's `NeedsAccess` affordance. This is also what makes
     *   `JoinPhase.ExplainAccess` unreachable from the switch surface.
     * - **permission never asked** — `NOT_DETERMINED`, the only state from which iOS will still raise the
     *   dialog. From `DENIED` a request is a silent no-op, so explaining and then producing no dialog
     *   would be a lie; `DENIED` goes straight to the confirm and meets the Settings affordance after
     *   joining. `GRANTED` needs no explanation.
     */
    private fun readyOrExplain(name: String, startsAt: String, endsAt: String): JoinPhase {
        val firstJoin = config.value == null
        val neverAsked = permission.value == PermissionStatus.NOT_DETERMINED
        return if (firstJoin && neverAsked) {
            JoinPhase.ExplainAccess(name, startsAt, endsAt)
        } else {
            JoinPhase.Ready(name, startsAt, endsAt)
        }
    }

    private suspend fun commit(
        withLeave: Boolean,
        cutoff: String,
        until: String,
        direction: Direction,
        saveToAlbum: Boolean,
    ) {
        val p = pending.state.value ?: return
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action. Both carry a non-null name, startsAt
        // AND endsAt — so a commit can never reach `JoinEvent` without the floor and ceiling.
        val (name, startsAt, endsAt) = when (val ph = p.phase) {
            is JoinPhase.Ready -> Triple(ph.name, ph.startsAt, ph.endsAt)
            is JoinPhase.CommitFailed -> Triple(ph.name, ph.startsAt, ph.endsAt)
            else -> return
        }
        pending.set(p.copy(phase = JoinPhase.Committing(name, startsAt, endsAt)))
        if (withLeave) commands.leave()
        if (commands.commitJoin(p.eventId, name, startsAt, endsAt, cutoff, until, direction, saveToAlbum)) {
            // Success: config flips present via ConfigSource → reduces to Joined; drop the overlay.
            if (pending.state.value?.eventId == p.eventId) pending.set(null)
        } else if (pending.state.value?.eventId == p.eventId) {
            pending.set(p.copy(phase = JoinPhase.CommitFailed(name, startsAt, endsAt)))
        }
    }

    /**
     * The dev/headless auto-confirm path (`autoJoin=true`): run the same gate — fetch details, leave a
     * different current event first — but auto-fire the confirm on a successful load. No UI, so a load
     * or commit failure aborts and logs rather than parking on a retryable state.
     */
    private suspend fun autoConfirm(
        eventId: String,
        explicitCutoff: String?,
        explicitUntil: String?,
        explicitDirection: String?,
        explicitSaveToAlbum: Boolean?,
    ) {
        val load = loadJoinDetails(eventId)
        if (load !is JoinLoad.Found) {
            log("autoJoin aborted: details load did not succeed for $eventId ($load)")
            return
        }
        val current = config.value
        if (current != null && current.eventId != eventId) commands.leave()
        // The auto-fired confirm uses the event's `startsAt` as the cutoff, unless the event link supplied
        // an explicit dev/test one (capability `photo-selection-policy`). Never an absent cutoff — the headless
        // path has no surface to notice one.
        //
        // An explicit cutoff is passed through RAW and clamped on the far side of `commitJoin`, inside
        // `JoinEvent` — it gets no exemption from the floor, and that is the whole point. `minPhotoDate`
        // is decoded from ANY event link, so an unclamped override would let a hostile QR carrying
        // `autoJoin=true` + a distant-past cutoff auto-confirm a join at near-whole-library scope WITHOUT
        // A TAP. (Cost, accepted: the dev loop can no longer force a cutoff below the event's start — it
        // creates the event with an early `startsAt` instead, which the unbounded picker permits.)
        val cutoff = explicitCutoff ?: load.startsAt
        // The upper bound defaults to the event's `endsAt` (the full window), unless the event link supplied
        // an explicit dev/test override. Like the cutoff, an explicit `maxPhotoDate` is passed RAW and
        // clamped to the ceiling on the far side, inside `JoinEvent`.
        val until = explicitUntil ?: load.endsAt
        // The direction defaults to Both, unless the event link supplied an explicit dev/test override
        // (`both`/`upload`/`download`); an unrecognized token was already rejected by the decoder.
        val direction = explicitDirection?.let(Direction::fromWire) ?: Direction.Both
        // The album choice defaults to off, unless the event link supplied an explicit dev/test override
        // (capability `event-album`).
        val saveToAlbum = explicitSaveToAlbum ?: false
        if (!commands.commitJoin(eventId, load.name, load.startsAt, load.endsAt, cutoff, until, direction, saveToAlbum)) {
            log("autoJoin aborted: enrollment failed for $eventId")
        }
    }
}

/**
 * How often the not-started clock line re-checks the wall clock (capability `sync-status-screen`). One
 * minute: the line names a start time to the minute, so a finer tick would buy nothing visible, and
 * nothing of the member's can upload before the start regardless.
 */
private const val NOT_STARTED_TICK_MILLIS = 60_000L

/** How long the transient invalid-link error stays on screen after it last appeared. */
private const val TRANSIENT_ERROR_MILLIS = 4_000L

/** The transient invalid-link copy (the screen renders [StatusContainerHost.transientError] verbatim). */
private const val INVALID_LINK_MESSAGE = "That QR code wasn't valid."

/** The loaded/committing name carried by a phase, if any (for re-issuing the commit on confirm/retry). */
private fun JoinPhase.name(): String? = when (this) {
    is JoinPhase.ExplainAccess -> name
    is JoinPhase.Ready -> name
    is JoinPhase.Committing -> name
    is JoinPhase.CommitFailed -> name
    JoinPhase.Loading, JoinPhase.NotFound, JoinPhase.LoadFailed -> null
}

// Config presence is the top rung: without a connected event there is nothing to share, so the create
// layer replaces everything regardless of permission or snapshot. Once config is present the screen is
// ALWAYS the joined layer (name · QR · share · leave) — permission and sync activity are moods of the
// one-line status, never a hero-replacing gate. There is no join-status rung: reconciliation runs in
// the extension and status is read from the completeness listing.
private fun reduceFrom(
    config: EventConfig?,
    permission: PermissionStatus,
    snapshot: SyncStatus,
    creation: CreationStatus,
    download: DownloadProgress,
    pending: PendingJoin?,
    nowCutoff: String,
    attested: Boolean,
): UiState {
    if (config == null) {
        // A pending interactive join outranks the create layer (a switch whose leave already ran also
        // lands here — a transient no-event, shown full-screen with a Retry).
        if (pending != null) return UiState.JoiningEvent(pending.eventId, pending.phase)
        return when (creation) {
            CreationStatus.InFlight -> UiState.CreatingEvent
            is CreationStatus.Failed -> UiState.CreateEvent(error = creation.reason.message())
            CreationStatus.Idle -> UiState.CreateEvent()
        }
    }
    val health = when {
        // Missing permission is the sole attention state — the only reason contribution cannot run. It
        // outranks NotStarted because it is the only ACTIONABLE state, and the member must resolve it
        // BEFORE the event begins or they miss the start; hiding it behind the clock line would ambush
        // them with a permission prompt at the very moment the party starts. LIMITED is NOT here: a
        // partial grant is a working state (capability `limited-photo-access`) — it falls through to
        // the snapshot-derived health exactly like GRANTED.
        !permission.grantsPhotoAccess -> SyncHealth.NeedsAccess(permission)
        // The event has not begun. Outranks every snapshot-derived value because nothing of this member's
        // CAN be syncing yet — the cutoff floor guarantees it (`minPhotoDate >= startsAt > now`, and a
        // photo cannot be captured in the future) — so a snapshot line would say nothing true that this
        // does not say better. Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
        config.startsAt > nowCutoff -> SyncHealth.NotStarted(config.startsAt)
        // Uploads are gated on an attestation token, and we could not get one. Ranked BELOW permission and
        // BELOW NotStarted for the same reason: with no library access — or before the event begins —
        // nothing of this member's can upload anyway, so an unusable token is not yet their problem, and
        // two attention lines would only compete. Ranked ABOVE the sync progress, because "Syncing" would
        // be a lie: nothing can upload at all.
        !attested -> SyncHealth.Unattested
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        snapshot is SyncStatus.Loading -> SyncHealth.Loading
        snapshot is SyncStatus.Ready -> syncHealth(snapshot.progress, download)
        else -> SyncHealth.Loading
    }
    // A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
    val pendingSwitch = pending?.let { PendingSwitch(it.eventId, it.phase) }
    // The event's declared end has passed: an "Event ended" marker prefixing the health line (capability
    // `sync-status-screen`). Informational only — the health above is unchanged and sync continues in the
    // backend grace window. `null` endsAt (a legacy config before its reconcile backfill) shows no marker.
    // Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
    val ended = config.endsAt?.let { it < nowCutoff } ?: false
    return UiState.Joined(
        health,
        pendingSwitch,
        // The resting affordance, not an attention state (capability `limited-photo-access`): a
        // partial grant's joined layer always offers the picker, whatever the health.
        canChoosePhotos = permission == PermissionStatus.LIMITED,
        ended = ended,
    )
}

// Shown tracks completeness (never lies about "everything up/received"); pulse tracks live activity
// (never fakes motion). Each arrow derives from ITS OWN COUNTS ALONE — this function does not read the
// membership's direction, and never force-hides.
//
// It used to. An opted-out arm was masked here, and `InSync` collapsed over the "enabled" directions. That
// is no longer needed, because an opted-out direction now contributes no work and so has a zero total: the
// upload total is 0 for a non-contributing membership (capability `photo-selection-policy`), and the
// download total is 0 for a membership that never reconciles (capability `photo-download`, whose total is
// populated only by that reconcile). The arrows agree with the direction because the counts already do.
//
// The mask is not merely redundant now — it was actively harmful, and removing it is the point. A
// force-hidden arrow can only ever conceal a MISMATCH between the direction contract and what the system is
// actually doing. Concealing that mismatch is exactly how a download-only membership uploaded its member's
// camera roll for a full release cycle while this screen read "In sync" (capability `upload-lifecycle`): the
// one surface that would have shown them an upload they never asked for was the surface that hid it. If the
// counts are right, the arrow is already right; if they are wrong, an arrow the member never asked for is
// the only signal anyone gets. The display must not assert a contract the system is not keeping.
private fun syncHealth(progress: SyncProgress, download: DownloadProgress): SyncHealth {
    val upload = arrowOf(shown = progress.synced < progress.total, pulsing = progress.pending > 0)
    val downloadArrow = arrowOf(shown = download.downloaded < download.total, pulsing = download.inFlight > 0)
    return if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) {
        SyncHealth.InSync
    } else {
        SyncHealth.Syncing(upload = upload, download = downloadArrow)
    }
}

private fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
    if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC

// Derive the invite link from the persisted config's eventId (the wire payload is eventId-only).
private fun EventConfig.inviteUrl(): String = encodeEventUrl(EventLinkPayload(eventId))

// The inline create-error copy, formatted in presentation (UiState carries final display strings).
private fun CreationFailureReason.message(): String = when (this) {
    // The client already blocks the two knowable rules — empty (Create is disabled until the trimmed name
    // is non-empty) and over-length (the field caps at 100). So a returned 400 is a rule this client can't
    // name; the copy says what to try rather than asserting a constraint it doesn't know.
    CreationFailureReason.INVALID_NAME -> "That name wasn't accepted. Try a different one."
    CreationFailureReason.SERVER -> "Couldn't reach the server."
}
