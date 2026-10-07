package app.snapsync.presentation

import app.snapsync.model.DeviceRefusal
import app.snapsync.model.CreateDraftSession
import app.snapsync.model.NetworkNotice
import app.snapsync.model.NetworkAccess
import app.snapsync.model.AlbumKind
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.ReportDestination
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventStart
import app.snapsync.model.EventEnd
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.Arrow
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.RangeChoice
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.feature.membership.readmodel.RenameFailureReason
import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.model.GalleryAccess
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.model.SyncStatus
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncCounts
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.ReportContext
import app.snapsync.model.DirectionCount
import app.snapsync.model.EventTiming
import app.snapsync.model.eventTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.orbitContainer
import app.snapsync.model.EventDetails
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.ScreenMessage
import app.snapsync.model.Overlays
import app.snapsync.model.PendingSwitch
import app.snapsync.model.RangeForm
import app.snapsync.model.RenameState
import app.snapsync.model.ResolvedRange
import app.snapsync.model.ShareCount
import app.snapsync.model.SyncHealth
import app.snapsync.model.UiState
import app.snapsync.model.details
import app.snapsync.model.step
import app.snapsync.model.VersionRefusal
import app.snapsync.model.AppLink
import app.snapsync.model.BuildLabel
import app.snapsync.model.MobileDataState
import app.snapsync.model.ReportOutcome

