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
import app.snapsync.feature.version.AppVersionGate
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinCommit
import app.snapsync.model.FromChoice
import app.snapsync.model.UntilChoice
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
import app.snapsync.feature.membership.RenameFailureReason
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
    // Every read-model this container reduces over (see [StatusSources]). Bundled because they are one
    // KIND of thing — values observed and folded into `UiState` — while what the container INVOKES
    // ([commands], [loadJoinDetails]) and what it EMITS ([StatusDiagnostics]) stay separate.
    sources: StatusSources,
    private val scope: CoroutineScope,
    // Supplies "now" as a cutoff string and converts a local pick (capability `photo-selection-policy`).
    // Injected — with NO default (migration step 9): a default would have to read the system clock
    // here, which is exactly the through-ports law violation this parameter repays. Production wires
    // the `Clock`/`TimeZoneSource` ports; tests pass a fixed instant and zone.
    private val cutoffFormatter: CutoffFormatter,
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
    // The two out-channels (see [StatusDiagnostics]): the dev-path log and the intent-error seam.
    diagnostics: StatusDiagnostics = StatusDiagnostics(),
) : ContainerHost<UiState, Nothing> {

    // The bundles are unpacked into the names the body already uses. Grouping happens at the boundary,
    // where a caller has to read it; inside, each source keeps the name that says what it is.
    private val syncSource = sources.sync
    private val permission = sources.permission
    private val config = sources.config
    private val creationStatusSource = sources.creation
    private val renameStatusSource = sources.rename
    private val downloadSource = sources.download
    private val attested = sources.attested
    private val pending = sources.pending
    private val versionRefusal = sources.versionRefusal
    private val appStoreUrl = sources.appStoreUrl

    private val log = diagnostics.log
    private val onIntentError = diagnostics.onIntentError

    // Declared here rather than beside their use because `container`'s initializer reduces over them:
    // a property initialized later is null at that moment.
    private val transientErrorState = MutableStateFlow<String?>(null)
    private var transientErrorClear: Job? = null
    private val renameFlow: StateFlow<RenameStatus> = renameStatusSource.renameStatus

    // What is drawn OVER the current layer (see [Overlays]). Presentation-owned like the transient error:
    // opening a confirmation touches no port and calls no command, so these are container-local intents
    // that reduce and nothing more. They are state rather than screen-local `remember`s because the
    // screen SHOWS them (capability `sync-status-screen`).
    private val overlaysState = MutableStateFlow(Overlays())

    // The member's uncommitted choices. ONE cell, because the two surfaces that ask for them are mutually
    // exclusive by construction — the join gate needs config ABSENT, the settings surface needs it
    // PRESENT — and each open re-seeds, so nothing can leak from one surface into the other.
    private val formState = MutableStateFlow(RangeForm())

    // Whether the joined layer is showing its settings surface. A flag rather than a `UiState` family, for
    // the reason `reconfigure-membership` D4 gives: opening is client-side navigation that touches no port.
    private val reconfiguringState = MutableStateFlow(false)

    /**
     * Resolve a form against an event window, in the DEVICE's zone.
     *
     * The join gate and the settings surface reach a window by different roads — one off the loaded
     * phase, one off the persisted membership — and those roads genuinely differ. From here down the
     * rules are identical, and a clamping rule that held on one surface and not the other would be a bug
     * nobody would ever see.
     */
    private fun resolveRange(
        form: RangeForm,
        startsAt: EventStart,
        endsAt: EventEnd?,
        ownCeiling: CaptureCeiling?,
        deletesAt: DeletesAt? = null,
    ): ResolvedRange {
        val windowStart = cutoffFormatter.toLocal(startsAt.at) ?: cutoffFormatter.nowLocal()
        // A membership always carries its own ceiling; the join gate falls back to a far-future sentinel,
        // the widest safe reading, since the bounds only ever narrow from here.
        val upper = endsAt?.at ?: ownCeiling?.at
        val windowEnd = upper?.let { cutoffFormatter.toLocal(it) }
            ?: LocalDateTime(windowStart.year + NO_CEILING_YEARS, 1, 1, 0, 0)
        return form.resolve(
            windowStart = windowStart,
            windowEnd = windowEnd,
            nowLocal = cutoffFormatter.nowLocal(),
            nowAvailable = nowWithinWindow(cutoffFormatter.nowCutoff(), startsAt.at, endsAt?.at),
            toCutoff = cutoffFormatter::toCutoff,
            deletesLocal = deletesAt?.let { cutoffFormatter.toLocal(it.at) },
        )
    }


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
                renameFlow.value,
                transientErrorState.value,
                formState.value,
                reconfiguringState.value,
                updateLayerFor(versionRefusal.value, appStoreUrl),
                ::resolveRange,
            ).let { layer -> UiState(layer, overlaysState.value.maskedFor(layer)) },
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
                    renameFlow,
                    transientErrorState,
                    overlaysState,
                    formState,
                    reconfiguringState,
                    versionRefusal,
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
                        values[8] as RenameStatus,
                        values[9] as String?,
                        values[11] as RangeForm,
                        values[12] as Boolean,
                        updateLayerFor(values[13] as AppVersionGate.Refusal?, appStoreUrl),
                        ::resolveRange,
                    ).let { layer -> UiState(layer, (values[10] as Overlays).maskedFor(layer)) }
                }
                    .collect { ui -> reduce { ui } }
            }
        }

    /**
     * Flash the transient invalid-link error (capability `event-link`): a link arrived that the decoder
     * rejected, so the create layer shows a self-clearing message on its ONE inline error line without
     * touching persisted state.
     *
     * The value is an INPUT to the reduction — it reaches the screen inside `Layer.CreateEvent.error`,
     * coalesced with a sticky create failure — but the set-then-clear choreography lives HERE, in
     * presentation (spec `module-architecture`, "Commands cross one door": multi-step interactions are
     * presentation-owned, and interaction state dies with the UI). It replaced a one-shot side-effect
     * channel at the migration finale, whose single consumer was the untested iOS shell.
     *
     * A rejected link while the message is already showing re-arms the full window (the timer restarts)
     * — the deliberate reading of "self-clearing a few seconds after it LAST appeared".
     */
    private fun showTransientError() {
        transientErrorState.value = INVALID_LINK_MESSAGE
        transientErrorClear?.cancel()
        transientErrorClear = scope.launch {
            delay(TRANSIENT_ERROR_MILLIS)
            transientErrorState.value = null
        }
    }

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

    /**
     * The three photo-access taps (capability `permission-gate`, `limited-photo-access`), grouped —
     * they are one question the screen asks in three forms, and the screen's own `AccessActions` bundle
     * already mirrors this grouping.
     */
    val access: AccessCommands = AccessCommands()

    inner class AccessCommands internal constructor() {
        fun onRequestPermission() = intent { commands.requestAccess() }

        /**
         * The joined layer's "Choose more photos" tap (capability `limited-photo-access`) — presents the
         * platform's limited-library picker; the selection outcome arrives via the selection seam.
         */
        fun onChoosePhotos() = intent { commands.choosePhotos() }

        fun onOpenSettings() = intent { commands.openSettings() }
    }

    /**
     * Leave the configured event (confirmed in the UI before this fires). Delegates to the injected
     * leave action, which disables the producer and clears the persisted config (the extension resets
     * its own private ledger on its next cycle). The config going `null` makes the reduction fall back
     * to the setup gate — no new `UiState` and no reduction branch here.
     */
    fun onLeaveEvent() = intent {
        // Every overlay belongs to the membership being left, so none of them survives it. Resetting the
        // CELL (rather than only hiding them) is what stops a later rejoin from reopening a dialog the
        // member dismissed by leaving.
        closeOverlays()
        commands.leave()
    }

    /**
     * Share the event's invite link (the joined-layer share action). Hands the current invite URL
     * to the injected platform share; fire-and-forget — no result is observed, and `UiState` is
     * unaffected (the system share UI is presented over the screen, not part of it). Inert when no
     * event is configured (no URL) or no real share is bound (the no-op default).
     */
    // The invite URL is read off the state the reduction already derived, so the shared link is
    // byte-identical to the QR being rendered rather than a second derivation that could drift.
    fun onShareInvite() = intent { (state.layer as? Layer.Joined)?.let { commands.share(it.inviteUrl) } }

    /**
     * Open the App Store page from the update-required screen (capability `min-app-version`).
     *
     * The URL is read from the CURRENT state rather than taken from the caller, exactly as
     * [onShareInvite] reads the invite URL: the screen's contract is that a build carrying no store URL
     * renders no button, and reading it here means the container cannot be asked to open one the state
     * does not hold.
     */
    fun onOpenAppStore() = intent {
        (state.layer as? Layer.UpdateRequired)?.storeUrl?.let { commands.openLink(it) }
    }

    /**
     * Opening and closing what is drawn over — or instead of — the current layer.
     *
     * Grouped for the same reason [form] is: these are one question ("what is on screen"), and each of
     * them reduces and nothing more. That is the property `reconfigure-membership` D4 asked for — a
     * pure navigation act must not cross a flow command — and it still holds now that the answer is
     * state rather than a screen-held flag.
     */
    val surfaces: SurfaceCommands = SurfaceCommands()

    inner class SurfaceCommands internal constructor() {
        fun onConfirmLeaveOpen() = intent { overlaysState.value = overlaysState.value.copy(confirmingLeave = true) }

        fun onConfirmLeaveDismiss() = intent { overlaysState.value = overlaysState.value.copy(confirmingLeave = false) }

        fun onRenameOpen() = intent { overlaysState.value = overlaysState.value.copy(renaming = true) }

        fun onRenameDismiss() = intent { overlaysState.value = overlaysState.value.copy(renaming = false) }

        fun onReportBugOpen() = intent { overlaysState.value = overlaysState.value.copy(reportingBug = true) }

        fun onReportBugDismiss() = intent { overlaysState.value = overlaysState.value.copy(reportingBug = false) }

        /**
         * Open the settings surface, pre-filled from the persisted membership. Seeding HERE rather than
         * in the reduction is what makes the pre-fill a SNAPSHOT: a foreground refresh landing mid-edit
         * updates the heading, not the controls in the member's hand.
         */
        fun onOpenReconfigure() = intent {
            val config = config.value ?: return@intent
            formState.value = reconfigureForm(config, cutoffFormatter::toLocal)
            reconfiguringState.value = true
        }

        /** Cancel the settings surface: the edits are discarded, and no port was ever touched. */
        fun onCancelReconfigure() = intent { reconfiguringState.value = false }
    }

    /**
     * Close every overlay. Fired when the layer changes out from under them — a leave landing while the
     * rename sheet is open would otherwise leave a dialog over a screen whose event is gone.
     */
    private fun closeOverlays() {
        overlaysState.value = Overlays()
    }

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
    fun onReconfigure() = intent {
        val config = config.value ?: return@intent
        val form = formState.value
        val range = resolveRange(form, config.startsAt, config.endsAt, config.maxPhotoDate)
        reconfiguringState.value = false
        // The id rides with the values so a switch landing mid-edit makes the use-case a no-op rather
        // than overwriting a different membership.
        commands.reconfigure(config.eventId, range.direction, range.chosenFrom, range.chosenUntil, form.saveToAlbum)
    }


    /**
     * The member's edits to the capture-range form (capability `photo-selection-policy`), grouped.
     *
     * Each reduces and nothing more: a preset tap touches no port, dispatches no command, and is
     * discarded by Cancel. They are intents rather than screen state because the screen SHOWS them —
     * and they are a GROUP because they are one surface's questions, asked together and answered
     * together, which is also what keeps this container's own surface readable.
     */
    val form: FormEdits = FormEdits()

    inner class FormEdits internal constructor() {
        fun onShareOn(on: Boolean) = intent { formState.value = formState.value.copy(shareOn = on) }

        fun onReceiveOn(on: Boolean) = intent { formState.value = formState.value.copy(receiveOn = on) }

        fun onSaveToAlbum(on: Boolean) = intent { formState.value = formState.value.copy(saveToAlbum = on) }

        fun onFromPreset(preset: FromChoice) = intent { formState.value = formState.value.copy(fromPreset = preset) }

        fun onFromCustom(value: LocalDateTime) = intent {
            formState.value = formState.value.copy(fromPreset = FromChoice.CUSTOM, fromCustom = value)
        }

        fun onUntilPreset(preset: UntilChoice) = intent { formState.value = formState.value.copy(untilPreset = preset) }

        fun onUntilCustom(value: LocalDateTime) = intent {
            formState.value = formState.value.copy(untilPreset = UntilChoice.CUSTOM, untilCustom = value)
        }
    }

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
                    // The two DUPLICATE rungs, first because they outrank every other reading of the same
                    // link — including `autoJoin`, which used to be tested before any of this and would
                    // therefore auto-provision once per delivery.
                    pending.state.value?.eventId == eventId -> ignoreRepeat(eventId, "a pending join is open")
                    current?.eventId == eventId -> ignoreRepeat(eventId, "already joined")
                    result.payload.autoJoin ->
                        autoConfirm(
                            eventId,
                            result.payload.minPhotoDate?.let(::captureCutoff),
                            result.payload.maxPhotoDate?.let(::captureCeiling),
                            result.payload.direction,
                            result.payload.saveToAlbum,
                        )
                    // First join → JoiningEvent; a different event while joined → Joined.pendingSwitch.
                    // One rung now: the rung that told them apart was `current.eventId != eventId`, and the
                    // same-event case is the duplicate rung above.
                    else -> startPending(eventId)
                }
            }
        }
    }

    /**
     * A delivery of a link this gate is already acting on, or has already acted on — **recorded, then
     * ignored** (capability `event-link`).
     *
     * The platform delivers the same link more than once, and that is measured rather than defensive:
     * build 687 received one URL twice on an iOS 18.7.9 cold launch (~130 ms apart, the scene delegate's
     * connection and then SwiftUI's `.onOpenURL`), and twice again on iOS 26.6 both while running (8 ms)
     * and cold (105 ms). So "exactly once" is enforced here, in tested code, and NOT assumed of any
     * arrangement of platform hooks — which is what lets more than one delivery hook stay live, and what
     * keeps a hook a future iOS adds or removes from reintroducing a double join.
     *
     * Both rungs read state that already exists and already self-clears, so there is no duplicate-tracking
     * field to leak or to forget to reset: `pending` is cleared when the join is committed or dismissed,
     * and `config` names the event only while the membership stands. A repeat is therefore ignored exactly
     * while the member is still deciding about it, and a genuinely re-opened invite afterwards is acted on
     * again.
     *
     * It logs because "nothing happened, this was a duplicate" and "nothing happened, the link never
     * arrived" are different answers with different causes (law "Absence is never silent"). A device log
     * that could not tell them apart is what made `SNAPSYNC-25` take a day to characterise.
     */
    private fun ignoreRepeat(eventId: String, because: String) {
        log("join gate: ignoring a repeated delivery of $eventId — $because")
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
    fun onConfirmJoin() = intent { commit() }

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
        val ph = p.phase?.takeIf { it.step == JoinPhase.Detailed.Step.Ready } as? JoinPhase.Detailed ?: return@intent
        closeOverlays()
        commands.leave()
        if (config.value == null) {
            pending.set(p.copy(phase = deriveLoadedPhase(ph.event)))
        }
    }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin() = intent { commit() }

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
        val ph = p.phase as? JoinPhase.Detailed ?: return@intent
        if (ph.step != JoinPhase.Detailed.Step.ExplainAccess) return@intent
        commands.requestAccess()
        pending.set(p.copy(phase = JoinPhase.Detailed(ph.event, JoinPhase.Detailed.Step.Ready)))
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
        // A fresh surface starts from the defaults — all on, the full event window. Seeding HERE rather
        // than in the reduction is what keeps the member's edits from being overwritten by every
        // subsequent reduction, and what stops a previous surface's choices leaking into this one.
        formState.value = RangeForm()
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
            is JoinLoad.Found ->
                deriveLoadedPhase(EventDetails(load.name, load.startsAt, load.endsAt, load.deletesAt))
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
    private fun deriveLoadedPhase(event: EventDetails): JoinPhase {
        val noEventConfigured = config.value == null
        val neverAsked = permission.value == PermissionStatus.NOT_DETERMINED
        val step = if (noEventConfigured && neverAsked) {
            JoinPhase.Detailed.Step.ExplainAccess
        } else {
            JoinPhase.Detailed.Step.Ready
        }
        return JoinPhase.Detailed(event, step)
    }

    private suspend fun commit() {
        val p = pending.state.value ?: return
        // What is committed is what the reduction RESOLVED — the same value the surface rendered. The
        // screen used to hand these back, which meant the clamping rules ran in a Composable and the
        // committed range was only as correct as the render path that produced it.
        val event = p.phase.details ?: return
        val range = resolveRange(formState.value, event.startsAt, event.endsAt, null, event.deletesAt)
        val cutoff = range.chosenFrom
        val until = range.chosenUntil
        val direction = range.direction
        val saveToAlbum = formState.value.saveToAlbum
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action. Both carry a non-null name, startsAt,
        // endsAt AND deletesAt — so a commit can never reach `JoinEvent` without the floor, the ceiling,
        // and the retention deadline.
        val detailed = p.phase as? JoinPhase.Detailed ?: return
        if (detailed.step != JoinPhase.Detailed.Step.Ready && detailed.step != JoinPhase.Detailed.Step.CommitFailed) return
        val (name, startsAt, endsAt, deletesAt) = detailed.event
        pending.set(p.copy(phase = JoinPhase.Detailed(detailed.event, JoinPhase.Detailed.Step.Committing)))
        val commit = try {
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
                    pending.set(p.copy(phase = JoinPhase.Detailed(detailed.event, JoinPhase.Detailed.Step.CommitFailed)))
                }
            }
            // Rethrown, never swallowed: the container's `exceptionHandler` reports it at `Error`, which is
            // what reaches the crash reporter. That handler is also why rethrowing is safe here — without it
            // Orbit's re-throw would cancel the intent job and take every later command with it.
            throw t
        }
        if (commit == JoinCommit.Committed) {
            // Success: config flips present via ConfigSource → reduces to Joined; drop the overlay.
            if (pending.state.value?.eventId == p.eventId) pending.set(null)
        } else if (pending.state.value?.eventId == p.eventId) {
            // The two failures land on DIFFERENT steps, because one is retryable and one is not
            // (capability `join-event`). A full event given the CommitFailed surface would offer a Retry
            // that fails identically every time, with nothing saying why.
            val step = when (commit) {
                JoinCommit.Full -> JoinPhase.Detailed.Step.EventFull
                else -> JoinPhase.Detailed.Step.CommitFailed
            }
            pending.set(p.copy(phase = JoinPhase.Detailed(detailed.event, step)))
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
        val commit = commands.commitJoin(
            eventId, load.name, load.startsAt, load.endsAt, load.deletesAt, cutoff, until, direction,
            saveToAlbum,
        )
        // The headless path has no surface to park on, so it names the reason in the log instead — the
        // one channel it has. `full` and `failed` are as different here as on the screen: a run that
        // aborts because the event is full will abort identically on every retry.
        if (commit != JoinCommit.Committed) {
            log("autoJoin aborted: join ${commit.name.lowercase()} for $eventId")
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
private fun JoinPhase.name(): String? = details?.name

// Config presence is the top rung: without a connected event there is nothing to share, so the create
// layer replaces everything regardless of permission or snapshot. Once config is present the screen is
// ALWAYS the joined layer (name · QR · share · leave) — permission and sync activity are moods of the
// one-line status, never a hero-replacing gate. There is no join-status rung: reconciliation runs in
// the extension and status is read from the completeness listing.
/**
 * The refusal and the remedy, joined into the layer that states both — or `null` while this build is
 * being served (capability `min-app-version`).
 *
 * Joined HERE rather than inside [reduceFrom] because the reduction's job is to RANK layers, while these
 * two inputs meet nowhere else: the refusal is observed and the store URL is a build constant. It also
 * keeps [reduceFrom] under the tier's parameter ceiling, which may only fall (`complexity-budgets`) — a
 * budget respected by grouping what belongs together rather than by raising a number.
 */
private fun updateLayerFor(refusal: AppVersionGate.Refusal?, appStoreUrl: String?): Layer.UpdateRequired? =
    refusal?.let { Layer.UpdateRequired(minimumVersion = it.minimumVersion, storeUrl = appStoreUrl) }

/**
 * What is shown while NO event is configured: the interactive join confirmation, or the create surface.
 *
 * Its own function for the reason [joinedLayer] is — it answers a different question from the
 * precedence table. `reduceFrom` decides WHICH of the three worlds the app is in (refused · unjoined ·
 * joined); this one decides what the unjoined world looks like, and nothing in it consults the health,
 * the permission or the clock.
 */
private fun unjoinedLayer(
    pending: PendingJoin?,
    creation: CreationStatus,
    transient: String?,
    form: RangeForm,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?, DeletesAt?) -> ResolvedRange,
): Layer {
    // A pending interactive join outranks the create layer (a switch whose leave already ran also
    // lands here — a transient no-event, shown full-screen with a Retry).
    if (pending != null) {
        val event = pending.phase.details
        return Layer.JoiningEvent(
            eventId = pending.eventId,
            phase = pending.phase,
            form = form,
            // Resolved only where there IS a window: the three detail-less phases render no range row,
            // so an absent resolution is the honest answer rather than one invented from `now`.
            range = event?.let { resolveAgainst(form, it.startsAt, it.endsAt, null, it.deletesAt) },
        )
    }
    // One banner, one value. The TRANSIENT wins while it is showing: a create failure is sticky
    // until the next attempt, so a link scanned in between would otherwise be silently outranked by
    // an older complaint. When it self-clears, the sticky failure shows again.
    return when (creation) {
        CreationStatus.InFlight -> Layer.CreatingEvent
        is CreationStatus.Failed -> Layer.CreateEvent(error = transient ?: creation.reason.message())
        CreationStatus.Idle -> Layer.CreateEvent(error = transient)
    }
}

private fun reduceFrom(
    config: EventConfig?,
    permission: PermissionStatus,
    snapshot: SyncStatus,
    creation: CreationStatus,
    download: DownloadProgress,
    pending: PendingJoin?,
    nowCutoff: CaptureDate,
    attested: Boolean,
    rename: RenameStatus,
    // The transient invalid-link error (capability `event-link`). It is an INPUT to the reduction, not a
    // value beside it: the create screen renders ONE banner, so the create state carries one error value
    // and this is one of its two causes.
    transient: String?,
    // The member's uncommitted choices on whichever decision surface is open, and everything needed to
    // resolve them: the window comes off the loaded phase (join gate) or the membership (reconfigure).
    form: RangeForm,
    reconfiguring: Boolean,
    // The backend's refusal of this build (capability `min-app-version`), already carrying its remedy,
    // or null while this build is served. Arrives composed — see `updateLayerFor`.
    updateRequired: Layer.UpdateRequired?,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?, DeletesAt?) -> ResolvedRange,
): Layer {
    // ABOVE config-absent, and above everything else. A refused build makes no successful metadata call
    // at all, so every layer below would render something untrue: a joined event that is not syncing, a
    // create that cannot succeed, a join that cannot commit. There is exactly one thing to say and one
    // thing to do.
    if (updateRequired != null) return updateRequired
    if (config == null) return unjoinedLayer(pending, creation, transient, form, resolveAgainst)
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
        // The download arm has its OWN read-ness, and it must gate the health too. `syncHealth` below
        // hides an arrow when its counts are complete, and shows "In sync" only when BOTH arrows are
        // hidden — so an un-read DownloadProgress, whose `downloaded` and `total` are both a placeholder
        // zero, hides the download arrow and can carry the whole screen to a settled check mark on its
        // own. Gating the upload side alone would relocate that defect rather than remove it: the next
        // member to join an event with foreign photos outstanding would meet it through this arm on
        // their first launch (`SNAPSYNC-14`, `SNAPSYNC-16`; capability `sync-status`).
        !download.read -> SyncHealth.Loading
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
    return joinedLayer(
        config, health, pendingSwitch, permission, ended, rename, reconfiguring, transient, form, resolveAgainst,
    )
}

