package app.snapsync.presentation

import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.DeletesAt
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
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
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.membership.RenameStatus
import app.snapsync.feature.membership.RenameStatusSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.feature.download.DownloadProgress
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.download.InMemoryDownloadStatusSource
import app.snapsync.model.SyncStatus
import app.snapsync.model.SyncProgress
import app.snapsync.feature.status.SyncStatusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
    // The rename-status read-model (capability `event-rename`), the create twin. Same inert default for
    // the same reason. It is exposed as a SCREEN-LEVEL value below — never folded into `UiState` — so
    // the reduction gains no branch for a dialog's in-flight and failure states.
    renameStatusSource: RenameStatusSource = MutableRenameStatusSource(),
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
    // The container's ERROR seam (spec `sync-status-screen`, "A failing command never disables the status
    // container"): every throwable that escapes an intent arrives here instead of propagating.
    //
    // It is separate from [log] rather than folded into it because the two carry different severities and
    // [log] is bound to an INFO logger for the dev-path autoJoin abort lines. This one must reach the crash
    // reporter (capability `crash-reporting`, `Error` and above become events), so the composition binds it
    // to `log.e`. Keeping severity out of this module's vocabulary is the other half: presentation names a
    // need, not a level.
    //
    // No-op by default, so the harnesses and every existing test construct unchanged — but note that the
    // DEFAULT still keeps the container alive, because it is the handler's PRESENCE that stops the rethrow.
    // A host binding nothing loses the report, never the liveness.
    private val onIntentError: (Throwable) -> Unit = {},
    // Download progress for the joined-layer "downloaded X of Y" line (capability `photo-download`).
    // Exposed as a screen-level StateFlow (like `inviteUrl`), NOT folded into `UiState` — it's an
    // independent indicator that doesn't gate upload classification. Defaults to inert (always 0 of 0)
    // so non-iOS hosts/tests construct unchanged; iOS injects the store-backed source.
    downloadSource: DownloadStatusSource = InMemoryDownloadStatusSource(),
    // Attestation health (capability `device-attestation`): the trust feature's own read-model, false
    // only when this device's token is UNUSABLE (absent, unreadable, or expired) and the refresh could
    // not obtain one. Never false for a token that is merely due for renewal — that one still authorizes
    // every upload, and saying otherwise told a member sharing was paused with six days of token left
    // (`SNAPSYNC-20`). A bare StateFlow, like every other read-model here: the feature that owns the
    // fact publishes it, and it also owns the rule that a verdict never outlives the refresh that
    // produced it, so nothing downstream has to reason about how old this value is. Defaults to
    // always-true so non-iOS hosts and every existing test construct unchanged.
    attested: StateFlow<Boolean> = MutableStateFlow(true),
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
    private val nowTick: Flow<CaptureDate> =
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
                        val startPassed = now >= startsAt.at
                        val endPassed = endsAt == null || now >= endsAt.at
                        if (startPassed && endPassed) return@flow
                        delay(NOT_STARTED_TICK_MILLIS)
                    }
                }
            }

    override val container: Container<UiState, Nothing> =
        scope.container(
            // All seams hold their current truth synchronously, so the first state the screen can ever
            // render derives from real values — never a guess or a placeholder.
            initialState = reduceFrom(
                config.value,
                permission.value,
                syncSource.status.value,
                creationStatusSource.creationStatus.value,
                downloadSource.progress.value,
                pending.state.value,
                cutoffFormatter.nowCutoff(),
                attested.value,
            ),
            // The container SURVIVES a throwing intent (spec `sync-status-screen`) — and this handler is the
            // whole of what makes it so. It is not a logging convenience: Orbit runs each intent as
            // `runCatching { … }.exceptionOrNull()?.let { settings.exceptionHandler?.handleException(…) ?: throw it }`,
            // so with NO handler configured it RE-THROWS, which cancels `RealContainer.intentJob` — a plain
            // `Job(parent)`, not a `SupervisorJob` — after which every later `orbit()` call is a child of a
            // cancelled job and silently never runs. Measured on orbit-core 10.0.0: without a handler a
            // second intent issued after a throwing one never lands; with one, it does.
            //
            // What that costs in the field is every user tap, not just the one that failed: leave, share,
            // settings save, rename, join confirm, cancel, create all cross this container, so the screen
            // keeps rendering its last state, looks alive, and answers nothing until the process restarts.
            //
            // Necessity + expiry (law "Necessity claims carry forcing proofs"): the forcing fact is Orbit's
            // own `?: throw` above, which is library behaviour and could change on a version bump — so
            // `StatusContainerHostTest`'s liveness pin asserts a later intent still lands, and a bump that
            // changes the semantics fails the build rather than quietly restoring the dead container.
            buildSettings = {
                exceptionHandler = CoroutineExceptionHandler { _, throwable -> onIntentError(throwable) }
            },
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
                    attested,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    reduceFrom(
                        values[0] as EventConfig?,
                        values[1] as PermissionStatus,
                        values[2] as SyncStatus,
                        values[3] as CreationStatus,
                        values[4] as DownloadProgress,
                        values[5] as PendingJoin?,
                        values[6] as CaptureDate,
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
        intent {
            commands.create(
                name,
                EventStart(cutoffFormatter.toCutoff(startsAt)),
                EventEnd(cutoffFormatter.toCutoff(endsAt)),
            )
        }

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
     * The rename lifecycle (capability `event-rename`) for the heading's rename dialog: in-flight while
     * the request runs, then `Succeeded` (the dialog closes) or `Failed` (it stays open with a banner).
     *
     * A screen-level param like [inviteUrl]/[eventName]/[membership] — it does **not** enter `UiState`,
     * so the reduction gains no branch. The rename changes no layer; it changes one string and a dialog's
     * state, and neither is a status-screen family.
     */
    val renameStatus: StateFlow<RenameStatus> = renameStatusSource.renameStatus

    /**
     * Rename the joined event (capability `event-rename`), confirmed on the heading's rename dialog.
     * Delegates to the injected [UserCommands.rename] with [eventId] — the event the dialog was opened
     * for — so a switch that landed mid-edit makes the use-case a no-op rather than renaming a different
     * event. Fire-and-forget; the outcome arrives via [renameStatus] and the new name via the config
     * read-model. Unlike [onReconfigure], this writes the SHARED event, but it crosses the same one door.
     */
    fun onRenameEvent(eventId: String, name: String) = intent { commands.rename(eventId, name) }

    /** Clear the [renameStatus] latch once the screen has consumed a terminal value. */
    fun onRenameStatusConsumed() = intent { commands.resetRename() }

    /**
     * Send the diagnostic dump (capability `diagnostic-logging`) with the operator's account of the
     * problem — already trimmed and length-bounded by the sheet that collected it — and an opaque label
     * for the surface it was sent from (the screen, which only the screen itself can name). `null` when this
     * build carries no reporting channel — the screen then wires no gesture, so the affordance does not
     * exist rather than existing and doing nothing. Fire-and-forget: `UiState` is unaffected, and no
     * delivery claim is made (the channel may queue and retransmit).
     */
    val onSendDiagnostics: ((String, String) -> Unit)? =
        commands.sendDiagnostics?.let { send -> { note, screen -> intent { send(note, screen) } } }

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
        minPhotoDate: CaptureCutoff,
        maxPhotoDate: CaptureCeiling,
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
                            result.payload.minPhotoDate?.let(::captureCutoff),
                            result.payload.maxPhotoDate?.let(::captureCeiling),
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
    fun onConfirmJoin(cutoff: CaptureCutoff, until: CaptureCeiling, direction: Direction, saveToAlbum: Boolean) =
        intent {
            commit(cutoff = cutoff, until = until, direction = direction, saveToAlbum = saveToAlbum)
        }

    /**
     * Confirm a switch (capability `join-event`): run the **leave and nothing else**, and choose nothing
     * on the member's behalf. The pending join survives; once the leave has cleared the config, the
     * reduction's config-absent rung renders the **regular full-screen join surface** for the new event,
     * where the member picks direction, range and album exactly as on a first join. This is why the
     * confirmation carries no pickers and this intent takes no arguments — the surface that follows owns
     * every choice.
     *
     * The leave rides the same [UserCommands.leave] the joined layer's Leave action uses, so in-flight
     * downloads are cancelled and non-terminal rows pruned before `LeaveEvent` stops the producer and
     * clears the config.
     *
     * The phase is re-derived **after** the leave and only once the config is confirmed gone. `LeaveEvent`
     * is best-effort — a failing `ConfigStore.clear()` is logged and swallowed — and on that path the
     * phase must stay put so the confirmation re-renders and the member can simply confirm again.
     * Deriving *before* the leave would avoid a possible one-frame Ready render, but a failed clear would
     * then leave `Joined(pendingSwitch = ExplainAccess)`, whose dialog branch renders nothing: the
     * confirmation would vanish with an invisible pending join behind it.
     */
    fun onConfirmSwitch() = intent {
        val p = pending.state.value ?: return@intent
        val ph = p.phase as? JoinPhase.Ready ?: return@intent
        commands.leave()
        if (config.value == null) {
            pending.set(p.copy(phase = deriveLoadedPhase(ph.name, ph.startsAt, ph.endsAt, ph.deletesAt)))
        }
    }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin(cutoff: CaptureCutoff, until: CaptureCeiling, direction: Direction, saveToAlbum: Boolean) =
        intent {
            commit(cutoff = cutoff, until = until, direction = direction, saveToAlbum = saveToAlbum)
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
        pending.set(p.copy(phase = JoinPhase.Ready(ph.name, ph.startsAt, ph.endsAt, ph.deletesAt)))
    }

    /**
     * Discard the pending join, returning to the base screen — the create layer when no event is
     * configured. Reached through a **switch**, the leave has already run, so this lands the device in
     * **no event**; the confirmation named the event being left, and rescanning an invite rejoins.
     */
    fun onCancelJoin() = intent { pending.set(null) }

    /**
     * Dismiss the switch confirmation *before* its leave, staying in the current event untouched. The
     * same one-line body as [onCancelJoin], deliberately kept distinct: after this change the two are
     * genuinely different acts — this one keeps the membership, that one ends with none.
     */
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
        val load = try {
            loadJoinDetails(eventId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // The `Loading` twin of `commit()`'s repair (capability `join-event`): `Loading` pins no action
            // either, so a throw here would park a full-screen spinner with no Cancel — or, while joined, an
            // invisible pending join — until the process restarts. `LoadFailed` is exactly where a details
            // source REPORTING a transient failure lands, so a throwing source and a reporting one converge
            // on the same retryable surface.
            //
            // ⚠️ Defence in depth: UNREACHABLE through the production binding today, and knowingly kept.
            // `HttpEventDirectory.fetch` is `runCatching { … }.getOrDefault(EventDetails.Failed)` and
            // `toJoinLoad` is pure, so the bound lambda cannot throw — but `loadJoinDetails` is an injected
            // `suspend (String) -> JoinLoad` and nothing here can know that. It stays because the invariant
            // is one adapter change away from being false, and the cost of it being false is a screen no
            // one can leave. Unlike `BackgroundUploadPump`'s comparable wrapper, this one IS covered: the
            // seam is a constructor parameter, so a test injects a throwing loader directly.
            if (pending.state.value?.eventId == eventId) {
                pending.state.value?.let { pending.set(it.copy(phase = JoinPhase.LoadFailed)) }
            }
            throw t
        }
        // The headless negative oracle (mirrors autoConfirm's abort line): a gate parked on a failed
        // details load shows a dialog, but a `SNAPSYNC_EVENT_LINK` launch has no one watching the
        // screen — without this line, `debug.log` shows only the HTTP `404` and the run reads as if
        // the link applied (the documented invented-UUID trap).
        if (load !is JoinLoad.Found) log("join gate: details load did not succeed for $eventId ($load)")
        val phase = when (load) {
            // No seed-from-createdAt and no fallback-to-now any more: `startsAt` is ALWAYS present on a
            // successful load (the backend synthesizes one for legacy markers, and the details source
            // fails the load rather than invent one), so the default is simply the event's start. The
            // first of the derivation's two points (the other is `onConfirmSwitch`, after the leave).
            is JoinLoad.Found -> deriveLoadedPhase(load.name, load.startsAt, load.endsAt, load.deletesAt)
            JoinLoad.NotFound -> JoinPhase.NotFound
            JoinLoad.Failed -> JoinPhase.LoadFailed
        }
        // Only apply if this fetch is still the active pending target (not cancelled/superseded).
        pending.state.value?.let { if (it.eventId == eventId) pending.set(it.copy(phase = phase)) }
    }

    /**
     * The gate's **loaded-phase derivation** (capability `join-event`): the single rule deciding, for
     * loaded event details, whether the gate presents the confirm surface or the **photo-access
     * explainer** ahead of it. The explainer is chosen on exactly two conditions:
     *
     * - **no event configured** — `config == null`. While a *switch*'s previous event is still
     *   configured this yields the confirm phase, which is what the switch confirmation renders over the
     *   joined layer; the explainer comes later, if at all.
     * - **permission never asked** — `NOT_DETERMINED`, the only state from which iOS will still raise the
     *   dialog. From `DENIED` a request is a silent no-op, so explaining and then producing no dialog
     *   would be a lie; `DENIED` goes straight to the confirm and meets the Settings affordance after
     *   joining. `GRANTED` needs no explanation.
     *
     * It runs at **every** point the gate resolves to a loaded phase, so no entry path can reach the
     * confirm surface without having been offered the explainer. There are two such points: [loadInto]
     * when the details fetch resolves, and [onConfirmSwitch] once a switch's leave has cleared the config
     * — the second re-deriving from the details the first already loaded, never re-fetching them. That is
     * why permission is a **snapshot at the moment the phase is chosen** rather than an observation: the
     * phase advances only by user action, so a permission change while the explainer is on screen does
     * not move it.
     *
     * For every permission except `NOT_DETERMINED` the second derivation is a no-op — `GRANTED`,
     * `LIMITED` and `DENIED` all yield [JoinPhase.Ready] at both points.
     */
    private fun deriveLoadedPhase(
        name: String,
        startsAt: EventStart,
        endsAt: EventEnd,
        deletesAt: DeletesAt,
    ): JoinPhase {
        val noEventConfigured = config.value == null
        val neverAsked = permission.value == PermissionStatus.NOT_DETERMINED
        return if (noEventConfigured && neverAsked) {
            JoinPhase.ExplainAccess(name, startsAt, endsAt, deletesAt)
        } else {
            JoinPhase.Ready(name, startsAt, endsAt, deletesAt)
        }
    }

    /** The four facts a loaded details response supplies, carried together through the commit path. */
    private data class LoadedEvent(
        val name: String,
        val startsAt: EventStart,
        val endsAt: EventEnd,
        val deletesAt: DeletesAt,
    )

    private suspend fun commit(
        cutoff: CaptureCutoff,
        until: CaptureCeiling,
        direction: Direction,
        saveToAlbum: Boolean,
    ) {
        val p = pending.state.value ?: return
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action. Both carry a non-null name, startsAt,
        // endsAt AND deletesAt — so a commit can never reach `JoinEvent` without the floor, the ceiling,
        // and the retention deadline.
        val loaded = when (val ph = p.phase) {
            is JoinPhase.Ready -> LoadedEvent(ph.name, ph.startsAt, ph.endsAt, ph.deletesAt)
            is JoinPhase.CommitFailed -> LoadedEvent(ph.name, ph.startsAt, ph.endsAt, ph.deletesAt)
            else -> return
        }
        val (name, startsAt, endsAt, deletesAt) = loaded
        pending.set(p.copy(phase = JoinPhase.Committing(name, startsAt, endsAt, deletesAt)))
        val committed = try {
            commands.commitJoin(
                p.eventId, name, startsAt, endsAt, deletesAt, cutoff, until, direction, saveToAlbum,
            )
        } catch (cancelled: CancellationException) {
            // Cancellation is teardown, not failure: rethrow before the repair below, or a cancelled
            // commit would rewrite the phase on its way out (the `LedgerCountsPoller` shape).
            throw cancelled
        } catch (t: Throwable) {
            // `Committing` pins NO action (`JoiningEventScreen`: "In-flight phases offer no actions"), so a
            // throw here would otherwise park the gate on a dead-end spinner for the life of the process —
            // capability `join-event`, "The join gate never rests in a phase that offers no action".
            //
            // Which phase is right depends on whether the membership was PERSISTED, because `flow/Provision`
            // saves the config at step 2 of 6 and everything after it is follow-up the next foreground
            // repeats. So the config decides, and it is the only thing that can:
            //  · it names this event  → the join LANDED; drop the pending join, and the joined screen is the
            //    truth. Retrying would hit `JoinEvent`'s `AlreadyJoined` no-op anyway.
            //  · otherwise            → it never landed; `CommitFailed` pins the Retry that re-runs it.
            if (pending.state.value?.eventId == p.eventId) {
                if (config.value?.eventId == p.eventId) {
                    pending.set(null)
                } else {
                    pending.set(p.copy(phase = JoinPhase.CommitFailed(name, startsAt, endsAt, deletesAt)))
                }
            }
            // Rethrown, never swallowed: the container's `exceptionHandler` reports it at `Error`, which is
            // what reaches the crash reporter. That handler is also why rethrowing is safe here — without it
            // Orbit's re-throw would cancel the intent job and take every later command with it.
            throw t
        }
        if (committed) {
            // Success: config flips present via ConfigSource → reduces to Joined; drop the overlay.
            if (pending.state.value?.eventId == p.eventId) pending.set(null)
        } else if (pending.state.value?.eventId == p.eventId) {
            pending.set(p.copy(phase = JoinPhase.CommitFailed(name, startsAt, endsAt, deletesAt)))
        }
    }

    /**
     * The dev/headless auto-confirm path (`autoJoin=true`): run the same gate — fetch details, leave a
     * different current event first — but auto-fire the confirm on a successful load. No UI, so a load
     * or commit failure aborts and logs rather than parking on a retryable state.
     */
    private suspend fun autoConfirm(
        eventId: String,
        explicitCutoff: CaptureCutoff?,
        explicitUntil: CaptureCeiling?,
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
        val cutoff = explicitCutoff ?: CaptureCutoff(load.startsAt.at)
        // The upper bound defaults to the event's `endsAt` (the full window), unless the event link supplied
        // an explicit dev/test override. Like the cutoff, an explicit `maxPhotoDate` is passed RAW and
        // clamped to the ceiling on the far side, inside `JoinEvent`.
        val until = explicitUntil ?: CaptureCeiling(load.endsAt.at)
        // The direction defaults to Both, unless the event link supplied an explicit dev/test override
        // (`both`/`upload`/`download`); an unrecognized token was already rejected by the decoder.
        val direction = explicitDirection?.let(Direction::fromWire) ?: Direction.Both
        // The album choice defaults to off, unless the event link supplied an explicit dev/test override
        // (capability `event-album`).
        val saveToAlbum = explicitSaveToAlbum ?: false
        if (!commands.commitJoin(
                eventId, load.name, load.startsAt, load.endsAt, load.deletesAt, cutoff, until, direction,
                saveToAlbum,
            )
        ) {
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
    nowCutoff: CaptureDate,
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
        config.startsAt.at > nowCutoff -> SyncHealth.NotStarted(config.startsAt)
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
    val ended = config.endsAt?.let { it.at < nowCutoff } ?: false
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