class StatusContainerHost(
    // Every read-model this container reduces over (see [StatusSources]). Bundled because they are one
    // KIND of thing — values observed and folded into `UiState` — while what the container INVOKES
    // ([commands], [queries]) and what it EMITS ([StatusDiagnostics]) stay separate.
    sources: StatusSources,
    private val scope: CoroutineScope,
    // Supplies "now" as a cutoff string and converts a local pick (capability `photo-sharing`).
    // Injected — with NO default: a default would have to read the system clock here, which the
    // through-ports law forbids. Production wires
    // the `Clock` port (now, and the zone read once); tests pass a fixed instant and zone.
    private val cutoffFormatter: CutoffFormatter,
    // The user-tap **command bundle** (`docs/architecture.md`, "Commands cross one door"):
    // leave / create / commitJoin / share / requestAccess / openSettings — `model/` vocabulary whose
    // live instance is built only in `compose/` (`AppCore.userCommands`) — this container fires
    // commands solely through it and never references a feature command (or `ports/`, or `flow/`)
    // directly; the armed presentation gate enforces the import law. Required: a host states every
    // command it wires, so a forgotten one is a compile error rather than an inert tap.
    //
    // NB the CLAMP (`minPhotoDate = max(chosen, startsAt)`) is applied on the far side of the
    // `commitJoin` command, inside `JoinEvent` — this container passes the chosen value through raw,
    // so no entry path can reach a provision without the floor by forgetting to clamp here.
    private val commands: UserCommands,
    // The user-query bundle (`docs/architecture.md`, "Queries cross a lane-gated door"): the join gate's
    // details read and the shareable count. Reads this container INVOKES and reduces on, built and
    // lane-decorated in `compose/` beside the commands — so neither runs a port read on the thread that
    // asked.
    private val queries: UserQueries,
    // The two out-channels (see [StatusDiagnostics]): the dev-path log and the intent-error seam.
    diagnostics: StatusDiagnostics,
    // Whether an invite link's dev/test hints (`autoJoin` + its overrides) are acted on (capability
    // `join-event`, "Joining happens only on confirmation"). The link can never authorize its own headless
    // join, since the decoder accepts those keys from ANY link; the composition root does, and only a rig
    // build's root answers `Honoured`. Defaulted to the shipped answer so a host that forgets it is safe
    // rather than exploitable.
    private val inviteLinkHints: InviteLinkHints = InviteLinkHints.Ignored,
    // Where a bug report goes on this build (capability `privacy-security`) — a constant the composition states,
    // carried on every `UiState` so the sheet says it. Defaulted to the distributed build's answer.
    private val reportDestination: ReportDestination = ReportDestination.DEVELOPER,
    // Which build this is (capability `sync-status`) — the app menu's footer, a constant the composition states.
    private val build: BuildLabel = BuildLabel.UNKNOWN,
    // How this phone holds an event album (capability `event-album`): the photo library's own answer, which the
    // composition reads once and the surfaces' album note follows. Defaulted to the iPhone's answer.
    private val albumKind: AlbumKind = AlbumKind.COLLECTION,
) : OrbitContainerHost<UiState, UiState, Nothing> {

    // The bundles are unpacked into the names the body already uses. Grouping happens at the boundary,
    // where a caller has to read it; inside, each source keeps the name that says what it is.
    private val syncSource = sources.sync
    private val permission = sources.permission
    private val config = sources.config
    private val creationStatus = sources.creation
    private val downloadSource = sources.download
    private val attested = sources.verification.attested
    private val refusal = sources.verification.refusal

    // The two halves of the attestation's verdict travel as one input, so the reduction below keeps its arity.
    private val trust: Flow<Pair<Boolean, DeviceRefusal?>> = combine(attested, refusal) { a, r -> a to r }
    private val pending = sources.pending
    private val versionRefusal = sources.versionRefusal
    private val network = sources.network
    private val mobileData = sources.mobileData
    private val renameFlow: StateFlow<RenameStatus> = sources.rename
    private val store = sources.store
    private val foreground = sources.foreground
    private val inviteKey = sources.inviteKey

    private val log = diagnostics.log
    private val onIntentError = diagnostics.onIntentError

    // Declared here rather than beside their use because `container`'s initializer reduces over it:
    // a property initialized later is null at that moment.
    private val local = MutableStateFlow(Local(form = freshForm))
    private var transientErrorClear: Job? = null
    private var reportNoticeClear: Job? = null

    /** The non-idempotent commands claimed while their intent runs — see [guardedIntent]. */
    private val inFlight = MutableStateFlow<Set<Guarded>>(emptySet())

    /**
     * The settings' changes, in the order they were made (capability `manage-membership`: "changes made one after
     * another apply in order, the last one standing"). Orbit runs intents concurrently, so a confirm, the next switch or
     * a close could overtake a change still saving; each settings act is enqueued HERE, synchronously in the tapping
     * thread, and one consumer runs them one at a time. Started lazily on the container's scope.
     */
    private val settingsQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val settingsWorker by lazy {
        scope.launch {
            for (act in settingsQueue) {
                try {
                    act()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onIntentError(e)
                }
            }
        }
    }


    /** An untouched surface's choices on this phone: all on, the album included. */
    private val freshForm: RangeForm get() = RangeForm(albumKind = albumKind)

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
            shareCount = local.value.shareCount,
        )
    }


    /**
     * "Now", re-emitted every minute **only** while the joined event has not ended (capability
     * `sync-status`): the not-started line and the dates line's countdown are the two things it moves.
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
                        // Two wall-clock things depend on this tick: NotStarted (until `startsAt`) and the
                        // dates line's countdown — "starts in …", "ends in …", then "ended" (until `endsAt`).
                        // Keep ticking while EITHER boundary is still ahead; once both have passed, no
                        // clock-driven line can change, so the timer self-terminates. Canonical fixed-width
                        // UTC ⇒ lexicographic order IS chronological.
                        // (A backgrounded iOS app is suspended, so this is foreground-only in practice.)
                        val startPassed = now >= startsAt.at
                        val endPassed = now >= endsAt.at
                        if (startPassed && endPassed) return@flow
                        delay(NOT_STARTED_TICK_MILLIS)
                    }
                }
            }

    override val container: OrbitContainer<UiState, UiState, Nothing> =
        scope.orbitContainer(
            // All seams hold their current truth synchronously, so the first state the screen can ever
            // render derives from real values — never a guess or a placeholder.
            initialState = render(
                Membership(
                    config.value, permission.value, syncSource.status.value, downloadSource.value, attested.value,
                    network.access.value, mobileData.value, refusal.value,
                ),
                Interaction(pending.value, creationStatus.value, renameFlow.value, versionRefusal.value),
                local.value,
                cutoffFormatter.nowCutoff(),
            ),
            // The container SURVIVES a throwing intent (spec `sync-status`) — and this handler is the
            // whole of what makes it so. It is not a logging convenience: Orbit runs each intent as
            // `runCatchingCancellable { … }.exceptionOrNull()?.let { settings.exceptionHandler?.handleException(…) ?: throw it }`,
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
                // Each new value reduces straight to a UI state. The only clock-driven input is `nowTick`, and
                // it runs ONLY while an event has not ended (see above) — every other re-emission is a real
                // source change or a presentation-owned cell's.
                combineFlat(
                    config, permission, syncSource.status, downloadSource, trust, network.access, mobileData,
                    pending, creationStatus, renameFlow, versionRefusal,
                    local, nowTick,
                ) { config, permission, sync, download, (attested, refused), access, mobileData, pending, creation, rename, refusal, local, now ->
                    render(
                        Membership(config, permission, sync, download, attested, access, mobileData, refused),
                        Interaction(pending, creation, rename, refusal),
                        local,
                        now,
                    )
                }.collect { ui -> reduce { ui } }
            }
            // An encrypted event's invite carries its key, read from where it is kept once the membership names one.
            intent {
                inviteKey.collect { key -> local.update { it.copy(inviteKey = key) } }
            }
            // The create draft follows the app's returns to the foreground (capability `create-event`).
            intent {
                foreground.collect { back ->
                    local.update { it.copy(createDraft = it.createDraft.afterReturn(back.count, back.awayFor)) }
                }
            }
            // A missing network came back (capability `join-event`, "Without a network, the join screen waits for one"):
            // a join whose details could not load loads them now, untapped. Only on that return — a load that failed
            // while the network was there (an unreachable server) still waits for the member's Retry.
            intent {
                network.returned.collect {
                    if (pending.value?.phase == JoinPhase.LoadFailed) onRetryLoad()
                }
            }
            // The shareable count follows the range the showing surface resolves, and the grant (a late
            // first-join grant resolves the count). `collectLatest`: a newer range cancels an older count.
            intent {
                combine(container.stateFlow.map { it.layer.countedRange() }, permission) { range, grant ->
                    range?.let { CountKey(it.chosenFrom, it.chosenUntil, grant) }
                }
                    .distinctUntilChanged()
                    .collectLatest { key ->
                        if (key == null) return@collectLatest
                        local.update { it.copy(shareCount = ShareCount.Counting) }
                        val count = try {
                            queries.shareableCount(key.from, key.until)?.let { ShareCount.Ready(it) } ?: ShareCount.Unavailable
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A failed read is no count, not a crash: the row is omitted (capability `join-event`).
                            log("shareable count failed: ${e.message}")
                            ShareCount.Unavailable
                        }
                        local.update { it.copy(shareCount = count) }
                    }
            }
        }

    /** What the count is recomputed on — the bounds and the grant, never the count itself. */
    private data class CountKey(val from: CaptureCutoff, val until: CaptureCeiling, val grant: GalleryAccess)

    /**
     * One UI state from the observed inputs, the presentation-owned cells and "now".
     *
     * A refused build outranks everything (capability `app-update-required`), config-absent included: it makes no
     * successful metadata call at all, so every other layer would render something untrue — a joined event that is
     * not syncing, a create that cannot succeed, a join that cannot commit. Its layer joins the refusal with this
     * build's store link, a constant, which is why it is assembled here rather than ranked inside [reduceFrom].
     */
    private fun render(membership: Membership, interaction: Interaction, local: Local, now: CaptureDate): UiState {
        val layer = interaction.versionRefusal
            ?.let { Layer.UpdateRequired(minimumVersion = it.minimumVersion, store = store) }
            ?: reduceFrom(membership, interaction, local, now, ::resolveRange)
        return UiState(
            layer, local.overlays.maskedFor(layer), reportDestination, build,
            MobileDataState(on = membership.mobileData, notSaved = local.mobileDataNotSaved),
        )
    }


    /**
     * Flash the transient invalid-link error (capability `join-event`): a link arrived that the decoder
     * rejected, so whichever layer is showing — create, join or joined — shows a self-clearing message
     * without touching persisted state.
     *
     * The value is an INPUT to the reduction — it reaches the screen inside `Layer.CreateEvent.error`
     * (coalesced with a sticky create failure), `Layer.JoiningEvent.notice` or `Layer.Joined.notice` — but
     * the set-then-clear choreography lives HERE, in presentation (`docs/architecture.md`, "Commands cross
     * one door": multi-step interactions are presentation-owned, and interaction state dies with the UI).
     *
     * A rejected link while the message is already showing re-arms the full window (the timer restarts)
     * — the deliberate reading of "self-clearing a few seconds after it LAST appeared".
     */
    private fun showTransientError() {
        local.update { it.copy(transientError = ScreenMessage.INVALID_LINK) }
        transientErrorClear?.cancel()
        transientErrorClear = scope.launch {
            delay(TRANSIENT_ERROR_MILLIS)
            local.update { it.copy(transientError = null) }
        }
    }

    /**
     * Create a new event with [name], starting at [startsAt] (create-event). Delegates to the
     * injected [EventCreator] (fire-and-forget): it mints the event and, on success, provisions it
     * through the same path a scanned QR uses (config goes present, the reduction leaves the create
     * layer). Permission is not consulted here — a missing grant surfaces afterward via
     * `PermissionBlocked`. The in-flight and failure outcomes arrive back through the creation status read-model;
     * nothing is reduced here.
     *
     * [startsAt] arrives as the screen's **local** wall-clock pick and is converted here, through the same
     * [CutoffFormatter] the join surface uses — so the app has exactly one origin of "now" and one
     * local→UTC conversion, and `:ui:screens` stays free of any clock or timezone knowledge.
     */
    fun onCreateEvent(name: String, startsAt: LocalDateTime, endsAt: LocalDateTime) =
        guardedIntent(Guarded.Create) {
            commands.create(
                name,
                EventStart(cutoffFormatter.toCutoff(startsAt)),
                EventEnd(cutoffFormatter.toCutoff(endsAt)),
            )
        }

    /**
     * The three photo-access taps (capability `photo-access`, `photo-access`), grouped —
     * they are one question the screen asks in three forms, and the screen's own `AccessActions` bundle
     * already mirrors this grouping.
     */
    val access: AccessCommands = AccessCommands()

    inner class AccessCommands internal constructor() {
        fun onRequestPermission() = intent { commands.requestAccess() }

        /**
         * The joined layer's "Choose more photos" tap (capability `photo-access`) — presents the
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
        local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
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
    fun onShareInvite() = intent { (state.layer as? Layer.Joined)?.let { commands.share(it.inviteUrl, it.membership.name) } }

    /**
     * Open the App Store page from the update-required screen (capability `app-update-required`).
     *
     * The URL is read from the CURRENT state rather than taken from the caller, exactly as
     * [onShareInvite] reads the invite URL: the screen's contract is that a build carrying no store URL
     * renders no button, and reading it here means the container cannot be asked to open one the state
     * does not hold.
     */
    fun onOpenAppStore() = intent {
        (state.layer as? Layer.UpdateRequired)?.store?.let { commands.openLink(it.url) }
    }

    /**
     * Opening and closing what is drawn over — or instead of — the current layer.
     *
     * Grouped for the same reason [form] is: these are one question ("what is on screen"), and each of
     * them reduces and nothing more. That is the property `manage-membership` D4 asked for — a
     * pure navigation act must not cross a flow command — and it still holds now that the answer is
     * state rather than a screen-held flag.
     */
    val surfaces: SurfaceCommands = SurfaceCommands()

    inner class SurfaceCommands internal constructor() {
        fun onConfirmLeaveOpen() = intent { local.editOverlays { it.copy(confirmingLeave = true) } }

        fun onConfirmLeaveDismiss() = intent { local.editOverlays { it.copy(confirmingLeave = false) } }

        /**
         * Open the rename sheet on a clean latch: a rename that finished after the sheet was dismissed left its
         * terminal status behind, and the sheet would read it as this edit's outcome and close itself.
         */
        fun onRenameOpen() = intent {
            if (renameFlow.value.isTerminal) commands.resetRename()
            local.editOverlays { it.copy(renaming = true) }
        }

        fun onRenameDismiss() = intent { local.editOverlays { it.copy(renaming = false) } }

        fun onQrOpen() = intent { local.editOverlays { it.copy(showingQr = true) } }

        fun onQrDismiss() = intent { local.editOverlays { it.copy(showingQr = false) } }

        fun onReportBugOpen() = intent {
            local.update { it.copy(overlays = it.overlays.copy(reportingBug = true, reportSeed = null), verifiedReport = false) }
        }

        /** "Report this" beside a refusal: the same sheet, its description written for [message] (capability `privacy-security`). */
        fun onReportRefusal(message: ScreenMessage) = intent {
            local.update {
                it.copy(overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = message), verifiedReport = true)
            }
        }

        fun onReportBugDismiss() = intent { local.editOverlays { it.copy(reportingBug = false, reportSeed = null) } }

        /** The app menu (capability `sync-status`). Where the layer does not offer it, an open flag is masked. */
        fun onMenuOpen() = intent { local.update { it.copy(overlays = it.overlays.copy(menuOpen = true), mobileDataNotSaved = false) } }

        fun onMenuDismiss() = intent { local.update { it.copy(overlays = it.overlays.copy(menuOpen = false), mobileDataNotSaved = false) } }

        /**
         * The menu's mobile-data switch (capability `mobile-data`): applied as it is flipped, the menu staying open. The
         * switch shows the device's choice, which only a save moves — so a flip that could not be saved leaves it where
         * it was, and the menu says so (decision record `changes/archive/2026-10-07-mobile-data-per-device`, D3).
         */
        fun onMobileData(on: Boolean) = intent {
            local.update { it.copy(mobileDataNotSaved = false) }
            if (!commands.setMobileData(on)) local.update { it.copy(mobileDataNotSaved = true) }
        }

        /** The menu's "Report a problem": the menu gives way to the sheet in one edit, so the two never stack. */
        fun onMenuReportBug() = intent {
            local.update {
                it.copy(overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = null), verifiedReport = false)
            }
        }

        /**
         * One of the menu's links, opened outside the app (capability `sync-status`). The menu closes first, so
         * coming back finds the screen rather than the drawer; a refused hand-off is logged by the command.
         */
        fun onOpenLink(link: AppLink) = intent {
            local.editOverlays { it.copy(menuOpen = false) }
            commands.openLink(link.url)
        }

        /** The report's brief word, tapped away before it cleared itself. */
        fun onReportNoticeDismiss() = intent {
            reportNoticeClear?.cancel()
            local.editOverlays { it.copy(reportNotice = null) }
        }

        /**
         * Open the settings surface, pre-filled from the persisted membership. Seeding HERE rather than
         * in the reduction is what makes the pre-fill a SNAPSHOT: a foreground refresh landing mid-edit
         * updates the heading, not the controls in the member's hand.
         */
        fun onOpenReconfigure() = settings.enqueue {
            val config = config.value ?: return@enqueue
            val form = reconfigureForm(config, cutoffFormatter::toLocal).copy(albumKind = albumKind)
            local.update {
                it.copy(
                    form = form,
                    settings = Owned(config.eventId, SettingsSurface.Open),
                    pendingWithdrawal = Owned(null, null),
                    lastApplied = Owned(null, null),
                )
            }
        }

        /**
         * Close the settings (capability `manage-membership`): swiped down, back, or a tap on the joined screen above
         * them. Every change already applied as it was made, so closing writes nothing — and a question still open
         * about withdrawing photos is answered "keep sharing".
         */
        fun onCancelReconfigure() = settings.enqueue {
            local.update { it.copy(settings = Owned(null, SettingsSurface.Closed), pendingWithdrawal = Owned(null, null)) }
        }
    }

    /**
     * Rename the joined event (capability `manage-membership`), confirmed on the heading's rename dialog.
     * Delegates to the injected [UserCommands.rename] with [eventId] — the event the dialog was opened
     * for — so a switch that landed mid-edit makes the use-case a no-op rather than renaming a different
     * event. Fire-and-forget; the outcome arrives via [renameStatus] and the new name via the config
     * read-model. Unlike [onReconfigure], this writes the SHARED event, but it crosses the same one door.
     */
    fun onRenameEvent(eventId: String, name: String) = guardedIntent(Guarded.Rename) {
        local.update { it.copy(renameOwner = eventId) }
        commands.rename(eventId, name)
    }

    /** Clear the [renameStatus] latch once the screen has consumed a terminal value. */
    fun onRenameStatusConsumed() = intent { commands.resetRename() }

    /**
     * Send the diagnostic dump (capability `privacy-security`) with the operator's account of the
     * problem — already trimmed and length-bounded by the sheet that collected it — an opaque label for the
     * surface it was sent from (the screen, which only the screen itself can name), and the counts that surface
     * showed, taken from the state this host last reduced ([shownCounts]). On a build that reports nowhere the report
     * is kept on the device instead ([UiState.reportDestination] says which).
     * Once it has been handed off, the app briefly says what became of it ([Overlays.reportNotice]) — sent, saved,
     * or neither — and makes no delivery claim (the channel may queue and retransmit). A command that failed
     * outright is "neither": the user confirmed a report, and silence would read as sent.
     */
    val onSendDiagnostics: (String, String) -> Unit = { note, screen ->
        intent {
            // Read and spent here: the sheet is dismissed before it sends, so the report remembers how it was opened.
            val verification = local.getAndUpdate { it.copy(verifiedReport = false) }.verifiedReport
            val outcome = try {
                commands.sendDiagnostics(note, ReportContext(screen, shownCounts(state), verification))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onIntentError(e)
                ReportOutcome.NOT_SENT
            }
            // Shown, then cleared after a moment; a later report's word replaces an earlier one's.
            local.editOverlays { it.copy(reportNotice = outcome) }
            reportNoticeClear?.cancel()
            reportNoticeClear = scope.launch {
                delay(REPORT_NOTICE_MILLIS)
                local.editOverlays { it.copy(reportNotice = null) }
            }
        }
    }

    /**
     * The event's settings, applied as they change (capability `manage-membership`): the queue every settings act
     * runs through, what the next change builds on, and the answers to the withdrawal question.
     */
    val settings: SettingsChanges = SettingsChanges()

    inner class SettingsChanges internal constructor() {
        /** Run [act] after every settings act enqueued before it — see [settingsQueue]. */
        internal fun enqueue(act: suspend () -> Unit) {
            settingsWorker.start()
            settingsQueue.trySend(act)
        }

        /**
         * The settings apply each change as it is made (capability `manage-membership`): one reconfigure per change, built
         * from the membership IN EFFECT with that one change, and run in the container's intent order — so quick changes
         * apply one after another and the last one stands. A change that would withdraw photos from the event (sharing
         * off, or a narrower range) is held instead, and asked about ([pendingWithdrawal][Local.pendingWithdrawal]).
         */
        internal suspend fun change(change: (SettingChange, EventConfig) -> SettingChange) {
            val config = config.value ?: return
            val inEffect = settingsInEffect(config)
            val next = change(inEffect, config)
            if (next.withdrawsFrom(inEffect)) {
                local.update { it.copy(pendingWithdrawal = Owned(config.eventId, next)) }
                return
            }
            applySetting(config, inEffect, next)
        }

        /**
         * The settings in effect: the last change these settings applied, else the membership as read. The read can lag a
         * save the use-case just made, and a change built on it would quietly undo the one before — so quick changes
         * build on each other, not on a stale read.
         */
        private fun settingsInEffect(config: EventConfig): SettingChange =
            local.value.lastApplied.forMembership(config.eventId, null) ?: SettingChange.of(config)

        /**
         * Run one change through [UserCommands.reconfigure]. The event id rides with the values so a switch that landed
         * while the settings were open makes the use-case a no-op rather than overwriting a different membership; the
         * clamp to the event's window is the use-case's. Afterwards the controls are re-seeded from the settings in
         * effect — the change if it landed, the ones before it if not — so a change that did not land shows the setting
         * still in effect, and says so: the controls never hold a value that is not saved.
         */
        private suspend fun applySetting(config: EventConfig, before: SettingChange, next: SettingChange) {
            local.update { it.copy(pendingWithdrawal = Owned(null, null)) }
            val outcome = commands.reconfigure(config.eventId, next.direction, next.from, next.until, next.saveToAlbum)
            if (outcome == ReconfigureOutcome.NotCurrent) {
                local.update { it.copy(settings = Owned(null, SettingsSurface.Closed), lastApplied = Owned(null, null)) }
                return
            }
            val saved = outcome != ReconfigureOutcome.SaveFailed
            val inEffect = if (saved) next else before
            val reseeded = reconfigureForm(inEffect.appliedTo(config), cutoffFormatter::toLocal).copy(albumKind = albumKind)
            val surface = if (saved) SettingsSurface.Open else SettingsSurface.SaveFailed
            local.update {
                it.copy(form = reseeded, settings = Owned(config.eventId, surface), lastApplied = Owned(config.eventId, inEffect))
            }
        }

        /** "Stop sharing" on the withdrawal question: the held change applies. */
        fun onConfirmStopSharing() = enqueue {
            val config = config.value ?: return@enqueue
            val held = local.value.pendingWithdrawal.forMembership(config.eventId, null) ?: return@enqueue
            applySetting(config, settingsInEffect(config), held)
        }

        /** "Keep sharing": the held change is dropped, and the controls — which never took it — stay as they were. */
        fun onKeepSharing() = enqueue { local.update { it.copy(pendingWithdrawal = Owned(null, null)) } }

        /** Whether the event's settings are open over the joined screen — where a form edit applies rather than drafts. */
        internal fun isOpen(): Boolean =
            local.value.settings.forMembership(config.value?.eventId, SettingsSurface.Closed) != SettingsSurface.Closed
    }

    /**
     * The member's edits to the participation form (capabilities `photo-sharing`, `manage-membership`), grouped.
     *
     * At the join gate each only reduces: a tap touches no port and dispatches no command until Join. With the
     * event's settings open each one IS the change, applied through [SettingsChanges.change]. They are intents rather than
     * screen state because the screen SHOWS them — and a GROUP because they are one surface's questions.
     */
    val form: FormEdits = FormEdits()

    inner class FormEdits internal constructor() {
        /**
         * While joined, an edit can only be a settings change, so it takes its place in the settings' order
         * ([SettingsChanges.enqueue]) — behind an open still queued. At the join gate it is an ordinary intent, so the Join that
         * follows it reads the form it left.
         */
        private fun formIntent(act: suspend () -> Unit) {
            if (config.value != null) settings.enqueue(act) else intent { act() }
        }

        // At the join gate each edit drafts the form; with the event's settings open it applies at once.
        fun onShareOn(on: Boolean) = formIntent {
            if (settings.isOpen()) {
                settings.change { now, _ -> now.copy(direction = directionOf(on, now.direction.includesDownload)) }
            } else {
                local.editForm { it.copy(shareOn = on) }
            }
        }

        fun onReceiveOn(on: Boolean) = formIntent {
            if (settings.isOpen()) {
                settings.change { now, _ -> now.copy(direction = directionOf(now.direction.includesUpload, on)) }
            } else {
                local.editForm { it.copy(receiveOn = on) }
            }
        }

        fun onSaveToAlbum(on: Boolean) = formIntent {
            if (settings.isOpen()) settings.change { now, _ -> now.copy(saveToAlbum = on) } else local.editForm { it.copy(saveToAlbum = on) }
        }

        fun onRangePreset(preset: RangeChoice) = formIntent { editRange { it.copy(preset = preset) } }

        /** A custom range from the calendar; a `null` bound keeps the one already picked. */
        fun onRangeCustom(from: LocalDateTime?, until: LocalDateTime?) = formIntent {
            editRange { f -> f.copy(preset = RangeChoice.CUSTOM, customFrom = from ?: f.customFrom, customUntil = until ?: f.customUntil) }
        }

        /**
         * A committed range (a preset chip, or the calendar's OK). With the settings open it is the one place the
         * bounds are re-resolved from the form; every other change carries the bounds in effect untouched.
         */
        private suspend fun editRange(edit: (RangeForm) -> RangeForm) {
            if (!settings.isOpen()) {
                local.editForm(edit)
                return
            }
            val form = edit(local.value.form)
            settings.change { now, c ->
                val range = resolveRange(form, c.startsAt, c.endsAt, now.until)
                now.copy(from = range.chosenFrom, until = range.chosenUntil)
            }
        }
    }

    /**
     * An event link arrived (forwarded raw from the platform). Decode it with the shared codec; an
     * invalid link flashes the transient error without touching state. A valid link opens the **join
     * gate** (capability `join-event`): `autoJoin` auto-confirms headlessly — **only** when the root
     * honours invite-link hints, which only a rig build's does — otherwise a first join opens the
     * full-screen confirmation and a different event while joined opens a switch confirmation, the
     * link's overrides discarded. Re-scanning the already-joined event is a no-op (never re-enrolls).
     */
    fun onOpenUrl(raw: String) = intent {
        when (val result = decodeEventUrl(raw)) {
            is ConfigDecodeResult.Failure -> showTransientError()
            is ConfigDecodeResult.Success -> {
                val eventId = result.payload.eventId
                val current = config.value
                when {
                    // The two DUPLICATE rungs, first because they outrank every other reading of the same
                    // link — including `autoJoin`, which tested earlier would auto-provision once per delivery.
                    pending.value?.eventId == eventId -> ignoreRepeat(eventId, "a pending join is open")
                    current?.eventId == eventId -> ignoreRepeat(eventId, "already joined")
                    // A crafted link must not join, switch or start sharing without a tap, so the link's
                    // own `autoJoin` is never the authority — the root's [inviteLinkHints] is.
                    result.payload.autoJoin && inviteLinkHints == InviteLinkHints.Honoured ->
                        autoConfirm(
                            eventId,
                            result.payload.minPhotoDate?.let(::captureCutoff),
                            result.payload.maxPhotoDate?.let(::captureCeiling),
                            result.payload.direction,
                            result.payload.saveToAlbum,
                            result.payload.key,
                        )
                    // First join → JoiningEvent; a different event while joined → Joined.pendingSwitch (the
                    // same-event case is the duplicate rung above). An `autoJoin` link lands here too when
                    // hints are ignored — an ordinary invite — and says so in the log.
                    else -> {
                        if (result.payload.autoJoin) log("join gate: ignoring the invite-link hints of $eventId")
                        startPending(eventId, result.payload.key)
                    }
                }
            }
        }
    }

    /**
     * A delivery of a link this gate is already acting on, or has already acted on — **recorded, then
     * ignored** (capability `join-event`).
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
        val p = pending.value ?: return@intent
        pending.value = p.copy(phase = JoinPhase.Loading)
        loadInto(p.eventId)
    }

    /**
     * Confirm a first join with the chosen capture-date [cutoff], participation [direction], and album
     * choice [saveToAlbum] (capability `event-album`): enroll → provision (no leave).
     *
     * Where the surface said so ([asksAccessOnJoin]), the same tap first raises iOS's photo-access dialog —
     * the one deliberate action the join gate has that may (capability `photo-access`). The join does not
     * wait for the answer: `request()` returns nothing and cannot suspend, the grant arrives only through the
     * permission source, and the join goes ahead whatever it is — so the dialog lands over Committing or the
     * joined screen, and a later grant starts sharing through the existing subscription (decision record
     * `simplify-join-screen`, D3). A retry never re-requests: by then iOS has an answer.
     */
    fun onConfirmJoin() = intent {
        if (asksAccessOnJoin(config.value, permission.value)) commands.requestAccess()
        commit()
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
     * Nothing is re-derived after the leave: the loaded phase is always the confirm surface, and whether
     * its confirm also asks for photo access is read live once the config is gone ([asksAccessOnJoin]).
     */
    fun onConfirmSwitch() = guardedIntent(Guarded.SwitchLeave) {
        val p = pending.value ?: return@guardedIntent
        if (p.phase.step != JoinPhase.Detailed.Step.Ready) return@guardedIntent
        local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
        commands.leave()
    }

    /**
     * Run [run] as an intent unless [command] is already in flight (capability `sync-status`, "A
     * non-idempotent command is in flight before it first suspends"; decision record `harden-seam-bug-classes`,
     * D13). The claim is taken HERE, synchronously in the tapping thread, before any intent is launched — two taps
     * before the screen recomposes launch one command, not two — and released when the intent's body returns.
     */
    private fun guardedIntent(command: Guarded, run: suspend () -> Unit) {
        if (command in inFlight.getAndUpdate { it + command }) return
        intent {
            try {
                run()
            } finally {
                inFlight.update { it - command }
            }
        }
    }

    /** Retry a failed commit — the leave (if any) already succeeded, so this re-runs only the join. */
    fun onRetryJoin() = intent { commit() }

    /**
     * Discard the pending join, returning to the base screen — the create layer when no event is
     * configured. Reached through a **switch**, the leave has already run, so this lands the device in
     * **no event**; the confirmation named the event being left, and rescanning an invite rejoins.
     */
    fun onCancelJoin() = intent { pending.value = null }

    /**
     * Dismiss the switch confirmation *before* its leave, staying in the current event untouched. The
     * same one-line body as [onCancelJoin], deliberately kept distinct: after this change the two are
     * genuinely different acts — this one keeps the membership, that one ends with none.
     */
    fun onCancelSwitch() = intent { pending.value = null }

    /**
     * A create just minted [eventId] (capability `create-event`): route it into the **same**
     * pending-join gate a scanned QR uses — non-auto-confirmed — so the creator loads the event, picks a
     * capture-date cutoff, and confirms like any joiner. The `POST /events` already minted the event, so
     * the gate holds a real `eventId` and performs a real details load; provision happens on confirm.
     */
    fun onEventCreated(eventId: String, linkKey: String? = null) = intent { startPending(eventId, linkKey) }

    // The gate's async work runs INLINE within the orbit intent (not on a side scope) so each pending
    // transition reduces through the container's own pipeline deterministically. A modal join is fine
    // to serialize; a real fetch suspends here, yielding a Loading frame before the result.
    private suspend fun startPending(eventId: String, linkKey: String?) {
        // A fresh surface starts from the defaults — all on, the full event window. Seeding HERE rather
        // than in the reduction is what keeps the member's edits from being overwritten by every
        // subsequent reduction, and what stops a previous surface's choices leaking into this one.
        local.editForm { freshForm }
        pending.value = PendingJoin(eventId, JoinPhase.Loading, linkKey)
        loadInto(eventId)
    }

    /**
     * The event's `createdAt` **normalized** into a cutoff, falling back to **now** (capability
     * `photo-sharing`). A membership always carries a cutoff, so a missing or unparseable
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
            queries.loadJoinDetails(eventId, pending.value?.takeIf { it.eventId == eventId }?.linkKey)
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
            // `BackendEventDirectory.fetch` maps every backend answer, a transport failure included, to an outcome and
            // `toJoinLoad` is pure, so the bound lambda cannot throw — but `loadJoinDetails` is an injected
            // `suspend (String) -> JoinLoad` and nothing here can know that. It stays because the invariant
            // is one adapter change away from being false, and the cost of it being false is a screen no
            // one can leave. It IS covered: the seam is a constructor parameter, so a test injects a
            // throwing loader directly.
            if (pending.value?.eventId == eventId) {
                pending.update { it?.copy(phase = JoinPhase.LoadFailed) }
            }
            throw t
        }
        // The headless negative oracle (mirrors autoConfirm's abort line): a gate parked on a failed
        // details load shows a dialog, but a headless run has no one watching the
        // screen — without this line, `debug.log` shows only the HTTP `404` and the run reads as if
        // the link applied (the documented invented-UUID trap).
        if (load !is JoinLoad.Found) log("join gate: details load did not succeed for $eventId ($load)")
        val phase = when (load) {
            // `startsAt` is ALWAYS present on a successful load (the backend synthesizes one for legacy markers,
            // and the details source fails the load rather than invent one). Loaded details always open the
            // confirm surface; whether its confirm also raises the access dialog is read live
            // ([asksAccessOnJoin]), so a switch whose leave has just cleared the config needs no re-derivation.
            // A closed (or finished) event is refused before any choice is offered (capability `join-event`).
            is JoinLoad.Found -> if (load.completion.closed) {
                JoinPhase.Closed
            } else {
                JoinPhase.Detailed(
                    EventDetails(load.name, load.startsAt, load.endsAt, load.deletesAt),
                    JoinPhase.Detailed.Step.Ready,
                )
            }
            JoinLoad.NotFound -> JoinPhase.NotFound
            JoinLoad.Failed -> JoinPhase.LoadFailed
            JoinLoad.WrongLink -> JoinPhase.WrongLink
        }
        // Only apply if this fetch is still the active pending target (not cancelled/superseded).
        val keyId = (load as? JoinLoad.Found)?.keyId
        pending.update { if (it?.eventId == eventId) it.copy(phase = phase, eventKeyId = keyId) else it }
    }

    private suspend fun commit() {
        val p = pending.value ?: return
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action. Both carry a non-null name, startsAt,
        // endsAt AND deletesAt — so a commit can never reach `JoinEvent` without the floor, the ceiling,
        // and the retention deadline.
        val detailed = p.phase as? JoinPhase.Detailed ?: return
        if (detailed.step !in RETRYABLE_STEPS) return
        val event = detailed.event
        // What is committed is what the reduction RESOLVED — the same value the surface rendered.
        val form = local.value.form
        val range = resolveRange(form, event.startsAt, event.endsAt, null)
        // A join always carries a direction (capability `join-event`): with both switches off the confirm is
        // disabled on screen, and a confirm that arrives anyway joins nothing rather than a membership doing nothing.
        if (!range.commitEnabled) return
        val choice = JoinChoice(
            p.eventId, event.name, event.startsAt, event.endsAt, event.deletesAt,
            range.chosenFrom, range.chosenUntil, range.direction, form.saveToAlbum,
            linkKey = p.linkKey, eventKeyId = p.eventKeyId,
        )
        pending.value = p.copy(phase = JoinPhase.Detailed(event, JoinPhase.Detailed.Step.Committing))
        beginMembership()
        val commit = try {
            commands.commitJoin(choice)
        } catch (cancelled: CancellationException) {
            // Cancellation is teardown, not failure: rethrow before the repair below, or a cancelled
            // commit would rewrite the phase on its way out (the `StatusCountsPoller` shape).
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
            if (pending.value?.eventId == p.eventId) {
                if (config.value?.eventId == p.eventId) {
                    pending.value = null
                } else {
                    pending.value = p.copy(phase = JoinPhase.Detailed(detailed.event, JoinPhase.Detailed.Step.CommitFailed))
                }
            }
            // Rethrown, never swallowed: the container's `exceptionHandler` reports it at `Error`, which is
            // what reaches the crash reporter. That handler is also why rethrowing is safe here — without it
            // Orbit's re-throw would cancel the intent job and take every later command with it.
            throw t
        }
        if (commit == JoinCommit.Committed) {
            // Success: config flips present via ConfigSource → reduces to Joined; drop the overlay.
            if (pending.value?.eventId == p.eventId) pending.value = null
        } else if (pending.value?.eventId == p.eventId) {
            // The two failures land on DIFFERENT steps, because one is retryable and one is not
            // (capability `join-event`). A full event given the CommitFailed surface would offer a Retry
            // that fails identically every time, with nothing saying why.
            // A CLOSED event is final too, and carries no event facts worth keeping: its own phase, no Retry.
            pending.value = p.copy(phase = failedPhase(commit, detailed.event, refusal.value))
        }
    }

    /**
     * A membership BEGINS here, so its surface state starts here — in one place every new membership passes
     * through, a rejoin of the same event included (capability `sync-status`). A collector watching the config
     * could miss that: it is a StateFlow, and a leave and a rejoin of one event can conflate into A → A.
     */
    private suspend fun beginMembership() {
        local.setSettings(Owned(null, SettingsSurface.Closed))
        if (renameFlow.value.isTerminal) commands.resetRename()
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
        linkKey: String?,
    ) {
        val load = queries.loadJoinDetails(eventId, linkKey)
        if (load !is JoinLoad.Found) {
            log("autoJoin aborted: details load did not succeed for $eventId ($load)")
            return
        }
        val current = config.value
        if (current != null && current.eventId != eventId) commands.leave()
        // The auto-fired confirm uses the event's `startsAt` as the cutoff, unless the event link supplied
        // an explicit dev/test one (capability `photo-sharing`). Never an absent cutoff — the headless
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
        // (capability `event-album`). This is the ONE default that deliberately does NOT mirror the
        // interactive surface, where `RangeForm.saveToAlbum` starts ON: the cutoff and direction above
        // match their seeds, but a headless launch should do the minimal, side-effect-free thing, and
        // the link's explicit `saveToAlbum` already exercises album placement without a tap.
        val saveToAlbum = explicitSaveToAlbum ?: false
        beginMembership()
        val commit = commands.commitJoin(
            JoinChoice(
                eventId, load.name, load.startsAt, load.endsAt, load.deletesAt, cutoff, until, direction, saveToAlbum,
                linkKey = linkKey, eventKeyId = load.keyId,
            ),
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
 * How often the joined screen re-checks the wall clock (capability `sync-status`). One minute: the
 * dates line's finest unit is a minute ("ends in 40 min"), so a finer tick would buy nothing visible, and
 * nothing of the member's can upload before the start regardless.
 */
private const val NOT_STARTED_TICK_MILLIS = 60_000L

/** How long the transient invalid-link error stays on screen after it last appeared. */
private const val TRANSIENT_ERROR_MILLIS = 4_000L

/** How long the word on a sent report stays (capability `privacy-security`): long enough to read, then gone. */
private const val REPORT_NOTICE_MILLIS = 4_000L

/**
 * Whether confirming a join also raises iOS's photo-access dialog (capability `join-event`): no event is
 * configured, and access was never asked — the only state from which iOS can still raise it. From a
 * refusal a request is a silent no-op, so announcing a dialog there would be false; a switch's previous
 * event, while still configured, is not yet a join this rule speaks for.
 */
internal fun asksAccessOnJoin(config: EventConfig?, permission: GalleryAccess): Boolean =
    config == null && permission == GalleryAccess.NOT_DETERMINED

/**
 * What is shown while NO event is configured: the interactive join confirmation, or the create surface. Nothing in
 * it consults the health or the clock.
 */
private fun unjoinedLayer(
    pending: PendingJoin?,
    create: Creation,
    transient: ScreenMessage?,
    form: RangeForm,
    permission: GalleryAccess,
    network: NetworkNotice?,
    refusal: DeviceRefusal?,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?) -> ResolvedRange,
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
            range = event?.let { resolveAgainst(form, it.startsAt, it.endsAt, null) },
            // The same transient cell the create and joined layers read: a rejected link is rejected
            // wherever it arrives, including over an open join surface, and it touches the join not at all.
            notice = transient,
            // This rung is reached only with no event configured, so the permission alone decides.
            asksAccessOnJoin = asksAccessOnJoin(null, permission),
            // Join waits for a network; the screen names why (capability `join-event`).
            network = network,
        )
    }
    // One banner, one value. The TRANSIENT wins while it is showing: a create failure is sticky
    // until the next attempt, so a link scanned in between would otherwise be silently outranked by
    // an older complaint. When it self-clears, the sticky failure shows again. A missing network outranks both on
    // screen, but is carried beside them rather than instead of them, so the failure returns with the network
    // (capability `create-event`). A create in flight is not interrupted: it ends as any create ends.
    //
    // A phone the service refuses is told so in that same line as soon as the app learns of it (capability
    // `create-event`, "The front screen tells a refused phone before it tries"): below a failure, which for a refused
    // phone IS the refusal, and above the scan hint. Create stays available.
    val refused = refusal?.let(ScreenMessage::of)
    return when (val creation = create.status) {
        CreationStatus.InFlight -> Layer.CreatingEvent
        is CreationStatus.Failed ->
            Layer.CreateEvent(error = transient ?: creation.reason.message(refused), draft = create.draft, network = network)
        CreationStatus.Idle -> Layer.CreateEvent(error = transient ?: refused, draft = create.draft, network = network)
    }
}