/**
 * The joined layer itself, once the precedence table above has decided the health.
 *
 * Its own function because it answers a different question: `reduceFrom` decides WHICH layer and, for
 * this one, which health rung; this assembles what that layer carries.
 */
@Suppress("LongParameterList")
private fun joinedLayer(
    config: EventConfig,
    health: SyncHealth,
    pendingSwitch: PendingSwitch?,
    permission: PermissionStatus,
    ended: Boolean,
    rename: RenameStatus,
    reconfiguring: Boolean,
    transient: String?,
    form: RangeForm,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?, DeletesAt?) -> ResolvedRange,
): Layer.Joined {
    return Layer.Joined(
        membership = config,
        // Derived HERE and nowhere else (capability `event-invite-qr`, decision D3's surviving half):
        // one derivation feeds both the rendered QR and the share action, so they cannot drift.
        inviteUrl = config.inviteUrl(),
        health = health,
        pendingSwitch = pendingSwitch,
        // The resting affordance, not an attention state (capability `limited-photo-access`): a
        // partial grant's joined layer always offers the picker, whatever the health.
        canChoosePhotos = permission == PermissionStatus.LIMITED,
        ended = ended,
        renameState = rename.toRenameState(),
        // The same transient cell the create layer's banner reads. A rejected link is rejected wherever
        // it arrives, so the message reaches whichever layer is showing rather than only one of them.
        notice = transient,
        // The settings surface, pre-filled and resolved against the MEMBERSHIP's own window — which is
        // the one deliberate divergence from the join gate: a legacy membership carrying no event end
        // bounds against its own ceiling, so a no-edit Save is idempotent rather than silently widening.
        surface = if (reconfiguring) {
            JoinedSurface.Reconfigure(
                form = form,
                range = resolveAgainst(form, config.startsAt, config.endsAt, config.maxPhotoDate, null),
            )
        } else {
            JoinedSurface.Status
        },
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
private fun RenameStatus.toRenameState(): RenameState = when (this) {
    RenameStatus.Idle -> RenameState.Idle
    RenameStatus.InFlight -> RenameState.InFlight
    RenameStatus.Succeeded -> RenameState.Succeeded
    is RenameStatus.Failed -> RenameState.Failed(reason.message())
}

/**
 * The rename dialog's failure copy (capability `event-rename`). Two reasons, because the port reports two:
 * the backend rejected the name, or everything else.
 *
 * There is deliberately no "this event no longer exists" copy for the `404` that also arrives as
 * [RenameFailureReason.SERVER]. A `404` here is a single witness that the event is gone, and the
 * self-leave needs two (capability `leave-event`); giving it copy would give it a meaning, and a meaning
 * invites acting on it. The standing foreground refresh reaches that verdict on its own terms.
 */
private fun RenameFailureReason.message(): String = when (this) {
    RenameFailureReason.INVALID_NAME -> "That name wasn't accepted. Try a shorter one."
    RenameFailureReason.SERVER -> "Couldn't rename the event. Check your connection and try again."
}

private fun CreationFailureReason.message(): String = when (this) {
    // The client already blocks the two knowable rules — empty (Create is disabled until the trimmed name
    // is non-empty) and over-length (the field caps at 100). So a returned 400 is a rule this client can't
    // name; the copy says what to try rather than asserting a constraint it doesn't know.
    CreationFailureReason.INVALID_NAME -> "That name wasn't accepted. Try a different one."
    CreationFailureReason.SERVER -> "Couldn't reach the server."
}
