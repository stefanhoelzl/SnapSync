package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportDestination
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.time.Timestamp
import app.snapsync.query.RequestStatus
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.MobileDataState
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDateTime
import org.orbitmvi.orbit.OrbitContainer
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.orbitContainer

class StatusContainerHost(
    // Every read-model this container reduces over (see [StatusSources]). Bundled because they are one
    // KIND of thing — values observed and folded into `UiState` — while what the container INVOKES
    // ([commands], [queries]) and what it EMITS ([StatusDiagnostics]) stay separate.
    sources: StatusSources,
    private val scope: CoroutineScope,
    // The lane presentation reduces on — the CPU lane (`docs/architecture.md`, "Concurrency and failure"): Orbit's event
    // loop, kept off the composition lane so a blocked platform call there never freezes the screen. Named by the
    // composition, never left to Orbit's default, so a host that must see every task (the control channel's JVM host)
    // can hand its own.
    private val reduction: CoroutineDispatcher,
    // Supplies "now" as a cutoff string and converts a local pick.
    // Injected — with NO default: a default would have to read the system clock here, which the
    // through-ports law forbids. Production wires
    // the `Clock` port (now, and the zone read once); tests pass a fixed instant and zone.
    private val cutoffFormatter: CutoffFormatter,
    // The user-tap **command bundle** (`docs/architecture.md`, "Commands cross one door"):
    // leave / create / commitJoin / share / requestAccess / openSettings — `model/` vocabulary whose
    // live instance is built only in `compose/` (`AppCore.userCommands`) — this container fires
    // commands solely through it and never references a feature command (or `ports/`, or an entry point)
    // directly; the armed presentation gate enforces the import law. Required: a host states every
    // command it wires, so a forgotten one is a compile error rather than an inert tap.
    //
    // NB the share-range rule is applied on the far side of the `commitJoin` command, by the join's use case — this
    // container passes the chosen range through raw, so no entry path can reach a membership by forgetting it here.
    private val commands: UserCommands,
    // The screen's lookups (`docs/patterns.md`, "Lookups"): reads this container asks and reduces on, each read on the
    // composition lane, never on the thread that asked. How many of the member's photos a range would share, `null`
    // when no count can be stated…
    private val shareableCount: ScreenLookup<CaptureRange, Int?>,
    // …and the join gate's read of an invite's event.
    private val joinDetails: ScreenLookup<InviteLink, JoinDetails>,
    // The two out-channels (see [StatusDiagnostics]): the dev-path log and the intent-error seam.
    diagnostics: StatusDiagnostics,
    // Where a bug report goes on this build — a constant the composition states,
    // carried on every `UiState` so the sheet says it. Defaulted to the distributed build's answer.
    private val reportDestination: ReportDestination = ReportDestination.DEVELOPER,
    // Which build this is — the app menu's footer, a constant the composition states.
    private val build: BuildLabel = BuildLabel.UNKNOWN,
    // What the event album holds on this phone, read from the photo library: the composition reads
    // it once and the surfaces' album note follows. Defaulted to the iPhone's.
    private val albumContents: AlbumContents = AlbumContents.SharedAndReceived,
) : OrbitContainerHost<ScreenModel, ScreenModel, Nothing> {

    // The bundles are unpacked into the names the body already uses. Grouping happens at the boundary,
    // where a caller has to read it; inside, each source keeps the name that says what it is.
    private val syncSource = sources.sync
    private val permission = sources.permission
    private val membership = sources.membership
    private val creationStatus = sources.creation
    private val invites = sources.invites
    private val downloadSource = sources.download
    private val attested = sources.verification.attested
    private val refusal = sources.verification.refusal
    private val attestFault = sources.verification.fault
    private val serviceFault = sources.verification.service

    // The attestation's verdict and the service's condition travel as one input, so the reduction below keeps its arity.
    private val trust: Flow<Trust> =
        combine(attested, refusal, attestFault, serviceFault) { a, r, f, s -> Trust(a, r, f, s) }
    private val pending = sources.pending
    private val versionRefusal = sources.build.refusal
    private val network = sources.network
    private val mobileDataQuery = sources.mobileData

    /** The choice the menu's switch shows: read as the screen is built, then moved only by a change's answer. */
    private val mobileData = MutableStateFlow(mobileDataQuery.current() == MobileDataChoice.ALLOWED)
    private val requests = sources.requests

    /** The ids of this screen's requests. */
    private val ids = RequestIds()
    private val renameFlow: StateFlow<RequestStatus<RenameResult>> = requests.rename
    private val store = sources.build.store
    private val foreground = sources.foreground
    private val keyPresence = sources.eventKey.presence
    private val inviteRead = sources.eventKey.invite

    private val log = diagnostics.log
    private val onIntentError = diagnostics.onIntentError

    /** Resolves a form against an event window, and seeds a fresh one ([RangeResolver]). */
    private val ranges = RangeResolver(cutoffFormatter, albumContents)

    // Declared here rather than beside their use because `container`'s initializer reduces over it:
    // a property initialized later is null at that moment.
    private val local = MutableStateFlow(
        Local(
            form = freshForm,
            create = CreateDraft.fresh(CreateDraftSession(), cutoffFormatter.nowToTheMinute()),
            // The key's presence as it stands, so the first state already says whether it is lost.
            keyPresence = keyPresence.value,
        ),
    )

    /** How the screen's parts run their work on this container: as intents, or as guarded ones. */
    private val runner = object : IntentRunner {
        override fun intent(block: suspend () -> Unit): Job = this@StatusContainerHost.intent { block() }

        override fun guarded(command: Guarded, block: suspend () -> Unit) = guardedIntent(command, block)

        override fun current(): ScreenModel = container.stateFlow.value
    }

    /** The screen's own cells, shared by its parts. */
    private val cells = ScreenCells(local, pending, ids, runner, log, scope)

    /** The join gate ([JoinGate]): an opened invite, the event it names, a first join or a switch. */
    private val gate = JoinGate(
        cells,
        GateReads(membership, permission, keyPresence, refusal, attestFault),
        ranges,
        commands,
        requests,
        joinDetails,
        scope,
    )

    /** The event's settings, applied as they change ([SettingsChanges]). */
    val settings: SettingsChanges =
        SettingsChanges(cells, membership, commands, requests, cutoffFormatter, albumContents, scope)

    /** The member's edits to the participation form ([FormEdits]). */
    val form: FormEdits = FormEdits(cells, membership, settings, ranges)

    /** The conversions from the domain into what the screen shows (see [Display]). */
    private val display = Display(cutoffFormatter, ::resolveRange)

    /** The create form's own taps ([CreateFlow]). */
    val create: CreateFlow = CreateFlow(cells, display, cutoffFormatter, commands, creationStatus, gate)

    /** The range calendars ([RangePicker]). */
    val picker: RangePicker = RangePicker(cells, display, cutoffFormatter, form)

    /** The three photo-access taps ([AccessAsks]). */
    val access: AccessAsks = AccessAsks(runner, commands)

    /** A bug report and the word on what became of it ([ReportFlow]). */
    private val report = ReportFlow(cells, commands, requests, scope)

    /** What is drawn over — or instead of — the current layer ([SurfaceCommands]). */
    val surfaces: SurfaceCommands = SurfaceCommands(cells, commands, mobileDataQuery, mobileData, settings, report)

    /** Create a new event ([CreateFlow.onCreateEvent]). */
    fun onCreateEvent(name: String, startsAt: LocalDateTime, endsAt: LocalDateTime, openUntil: OpenUntil? = null) =
        create.onCreateEvent(name, startsAt, endsAt, openUntil)

    /** Send the diagnostic dump with the member's note, from the surface named [screen][ReportFlow.send]. */
    val onSendDiagnostics: (String, String) -> Unit = { note, screen -> report.send(note, screen) }

    /** The non-idempotent commands claimed while their intent runs — see [guardedIntent]. */
    private val inFlight = MutableStateFlow<Set<Guarded>>(emptySet())

    /** An untouched surface's choices on this phone: all on, the album included. */
    private val freshForm: RangeForm get() = ranges.freshForm

    /**
     * Resolve a form against an event window, in the DEVICE's zone.
     *
     * The join gate and the settings surface reach a window by different roads — one off the loaded
     * phase, one off the persisted membership — and those roads genuinely differ. From here down the
     * rules are identical, and a range rule that held on one surface and not the other would be a bug
     * nobody would ever see.
     */
    private fun resolveRange(form: RangeForm, startsAt: EventStart, endsAt: EventEnd): ResolvedRange =
        ranges.resolve(form, startsAt, endsAt, local.value.shareCount)

    /**
     * "Now", re-emitted every minute **only** while the joined event has not ended: the not-started line and the dates
     * line's countdown are the two things it moves.
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
    private val nowTick: Flow<Timestamp> =
        membership
            .map { held -> held?.let { it.event.range } }
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

    override val container: OrbitContainer<ScreenModel, ScreenModel, Nothing> =
        scope.orbitContainer(
            // All seams hold their current truth synchronously, so the first state the screen can ever
            // render derives from real values — never a guess or a placeholder.
            initialState = render(
                Observed(
                    membership.value,
                    permission.value,
                    syncSource.value,
                    downloadSource.value,
                    attested.value,
                    network.access.value,
                    mobileData.value,
                    refusal.value,
                    attestFault.value,
                    serviceFault.value,
                    inviteRead.invite(),
                ),
                Interaction(pending.value, creationStatus.value, renameFlow.value, versionRefusal.value),
                local.value,
                cutoffFormatter.nowCutoff(),
            ),
            // The container SURVIVES a throwing intent — and this handler is the
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
                eventLoopDispatcher = { reduction }
                exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                    // Nothing catches an InvalidProgramState: rethrown, it ends the process.
                    when (throwable) {
                        is InvalidProgramState -> throw throwable
                        else -> onIntentError(throwable)
                    }
                }
            },
        ) {
            intent {
                // An invite the app was opened with waits in the core until a screen can open it: from this
                // container's start on, it opens — and is told opened, so it no longer waits.
                invites.filterNotNull().collect { invite ->
                    onInvite(invite)
                    commands.inviteOpened.fire(invite)
                }
            }
            intent {
                // Each new value reduces straight to a UI state. The only clock-driven input is `nowTick`, and
                // it runs ONLY while an event has not ended (see above) — every other re-emission is a real
                // source change or a presentation-owned cell's.
                combineFlat(
                    membership, permission, syncSource, downloadSource, trust, network.access, mobileData,
                    pending, creationStatus, renameFlow, versionRefusal,
                    local, nowTick,
                ) { joined, permission, sync, download, trust, access, mobileData, pending, creation, rename, refusal, local, now ->
                    render(
                        Observed(
                            joined, permission, sync, download, trust.attested, access, mobileData,
                            trust.refusal, trust.attestFault, trust.serviceFault, inviteRead.invite(),
                        ),
                        Interaction(pending, creation, rename, refusal),
                        local,
                        now,
                    )
                }.collect { model -> reduce { model } }
            }
            // Whether the joined event's key is held: a lost one is the joined screen's status line until a reopened invite
            // gives it back, and a change of it re-reads the invite an encrypted event offers only with its key held.
            intent {
                keyPresence.collect { presence -> local.update { it.copy(keyPresence = presence) } }
            }
            // The create draft follows the app's returns to the foreground.
            intent {
                foreground.collect { back ->
                    val now = cutoffFormatter.nowToTheMinute()
                    local.update { it.copy(create = it.create.afterReturn(back.count, back.awayFor, now)) }
                }
            }
            // The draft belongs to one visit of the create flow: leaving it for anything else —
            // a join, the joined layer — drops it, so a later visit starts afresh.
            intent {
                container.stateFlow
                    .map { it.ui.layer is Layer.CreateEvent || it.ui.layer == Layer.CreatingEvent }
                    .distinctUntilChanged()
                    .collect { inCreateFlow ->
                        if (!inCreateFlow) {
                            val now = cutoffFormatter.nowToTheMinute()
                            local.update { it.copy(create = CreateDraft.fresh(it.create.session, now)) }
                        }
                    }
            }
            // While the create form shows an untouched range, its start follows the clock minute by minute, until the
            // host chooses the range.
            intent {
                container.stateFlow
                    .map { it.ui.layer is Layer.CreateEvent && !local.value.create.rangeTouched }
                    .distinctUntilChanged()
                    .collectLatest { following ->
                        while (following) {
                            val now = cutoffFormatter.nowToTheMinute()
                            local.update { it.copy(create = it.create.followNow(now)) }
                            delay(cutoffFormatter.millisToNextMinute())
                        }
                    }
            }
            // A missing network came back (without a network, the join screen waits for one):
            // a join whose details could not load loads them now, untapped. Only on that return — a load that failed
            // while the network was there (an unreachable server) still waits for the member's Retry.
            intent {
                network.returned.collect {
                    if (pending.value?.phase == JoinProgress.LoadFailed) onRetryLoad()
                }
            }
            // The shareable count follows the range the showing surface resolves, and the grant (a late
            // first-join grant resolves the count). `collectLatest`: a newer range cancels an older count.
            intent {
                combine(container.stateFlow.map { it.shown.countedRange }, permission) { range, grant ->
                    range?.let { CountKey(it.chosen, grant) }
                }
                    .distinctUntilChanged()
                    .collectLatest { key ->
                        if (key == null) return@collectLatest
                        local.update { it.copy(shareCount = ShareCount.Counting) }
                        // No count — no range, or none the lookup can state — is no row, never a zero.
                        val count = key.range?.let { shareableCount.ask(it) }?.let { ShareCount.Ready(it) }
                            ?: ShareCount.Unavailable
                        local.update { it.copy(shareCount = count) }
                    }
            }
        }

    /** What the count is recomputed on — the bounds and the grant, never the count itself. */
    private data class CountKey(val range: CaptureRange?, val grant: PhotoGrant)

    /** What the screen shows: the display half of the container's [ScreenModel]. */
    val screen: StateFlow<UiState> = Displayed { container.stateFlow }

    /**
     * One UI state from the observed inputs, the presentation-owned cells and "now".
     *
     * A refused build outranks everything, config-absent included: it makes no
     * successful metadata call at all, so every other layer would render something untrue — a joined event that is
     * not syncing, a create that cannot succeed, a join that cannot commit. Its layer joins the refusal with this
     * build's store link, a constant, which is why it is assembled here rather than ranked inside [reduceFrom].
     */
    private fun render(observed: Observed, interaction: Interaction, local: Local, now: Timestamp): ScreenModel {
        val refused = interaction.versionRefusal
        val reduced = if (refused != null) {
            Reduced(Layer.UpdateRequired(minimumVersion = refused.minimumVersion, store = store?.name))
        } else {
            reduceFrom(observed, interaction, local, now, display)
        }
        val ui = UiState(
            reduced.layer,
            local.overlays.maskedFor(reduced.layer),
            reportDestination.shown,
            build.shown,
            MobileDataState(on = observed.mobileData, notSaved = local.mobileDataNotSaved),
        )
        // The count row renders only while sharing is on, so no photo-library read is made for a row nobody sees.
        val counted = reduced.range?.takeIf { local.form.shareOn }
        return ScreenModel(
            ui,
            Shown(store = store.takeIf { refused != null }, range = reduced.range, countedRange = counted),
        )
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
        commands.leave.fire(Unit)
    }

    /**
     * Share the event's invite link (the joined-layer share action). Hands the current invite URL
     * to the injected platform share; fire-and-forget — no result is observed, and `UiState` is
     * unaffected (the system share UI is presented over the screen, not part of it). Inert when no
     * event is configured (no URL) or no real share is bound (the no-op default).
     */
    // The invite URL is read off the state the reduction already derived, so the shared link is
    // byte-identical to the QR being rendered rather than a second derivation that could drift.
    fun onShareInvite() = intent {
        (state.ui.layer as? Layer.Joined)?.let { joined ->
            joined.inviteUrl?.let { commands.platform.share.fire(ShareRequest(it, joined.membership.name)) }
        }
    }

    /**
     * Open the build's store page from the update-required screen.
     *
     * The URL is read from the CURRENT state rather than taken from the caller, exactly as
     * [onShareInvite] reads the invite URL: the screen's contract is that a build carrying no store URL
     * renders no button, and reading it here means the container cannot be asked to open one the state
     * does not hold.
     */
    fun onOpenAppStore() = intent {
        state.shown.store?.let { commands.platform.openLink.fire(AppLinkRequest.StorePage(it)) }
    }

    /**
     * Rename the joined event, confirmed on the heading's rename dialog.
     * Delegates to the injected [UserCommands.rename] with [eventId] — the event the dialog was opened
     * for — so a switch that landed mid-edit makes the use-case a no-op rather than renaming a different
     * event. The outcome arrives on the rename status under the request's id and the new name via the config
     * read-model. Unlike [onReconfigure], this writes the SHARED event, but it crosses the same one door. One rename
     * at a time: the guard is held until this one is answered.
     */
    fun onRenameEvent(eventId: String, name: String) = guardedIntent(Guarded.Rename) {
        // The screen hands back the id it was shown; one that names no event renames nothing.
        val event = EventId.parse(eventId) ?: return@guardedIntent
        val id = ids.next()
        commands.rename.fire(RenameRequest(id, event, name))
        local.update { it.copy(renameOwner = event, renameRequest = id) }
        renameFlow.answerTo(id)
    }

    /** The screen has consumed the rename's answer: the next rename starts from none. */
    fun onRenameStatusConsumed() = intent { local.update { it.copy(renameRequest = null) } }

    /** An invite the app was opened with, read at the boundary ([JoinGate.onInvite]). */
    fun onInvite(read: InviteLinkVerdict): Job = gate.onInvite(read)

    /** Retry the details fetch after a transient load failure. */
    fun onRetryLoad(): Job = gate.onRetryLoad()

    /** Confirm a first join ([JoinGate.onConfirmJoin]). */
    fun onConfirmJoin(): Job = gate.onConfirmJoin()

    /** Confirm a switch: the leave, and nothing else ([JoinGate.onConfirmSwitch]). */
    fun onConfirmSwitch() = gate.onConfirmSwitch()

    /** Retry a failed commit. */
    fun onRetryJoin(): Job = gate.onRetryJoin()

    /** Discard the pending join. */
    fun onCancelJoin(): Job = gate.onCancelJoin()

    /** Dismiss the switch confirmation before its leave. */
    fun onCancelSwitch(): Job = gate.onCancelSwitch()

    /**
     * Run [run] as an intent unless [command] is already in flight — a non-idempotent command is in flight before it
     * first suspends (decision record `harden-seam-bug-classes`, D13). The claim is taken HERE, synchronously in the
     * tapping thread, before any intent is launched — two taps before the screen recomposes launch one command, not
     * two — and released when the intent's body returns.
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
}

/**
 * How often the joined screen re-checks the wall clock. One minute: the
 * dates line's finest unit is a minute ("ends in 40 min"), so a finer tick would buy nothing visible, and
 * nothing of the member's can upload before the start regardless.
 */
private const val NOT_STARTED_TICK_MILLIS = 60_000L