/** The create surface's two inputs: the create request's status, and where its draft stands. */
private class Creation(val status: CreationStatus, val draft: CreateDraftSession)

/**
 * Which of the worlds the app is in — unjoined (the join gate or the create surface) or joined — and, for the
 * joined layer, which health rung. Config presence is the top rung: without a connected event there is nothing to
 * share. Once config is present the screen is ALWAYS the joined layer — permission and sync activity are moods of
 * the one-line status, never a hero-replacing gate.
 */
private fun reduceFrom(
    membership: Membership,
    interaction: Interaction,
    local: Local,
    nowCutoff: CaptureDate,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?) -> ResolvedRange,
): Layer {
    val (config, permission, snapshot, download, attested, access) = membership
    val network = NetworkNotice.of(access)
    val (pending, creation, renameStatus) = interaction
    val transient = local.transientError
    val form = local.form
    // Surface state belongs to the membership it was opened in; another membership reads it as closed / idle.
    val rename = Owned(local.renameOwner, renameStatus).forMembership(config?.eventId, RenameStatus.Idle)
    val reconfiguring = SettingsShown(
        surface = local.settings.forMembership(config?.eventId, SettingsSurface.Closed),
        askingToStopSharing = local.pendingWithdrawal.forMembership(config?.eventId, null) != null,
    )
    if (config == null) {
        val create = Creation(creation, local.createDraft)
        return unjoinedLayer(pending, create, transient, form, permission, network, membership.refusal, resolveAgainst)
    }
    val health = joinedHealth(membership, config, network, nowCutoff)
    // A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
    val pendingSwitch = pending?.let { PendingSwitch(it.eventId, it.phase) }
    // Where the event is in its life, for the dates line (capability `sync-status`). Informational only —
    // the health above is unchanged by it, and sync continues after the end in the backend grace window.
    val timing = eventTiming(config.startsAt, config.endsAt, nowCutoff)
    // The counts line rides ONLY on the two health rungs that read the numbers; every rung above them
    // means the numbers are unknown (not read yet), zero (no access collapses the gallery total) or beside
    // the point (not started, unverified) — and the one status line is what the member should read.
    val counts = (snapshot as? SyncStatus.Ready)
        ?.takeIf { health is SyncHealth.InSync || health is SyncHealth.Syncing }
        ?.let { syncCounts(it.progress, download, config.direction) }
    return joinedLayer(
        config, health, pendingSwitch, permission, JoinedFacts(timing, counts),
        rename, reconfiguring, transient, form, resolveAgainst, local.inviteKey,
    )
}

/**
 * The joined layer's one status line (capability `sync-status`, "One status line in a fixed priority"): the rungs in
 * order, over what the snapshot says on its own. Its own function because the ladder is one question, apart from which
 * layer [reduceFrom] builds.
 */
private fun joinedHealth(membership: Membership, config: EventConfig, network: NetworkNotice?, nowCutoff: CaptureDate): SyncHealth {
    val (_, permission, snapshot, download, attested, access) = membership
    // What the snapshot says on its own: not read yet, settled, or work remaining. The bottom of the ladder below,
    // and what decides whether a membership that neither shares nor receives may say so.
    val settled = when {
        // Joined but persisted state not read yet — a neutral first frame (the joined chrome still shows).
        snapshot is SyncStatus.Loading -> SyncHealth.Loading
        // The download arm has its OWN read-ness, and it must gate the health too. `syncHealth` below
        // hides an arrow when its counts are complete, and shows "Up to date" only when BOTH arrows are
        // hidden — so an un-read DownloadProgress, whose `downloaded` and `total` are both a placeholder
        // zero, hides the download arrow and can carry the whole screen to a settled check mark on its
        // own. Gating the upload side alone would relocate that defect rather than remove it: the next
        // member to join an event with foreign photos outstanding would meet it through this arm on
        // their first launch (`SNAPSYNC-14`, `SNAPSYNC-16`; capability `sync-status`).
        !download.read -> SyncHealth.Loading
        // Photos kept off mobile data wait while the phone is on a network the choice avoids (capability `mobile-data`):
        // from the device's CURRENT choice, so after turning mobile data back on the few transfers still holding the old
        // rule read as pending, not waiting (decision record `changes/archive/2026-10-04-mobile-data-for-photos`, D7).
        snapshot is SyncStatus.Ready ->
            syncHealth(snapshot.progress, download, heldForWifi = !membership.mobileData && access == NetworkAccess.Online(restricted = true))
        else -> SyncHealth.Loading
    }
    return when {
        // Neither shares nor receives (capability `sync-status`): once the snapshot is read and nothing is left in
        // either direction, that is the one thing worth saying — access, the network, the start and verification
        // all concern photos that no longer move. Work still draining in a switched-off direction is shown by the
        // ladder below, never masked (the reason above `syncHealth`).
        config.direction == Direction.Neither && settled == SyncHealth.InSync -> SyncHealth.Inactive
        config.direction == Direction.Neither && settled == SyncHealth.Loading -> SyncHealth.Loading
        // Missing permission is the sole attention state — the only reason contribution cannot run. It
        // outranks NotStarted because it is the only ACTIONABLE state, and the member must resolve it
        // BEFORE the event begins or they miss the start; hiding it behind the clock line would ambush
        // them with a permission prompt at the very moment the party starts. LIMITED is NOT here: a
        // partial grant is a working state (capability `photo-access`) — it falls through to
        // the snapshot-derived health exactly like GRANTED.
        !permission.grantsPhotoAccess -> SyncHealth.NeedsAccess(permission)
        // No usable network (capability `sync-status`, "One status line in a fixed priority"). Below access, which
        // decides what is shared and can be fixed offline; above everything else — before the start, received photos
        // cannot arrive either, and a missing network is the true cause of most failed verifications, so it outranks
        // the vaguer cannot-verify line by rank alone.
        network != null -> SyncHealth.NoNetwork(network)
        // The event has not begun. Outranks every snapshot-derived value because nothing of this member's
        // CAN be syncing yet — the cutoff floor guarantees it (`minPhotoDate >= startsAt > now`, and a
        // photo cannot be captured in the future) — so a snapshot line would say nothing true that this
        // does not say better. Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
        config.startsAt.at > nowCutoff -> SyncHealth.NotStarted
        // Uploads are gated on an attestation token, and we could not get one. Ranked BELOW permission and
        // BELOW NotStarted for the same reason: with no library access — or before the event begins —
        // nothing of this member's can upload anyway, so an unusable token is not yet their problem, and
        // two attention lines would only compete. Ranked ABOVE the sync progress, because "Syncing" would
        // be a lie: nothing can upload at all.
        // A definite refusal names its cause on the same rung (capability `sync-status`); no verdict names none.
        !attested -> SyncHealth.Unattested(membership.refusal)
        else -> settled
    }
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
    permission: GalleryAccess,
    facts: JoinedFacts,
    rename: RenameStatus,
    reconfiguring: SettingsShown,
    transient: ScreenMessage?,
    form: RangeForm,
    resolveAgainst: (RangeForm, EventStart, EventEnd?, CaptureCeiling?) -> ResolvedRange,
    inviteKey: String?,
): Layer.Joined {
    return Layer.Joined(
        membership = config,
        // Derived HERE and nowhere else (capability `manage-membership`, decision D3's surviving half):
        // one derivation feeds both the rendered QR and the share action, so they cannot drift.
        inviteUrl = config.inviteUrl(inviteKey),
        health = health,
        pendingSwitch = pendingSwitch,
        // The resting affordance, not an attention state (capability `photo-access`): a
        // partial grant's joined layer always offers the picker, whatever the health.
        canChoosePhotos = permission == GalleryAccess.LIMITED,
        timing = facts.timing,
        counts = facts.counts,
        closed = config.closed,
        // Only once this member is in sync is "who are we waiting for" the member's question (capability
        // `sync-status`); only while the event is open is anyone still settling.
        waiting = config.members?.takeIf {
            facts.timing == EventTiming.Ended && !config.closed && health == SyncHealth.InSync && it.waitingFor > 0
        },
        renameState = rename.toRenameState(),
        // The same transient cell the create layer's banner reads. A rejected link is rejected wherever
        // it arrives, so the message reaches whichever layer is showing rather than only one of them.
        notice = transient,
        // The settings surface, pre-filled and resolved against the MEMBERSHIP's own window — which is
        // the one deliberate divergence from the join gate: a legacy membership carrying no event end
        // bounds against its own ceiling, so a no-edit Save is idempotent rather than silently widening.
        // A closed event's settings are fixed (capability `manage-membership`): a surface left open when the close
        // lands gives way to the status.
        surface = if (reconfiguring.surface != SettingsSurface.Closed && !config.closed) {
            JoinedSurface.Reconfigure(
                form = form,
                range = resolveAgainst(form, config.startsAt, config.endsAt, config.maxPhotoDate),
                saveFailed = reconfiguring.surface == SettingsSurface.SaveFailed,
                askingToStopSharing = reconfiguring.askingToStopSharing,
            )
        } else {
            JoinedSurface.Status
        },
    )
}

// Shown tracks completeness (never lies about "everything up/received"); pulse tracks live activity
// (never fakes motion). Each arrow derives from ITS OWN COUNTS ALONE — this function does not read the
// membership's direction, and never force-hides: an opted-out direction contributes no work and so has a
// zero total — the upload total is 0 for a non-contributing membership (capability `photo-sharing`), and the
// download total is 0 for a membership that never reconciles (capability `receiving-photos`) — so the arrows
// agree with the direction because the counts already do.
//
// A mask would be harmful, not merely redundant. A force-hidden arrow can only ever conceal a MISMATCH between the
// direction contract and what the system is actually doing. Concealing that mismatch is exactly how a download-only
// membership uploaded its member's camera roll for a full release cycle while this screen read "In sync" (capability
// `background-upload`): the one surface that would have shown them an upload they never asked for was the surface
// that hid it. If the counts are right, the arrow is already right; if they are wrong, an arrow the member never
// asked for is the only signal anyone gets. The display must not assert a contract the system is not keeping.
private fun syncHealth(progress: SyncProgress, download: DownloadProgress, heldForWifi: Boolean): SyncHealth {
    // A held transfer is handed to the platform and so counts as in flight, but it is not RUNNING: no arrow pulses.
    val upload = arrowOf(shown = progress.synced < progress.total, pulsing = progress.pending > 0 && !heldForWifi)
    val downloadArrow = arrowOf(shown = download.downloaded < download.total, pulsing = download.inFlight > 0 && !heldForWifi)
    return if (upload == Arrow.HIDDEN && downloadArrow == Arrow.HIDDEN) {
        SyncHealth.InSync
    } else {
        SyncHealth.Syncing(upload = upload, download = downloadArrow, waitingForWifi = heldForWifi)
    }
}

/** What the joined layer says beside its health: the dates line's timing and the counts line. */
private class JoinedFacts(val timing: EventTiming, val counts: SyncCounts?)

// The counts line, from the SAME pairs `syncHealth` derives the arrows from, so the numbers and the arrows
// cannot disagree. A direction reads `Off` only when the member switched it off AND it has no work: the
// reason is the one above `syncHealth` — a display that hides work in a switched-off direction conceals
// exactly the mismatch the member most needs to see.
private fun syncCounts(progress: SyncProgress, download: DownloadProgress, direction: Direction): SyncCounts =
    SyncCounts(
        shared = if (!direction.includesUpload && progress.total == 0) {
            DirectionCount.Off
        } else {
            DirectionCount.Progress(done = progress.synced, total = progress.total)
        },
        received = if (!direction.includesDownload && download.total == 0) {
            DirectionCount.Off
        } else {
            DirectionCount.Progress(done = minOf(download.downloaded, download.total), total = download.total)
        },
    )

/**
 * The counts line as the screen showed it (capability `privacy-security`), for a bug report: `<done>/<total>` or `off`
 * per direction, taken from the reduced [UiState] itself rather than re-derived, because a report exists to catch the
 * screen disagreeing with the stores. Empty when no counts line was showing — not joined, or a status line in its place.
 */
internal fun shownCounts(state: UiState): Map<String, String> {
    val counts = (state.layer as? Layer.Joined)?.counts ?: return emptyMap()
    return mapOf(DiagnosticKeys.SHOWN_SHARED to counts.shared.shown(), DiagnosticKeys.SHOWN_RECEIVED to counts.received.shown())
}

private fun DirectionCount.shown(): String = when (this) {
    DirectionCount.Off -> "off"
    is DirectionCount.Progress -> "$done/$total"
}

private fun arrowOf(shown: Boolean, pulsing: Boolean): Arrow =
    if (!shown) Arrow.HIDDEN else if (pulsing) Arrow.PULSING else Arrow.STATIC

// Derive the invite link from the persisted config's eventId (the wire payload is eventId-only).
/**
 * The invite a member shares. An ENCRYPTED event's carries its key — only ever the key of the event its config names
 * (a key read for no encrypted membership is ignored); a plain event's carries none, in the form every build reads.
 */
private fun EventConfig.inviteUrl(key: String?): String =
    encodeEventUrl(EventLinkPayload(eventId, key = key.takeIf { keyId != null }))

private fun RenameStatus.toRenameState(): RenameState = when (this) {
    RenameStatus.Idle -> RenameState.Idle
    RenameStatus.InFlight -> RenameState.InFlight
    RenameStatus.Succeeded -> RenameState.Succeeded
    is RenameStatus.Failed -> RenameState.Failed(reason.message())
}

/**
 * The rename dialog's failure (capability `manage-membership`). Two reasons, because the port reports two:
 * the backend rejected the name, or everything else.
 *
 * There is deliberately no "this event no longer exists" message for the `404` that also arrives as
 * [RenameFailureReason.SERVER]. A `404` here is a single witness that the event is gone, and the
 * self-leave needs two (capability `manage-membership`); giving it copy would give it a meaning, and a meaning
 * invites acting on it. The standing foreground refresh reaches that verdict on its own terms.
 */
private fun RenameFailureReason.message(): ScreenMessage = when (this) {
    RenameFailureReason.INVALID_NAME -> ScreenMessage.RENAME_NAME_REFUSED
    RenameFailureReason.SERVER -> ScreenMessage.RENAME_FAILED
}

/**
 * A failed create in words. [refused] is the attestation's refusal, if it gave one: a create refused for this phone's
 * credential says why, and without a verdict — the attempt to verify got no answer — it is the unreachable server.
 */
private fun CreationFailureReason.message(refused: ScreenMessage?): ScreenMessage = when (this) {
    CreationFailureReason.INVALID_NAME -> ScreenMessage.CREATE_NAME_REFUSED
    CreationFailureReason.INVALID_WINDOW -> ScreenMessage.CREATE_DATES_REFUSED
    CreationFailureReason.UNVERIFIED -> refused ?: ScreenMessage.CREATE_FAILED
    CreationFailureReason.SERVER -> ScreenMessage.CREATE_FAILED
}

/**
 * The range the showing surface resolves and would count (capability `join-event`), or `null` when no
 * surface shows the count row — which renders only while sharing is on, so no photo-library read is made for
 * a row nobody sees.
 */
internal fun Layer.countedRange(): ResolvedRange? = when (this) {
    is Layer.JoiningEvent -> range.takeIf { form.shareOn }
    is Layer.Joined -> (surface as? JoinedSurface.Reconfigure)?.takeIf { it.form.shareOn }?.range
    else -> null
}

/** Whether the joined layer shows its settings, and whether the last change to them failed to save. */
internal enum class SettingsSurface { Closed, Open, SaveFailed }

/** The settings as the reduction reads them: open or not (and failed), and whether a withdrawal is being asked about. */
private data class SettingsShown(val surface: SettingsSurface, val askingToStopSharing: Boolean)

/**
 * One settings change, as the reconfigure command takes it (capability `manage-membership`): the membership in effect
 * with one thing changed. The bounds are the membership's own unless the change is to the range, so a switch never
 * moves the range it does not touch.
 */
internal data class SettingChange(
    val direction: Direction,
    val from: CaptureCutoff,
    val until: CaptureCeiling,
    val saveToAlbum: Boolean,
) {
    /**
     * Whether this change withdraws photos from the event, and so is asked about first: sharing turned off, or a
     * range that starts later or ends earlier than the one [inEffect]. Widening, and everything else, applies at once.
     * Canonical fixed-width UTC on both sides ⇒ lexicographic IS chronological.
     */
    fun withdrawsFrom(inEffect: SettingChange): Boolean = inEffect.direction.includesUpload &&
        (!direction.includesUpload || from.at > inEffect.from.at || until.at < inEffect.until.at)

    /** [config] carrying these settings — what the controls are seeded from. */
    fun appliedTo(config: EventConfig): EventConfig = config.copy(
        direction = direction, minPhotoDate = from, maxPhotoDate = until, saveToAlbum = saveToAlbum,
    )

    companion object {
        fun of(config: EventConfig) =
            SettingChange(config.direction, config.minPhotoDate, config.maxPhotoDate, config.saveToAlbum)
    }
}

/** The non-idempotent commands whose taps claim an in-flight slot (see `StatusContainerHost.guardedIntent`). */
private enum class Guarded { Create, Rename, SwitchLeave }

/**
 * Surface state that belongs to one membership (capability `sync-status`, "Membership-scoped surface state
 * belongs to the membership"; decision record `harden-seam-bug-classes`, D13): written with the event it was
 * opened for, and read back only while that event is the joined one. A change of membership therefore closes it
 * by construction — no exit path (leave, switch, self-leave, config clear) has to remember to (B7).
 */
internal data class Owned<T>(val eventId: String?, val value: T) {
    /** [value] while [eventId] is the [joined] event — or names none (a latch set from outside, e.g. forged). */
    fun forMembership(joined: String?, otherwise: T): T = if (eventId == null || eventId == joined) value else otherwise
}

/**
 * The phase a commit that did not land shows (capability `join-event`): a full event and a closed one are walls no
 * retry moves, so neither offers one; anything else may heal and keeps the Retry.
 */
private fun failedPhase(commit: JoinCommit, event: EventDetails, refusal: DeviceRefusal?): JoinPhase = when (commit) {
    JoinCommit.Closed -> JoinPhase.Closed
    JoinCommit.WrongLink -> JoinPhase.WrongLink
    JoinCommit.Full -> JoinPhase.Detailed(event, JoinPhase.Detailed.Step.EventFull)
    // Refused for this phone's credential: told why when the service said why (capability `join-event`, "A refused phone
    // is told why it cannot join"). Without a verdict — the attempt to verify got no answer — it is the ordinary failure.
    JoinCommit.Unverified if refusal != null ->
        JoinPhase.Detailed(event, JoinPhase.Detailed.Step.DeviceRefused, ScreenMessage.of(refusal))
    else -> JoinPhase.Detailed(event, JoinPhase.Detailed.Step.CommitFailed)
}

/** The steps a confirm may be taken from: the first, and each failure a Retry may heal. */
private val RETRYABLE_STEPS =
    setOf(JoinPhase.Detailed.Step.Ready, JoinPhase.Detailed.Step.CommitFailed, JoinPhase.Detailed.Step.DeviceRefused)

/** The membership and what its health is read from — observed read-models (see [StatusSources]). */
private data class Membership(
    val config: EventConfig?,
    val permission: GalleryAccess,
    val sync: SyncStatus,
    val download: DownloadProgress,
    val attested: Boolean,
    /** What the member is told about the network — it reaches every layer, not only the joined one. */
    val network: NetworkAccess,
    /** The device's mobile-data choice (capability `mobile-data`): the menu's switch, and the waiting line's cause. */
    val mobileData: Boolean,
    /** Why the service refused this phone at the latest attempt to verify it, if it did (capability `privacy-security`). */
    val refusal: DeviceRefusal? = null,
)

/** The observed outcomes of what the member started: the join gate, a create, a rename — and a refused build. */
private data class Interaction(
    val pending: PendingJoin?,
    val creation: CreationStatus,
    val rename: RenameStatus,
    val versionRefusal: VersionRefusal?,
)

/**
 * The presentation-owned cells, as one value: what the container itself writes, as distinct from the read-models
 * it observes. Opening a confirmation or editing the form touches no port and calls no command, so these are
 * container-local intents that reduce and nothing more — state rather than screen-local `remember`s because the
 * screen SHOWS them (capability `sync-status`).
 */
private data class Local(
    /**
     * The transient invalid-link error (capability `join-event`). An INPUT to the reduction, not a value beside it:
     * the create screen renders ONE banner, so the create state carries one error value and this is one of its
     * two causes.
     */
    val transientError: ScreenMessage? = null,
    /** What is drawn OVER the current layer (see [Overlays]). */
    val overlays: Overlays = Overlays(),
    /**
     * The member's uncommitted choices. ONE cell, because the two surfaces that ask for them are mutually exclusive
     * by construction — the join gate needs config ABSENT, the settings surface needs it PRESENT — and each open
     * re-seeds, so nothing can leak from one surface into the other.
     */
    val form: RangeForm,
    /** Where the create screen's draft stands against the app's foreground life (capability `create-event`). */
    val createDraft: CreateDraftSession = CreateDraftSession(),
    /**
     * Whether the joined layer is showing its settings surface (`manage-membership` D4: opening is client-side
     * navigation that touches no port). OWNED by the membership it was opened in (see [Owned]): a switch, a leave
     * or a fresh join cannot carry it into the next membership, whichever exit path ran (B7).
     */
    val settings: Owned<SettingsSurface> = Owned(null, SettingsSurface.Closed),
    /**
     * A settings change that would withdraw photos from the event, held while the member is asked whether to stop
     * sharing them (capability `manage-membership`). Owned like [settings], so it never outlives its membership.
     */
    val pendingWithdrawal: Owned<SettingChange?> = Owned(null, null),
    /** The settings the last change applied — what the next change builds on (see `settingsInEffect`). */
    val lastApplied: Owned<SettingChange?> = Owned(null, null),
    /** The event the last rename was fired for: a rename result for an event no longer joined reads as Idle. */
    val renameOwner: String? = null,
    /** The joined event's invite key, when it is encrypted — copied from [StatusSources.inviteKey], shown nowhere. */
    val inviteKey: String? = null,
    /**
     * The shareable count for whichever surface is showing a range (capability `join-event`), computed over the
     * query bundle and reduced into the range. It starts Unavailable (no row) rather than Counting: a count is only
     * "being computed" once one has been asked for.
     */
    val shareCount: ShareCount = ShareCount.Unavailable,
    /** The menu's last mobile-data flip could not be saved (capability `mobile-data`); cleared by the next flip or a close. */
    val mobileDataNotSaved: Boolean = false,
    /**
     * The open report sheet was opened from "Report this" beside a refusal (capability `privacy-security`), so its report
     * carries the refused verification's facts. Kept out of [Overlays]: it is how the sheet was opened, not what is shown.
     */
    val verifiedReport: Boolean = false,
)

/**
 * [combine] over thirteen flows, typed. The library's typed overloads stop at five, and nesting them is not the same
 * thing: each level is a stage of its own, so one synchronous change to cells in different levels reaches the
 * screen as several emissions rather than one. The casts are positional and fixed by this signature.
 */
@Suppress("UNCHECKED_CAST", "LongParameterList")
private fun <A, B, C, D, E, F, G, H, I, J, K, L, M, R> combineFlat(
    a: Flow<A>, b: Flow<B>, c: Flow<C>, d: Flow<D>, e: Flow<E>, f: Flow<F>, g: Flow<G>,
    h: Flow<H>, i: Flow<I>, j: Flow<J>, k: Flow<K>, l: Flow<L>, m: Flow<M>,
    transform: (A, B, C, D, E, F, G, H, I, J, K, L, M) -> R,
): Flow<R> = combine(listOf(a, b, c, d, e, f, g, h, i, j, k, l, m)) { v ->
    transform(
        v[0] as A, v[1] as B, v[2] as C, v[3] as D, v[4] as E, v[5] as F, v[6] as G,
        v[7] as H, v[8] as I, v[9] as J, v[10] as K, v[11] as L, v[12] as M,
    )
}

private fun MutableStateFlow<Local>.editOverlays(edit: (Overlays) -> Overlays) = update { it.copy(overlays = edit(it.overlays)) }

private fun MutableStateFlow<Local>.editForm(edit: (RangeForm) -> RangeForm) = update { it.copy(form = edit(it.form)) }

private fun MutableStateFlow<Local>.setSettings(settings: Owned<SettingsSurface>) =
    update { it.copy(settings = settings) }

/** A rename outcome the screen has yet to clear — a fresh surface must not read it as its own. */
private val RenameStatus.isTerminal: Boolean get() = this is RenameStatus.Succeeded || this is RenameStatus.Failed
