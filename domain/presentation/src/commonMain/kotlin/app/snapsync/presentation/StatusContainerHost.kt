package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.InviteFact
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RenameResult
import app.snapsync.domain.membership.SettingsResult
import app.snapsync.domain.membership.ShareChoice
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.report.ReportDestination
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.time.Timestamp
import app.snapsync.presentation.range.EventRange
import app.snapsync.presentation.range.RangeEdit
import app.snapsync.presentation.range.after
import app.snapsync.presentation.range.validUntil
import app.snapsync.query.RequestStatus
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.MobileDataState
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.SwitchTo
import app.snapsync.ui.state.UiState
import app.snapsync.ui.state.step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var maintenanceReload: Job? = null
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
    private var transientErrorClear: Job? = null
    private var reportNoticeClear: Job? = null

    /** The non-idempotent commands claimed while their intent runs — see [guardedIntent]. */
    private val inFlight = MutableStateFlow<Set<Guarded>>(emptySet())

    /**
     * The settings' changes, in the order they were made: changes made one after another apply in order, the last
     * one standing. Orbit runs intents concurrently, so a confirm, the next switch or
     * a close could overtake a change still saving; each settings act is enqueued HERE, synchronously in the tapping
     * thread, and one consumer runs them one at a time. Started lazily on the container's scope.
     */
    private val settingsQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val settingsWorker by lazy {
        scope.launch {
            // Never closed: the worker lives as long as the container's scope, so it ends by cancellation only.
            while (true) {
                val act = settingsQueue.receive()
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
    private val freshForm: RangeForm get() = RangeForm(albumContents = albumContents)

    /**
     * Resolve a form against an event window, in the DEVICE's zone.
     *
     * The join gate and the settings surface reach a window by different roads — one off the loaded
     * phase, one off the persisted membership — and those roads genuinely differ. From here down the
     * rules are identical, and a range rule that held on one surface and not the other would be a bug
     * nobody would ever see.
     */
    private fun resolveRange(
        form: RangeForm,
        startsAt: EventStart,
        endsAt: EventEnd,
    ): ResolvedRange {
        val windowStart = cutoffFormatter.toLocal(startsAt)
        val windowEnd = cutoffFormatter.toLocal(endsAt)
        val resolved = form.resolve(
            windowStart = windowStart,
            windowEnd = windowEnd,
            nowLocal = cutoffFormatter.nowLocal(),
            nowAvailable = nowWithinWindow(cutoffFormatter.nowCutoff(), startsAt.at, endsAt.at),
            toCutoff = cutoffFormatter::toCutoff,
            shareCount = local.value.shareCount,
        )
        val chosen = CaptureRange.of(resolved.chosenFrom, resolved.chosenUntil, startsAt, endsAt)
        return resolved.copy(chosen = (chosen as? CaptureRangeVerdict.Ok)?.range)
    }

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
                        val count = try {
                            key.range?.let { shareableCount.ask(it) }?.let { ShareCount.Ready(it) }
                                ?: ShareCount.Unavailable
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A failed read is no count, not a crash: the row is omitted.
                            log("shareable count failed: ${e.message}")
                            ShareCount.Unavailable
                        }
                        local.update { it.copy(shareCount = count) }
                    }
            }
        }

    /** The conversions from the domain into what the screen shows (see [Display]). */
    private val display: Display get() = Display(cutoffFormatter, ::resolveRange)

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
     * Flash the transient invalid-link error: a link arrived that the decoder
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
     * Create a new event with [name], starting at [startsAt]. Delegates to the
     * injected [EventCreator] (fire-and-forget): it mints the event and, on success, provisions it
     * through the same path a scanned QR uses (config goes present, the reduction leaves the create
     * layer). Permission is not consulted here — a missing grant surfaces afterward via
     * `PermissionBlocked`. The in-flight and failure outcomes arrive back through the creation status read-model;
     * nothing is reduced here.
     *
     * [startsAt] arrives as the screen's **local** wall-clock pick and is converted here, through the same
     * [CutoffFormatter] the join surface uses — so the app has exactly one origin of "now" and one
     * local→UTC conversion, and `:ui:screens` stays free of any clock or timezone knowledge. [openUntil] is the day
     * guests may join until, for an event created after its end (the back-dated sheet's choice); `null` otherwise.
     */
    fun onCreateEvent(name: String, startsAt: LocalDateTime, endsAt: LocalDateTime, openUntil: OpenUntil? = null) =
        guardedIntent(Guarded.Create) {
            val id = ids.next()
            local.update { it.copy(createRequest = id) }
            commands.create.fire(
                CreateRequest(
                    id,
                    name,
                    EventStart(cutoffFormatter.toCutoff(startsAt)),
                    EventEnd(cutoffFormatter.toCutoff(endsAt)),
                    openUntil,
                    cutoffFormatter.zoneId(),
                ),
            )
            // The guard is held until the create is answered: one at a time, or a second tap mints a second event.
            // A create that minted nothing is told on the form, from the status; only a minted event opens the gate.
            val created = creationStatus.answerTo(id)
            local.update { it.copy(createRequest = null) }
            if (created is CreateResult.Created) eventCreated(created.eventId, created.key)
        }

    /** The create form's own taps: its name as typed, and Create. */
    val create: CreateCommands = CreateCommands()

    inner class CreateCommands internal constructor() {
        /**
         * The name as typed so far. Applied at once rather than as an intent, so a Create tapped right after the last
         * keystroke submits it.
         */
        fun onName(name: String) = local.update { it.copy(create = it.create.copy(name = name)) }

        /**
         * Create the event the form describes — inert unless the draft may be created: complete, inside the window. A
         * range already over when Create is tapped first asks how long guests have to join (decision record
         * `changes/back-dated-events`, D9); the answer, [onKeepOpenFor], creates it.
         */
        fun onConfirm() {
            val draft = local.value.create
            val end = display.creatableEnd(draft) ?: return
            if (cutoffFormatter.toCutoff(end) < cutoffFormatter.nowCutoff()) {
                local.update { it.copy(create = it.create.copy(askingHowLong = true)) }
                return
            }
            onCreateEvent(draft.name, draft.range.from, end)
        }

        /**
         * The back-dated sheet's answer: create the event the form describes, guests joining for [days] more days. Inert
         * unless the sheet is asking and [days] is one of its choices — a tap that outlived the sheet creates nothing.
         * The sheet closes either way, so a create that fails returns to the form and the next Create asks again.
         */
        fun onKeepOpenFor(days: Int) {
            val draft = local.value.create
            if (!draft.askingHowLong || days !in KEEP_OPEN_DAYS) return
            onKeepOpenDismiss()
            val end = display.creatableEnd(draft) ?: return
            onCreateEvent(draft.name, draft.range.from, end, cutoffFormatter.openFor(days))
        }

        /** The back-dated sheet closed unchosen: the form stays exactly as the host left it, and nothing is created. */
        fun onKeepOpenDismiss() = local.update { it.copy(create = it.create.copy(askingHowLong = false)) }
    }

    /**
     * The range calendars: the gestures on the create
     * form's calendar or the open one's; and the calendar over a participation surface, which opens on the range the
     * surface resolves, complete — so the first tap on a day starts a new range — held to the share-range rule around
     * the event's window. OK takes its span as a custom range; closing changes nothing.
     */
    val picker: PickerCommands = PickerCommands()

    inner class PickerCommands internal constructor() {
        /**
         * A gesture on a range calendar: the open calendar's, or else the
         * create form's. Applied at once, in the order the gestures came, so a drag's moves land one after another.
         */
        internal fun onEdit(edit: RangeEdit) = local.update { l ->
            val picking = l.picking
            if (picking != null) {
                l.copy(
                    picking = picking.copy(range = picking.range.after(edit, picking.bounds, picking.range.from.hour)),
                )
            } else {
                val hour = cutoffFormatter.nowLocal().hour
                l.copy(create = l.create.choose(l.create.range.after(edit, display.createBounds, hour)))
            }
        }

        fun onOpen() {
            val r = container.stateFlow.value.shown.range ?: return
            val range = EventRange(r.from, r.until.date, r.until.time, endPending = false)
            local.update { it.copy(picking = Picking(range, cutoffFormatter.shareBounds(r.windowStart, r.windowEnd))) }
        }

        fun onDismiss() = local.update { it.copy(picking = null) }

        fun onConfirm() {
            val open = local.value.picking ?: return
            val until = open.range.validUntil(open.bounds) ?: return
            onDismiss()
            form.onRangeCustom(open.range.from, until)
        }
    }

    /**
     * The three photo-access taps, grouped —
     * they are one question the screen asks in three forms, and the screen's own `AccessActions` bundle
     * already mirrors this grouping.
     */
    val access: AccessCommands = AccessCommands()

    inner class AccessCommands internal constructor() {
        fun onRequestPermission() = intent { commands.platform.requestAccess.fire(Unit) }

        /**
         * The joined layer's "Choose more photos" tap — presents the
         * platform's limited-library picker; the selection outcome arrives via the selection seam.
         */
        fun onChoosePhotos() = intent { commands.platform.choosePhotos.fire(Unit) }

        fun onOpenSettings() = intent { commands.platform.openSettings.fire(Unit) }
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
     * Opening and closing what is drawn over — or instead of — the current layer.
     *
     * Grouped for the same reason [form] is: these are one question ("what is on screen"), and each of
     * them reduces and nothing more. That is the property decision record `add-reconfigure-membership` D4 asked for — a
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
            local.update { it.copy(renameRequest = null, overlays = it.overlays.copy(renaming = true)) }
        }

        fun onRenameDismiss() = intent { local.editOverlays { it.copy(renaming = false) } }

        fun onQrOpen() = intent { local.editOverlays { it.copy(showingQr = true) } }

        fun onQrDismiss() = intent { local.editOverlays { it.copy(showingQr = false) } }

        fun onReportBugOpen() = intent {
            local.update {
                it.copy(
                    overlays = it.overlays.copy(reportingBug = true, reportSeed = null),
                    verifiedReport = false,
                )
            }
        }

        /** "Report this" beside a refusal: the same sheet, its description written for [message]. */
        fun onReportRefusal(message: ScreenMessage) = intent {
            local.update {
                it.copy(
                    overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = message),
                    verifiedReport = true,
                )
            }
        }

        fun onReportBugDismiss() = intent { local.editOverlays { it.copy(reportingBug = false, reportSeed = null) } }

        /** The app menu. Where the layer does not offer it, an open flag is masked. */
        fun onMenuOpen() = intent {
            local.update { it.copy(overlays = it.overlays.copy(menuOpen = true), mobileDataNotSaved = false) }
        }

        fun onMenuDismiss() = intent {
            local.update { it.copy(overlays = it.overlays.copy(menuOpen = false), mobileDataNotSaved = false) }
        }

        /**
         * The menu's mobile-data switch: applied as it is flipped, the menu staying open. The
         * switch shows the device's choice, which only a save moves — so a flip that could not be saved leaves it where
         * it was, and the menu says so (decision record `changes/archive/2026-10-07-mobile-data-per-device`, D3).
         */
        fun onMobileData(to: SwitchTo) = intent {
            local.update { it.copy(mobileDataNotSaved = false) }
            val id = ids.next()
            commands.setMobileData.fire(
                MobileDataRequest(id, to.mobileData()),
            )
            val outcome = mobileDataQuery.changes.answerTo(id)
            mobileData.value = outcome.inEffect == MobileDataChoice.ALLOWED
            when (outcome) {
                is MobileDataOutcome.Saved -> Unit
                is MobileDataOutcome.NotSaved -> local.update { it.copy(mobileDataNotSaved = true) }
            }
        }

        /** The menu's "Report a problem": the menu gives way to the sheet in one edit, so the two never stack. */
        fun onMenuReportBug() = intent {
            local.update {
                it.copy(
                    overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = null),
                    verifiedReport = false,
                )
            }
        }

        /**
         * One of the menu's links, opened outside the app. The menu closes first, so
         * coming back finds the screen rather than the drawer; a refused hand-off is logged by the command.
         */
        fun onOpenLink(link: AppLink) = intent {
            local.editOverlays { it.copy(menuOpen = false) }
            commands.platform.openLink.fire(AppLinkRequest.SitePage(link.page))
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
            val joined = membership.value ?: return@enqueue
            val form = reconfigureForm(joined.event.range, SettingChange.of(joined), cutoffFormatter::toLocal)
                .copy(albumContents = albumContents)
            local.update {
                it.copy(
                    form = form,
                    picking = null,
                    settings = Owned(joined.event.id, SettingsSurface.Open),
                    pendingWithdrawal = Owned(null, null),
                    lastApplied = Owned(null, null),
                )
            }
        }

        /**
         * Close the settings: swiped down, back, or a tap on the joined screen above
         * them. Every change already applied as it was made, so closing writes nothing — and a question still open
         * about withdrawing photos is answered "keep sharing".
         */
        fun onCancelReconfigure() = settings.enqueue {
            local.update {
                it.copy(
                    settings = Owned(null, SettingsSurface.Closed),
                    pendingWithdrawal = Owned(null, null),
                    picking = null,
                )
            }
        }
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

    /**
     * Send the diagnostic dump with the operator's account of the
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
            val id = ids.next()
            commands.sendDiagnostics.fire(
                ReportRequest(id, note, ReportContext(screen, shownCounts(state.ui), verification)),
            )
            val outcome = requests.report.answerTo(id)
            // Shown, then cleared after a moment; a later report's word replaces an earlier one's.
            local.editOverlays { it.copy(reportNotice = outcome.shown) }
            reportNoticeClear?.cancel()
            reportNoticeClear = scope.launch {
                delay(REPORT_NOTICE_MILLIS)
                local.editOverlays { it.copy(reportNotice = null) }
            }
        }
    }

    /**
     * The event's settings, applied as they change: the queue every settings act
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
         * The settings apply each change as it is made: one reconfigure per change, built from the membership IN EFFECT
         * with that one change, and run in the container's intent order — so quick changes apply one after another and
         * the last one stands. A change that would withdraw photos from the event (sharing
         * off, or a narrower range) is held instead, and asked about ([pendingWithdrawal][Local.pendingWithdrawal]).
         * [config] is the membership whose settings are open ([openMembership]).
         */
        internal suspend fun change(joined: Membership, change: (SettingChange, Membership) -> SettingChange) {
            val inEffect = settingsInEffect(joined)
            val next = change(inEffect, joined)
            // A switch set to what it shows changes nothing, so there is nothing to save.
            if (next == inEffect) return
            if (next.withdrawsFrom(inEffect)) {
                local.update { it.copy(pendingWithdrawal = Owned(joined.event.id, next)) }
                return
            }
            applySetting(joined, inEffect, next)
        }

        /**
         * The settings in effect: the last change these settings applied, else the membership as read. The read can lag a
         * save the use-case just made, and a change built on it would quietly undo the one before — so quick changes
         * build on each other, not on a stale read.
         */
        private fun settingsInEffect(joined: Membership): SettingChange =
            local.value.lastApplied.forMembership(joined.event.id, null) ?: SettingChange.of(joined)

        /**
         * Run one change through [UserCommands.reconfigure]. The event id rides with the values so a switch that landed
         * while the settings were open makes the use-case a no-op rather than overwriting a different membership; the
         * share-range rule is the use-case's. Afterwards the controls are re-seeded from the settings in
         * effect — the change if it landed, the ones before it if not — so a change that did not land shows the setting
         * still in effect, and says so: the controls never hold a value that is not saved.
         */
        private suspend fun applySetting(joined: Membership, before: SettingChange, next: SettingChange) {
            local.update { it.copy(pendingWithdrawal = Owned(null, null)) }
            val id = ids.next()
            commands.reconfigure.fire(
                SettingsRequest(
                    id,
                    ShareChoice(
                        joined.event.id,
                        next.from,
                        next.until,
                        next.direction,
                        AlbumSwitch.choice(next.saveToAlbum),
                    ),
                ),
            )
            val outcome = requests.settings.answerTo(id)
            if (outcome == SettingsResult.NotCurrent) {
                local.update {
                    it.copy(
                        settings = Owned(null, SettingsSurface.Closed),
                        lastApplied = Owned(null, null),
                    )
                }
                return
            }
            val saved = outcome == SettingsResult.Saved
            val inEffect = if (saved) next else before
            val reseeded = reconfigureForm(joined.event.range, inEffect, cutoffFormatter::toLocal)
                .copy(albumContents = albumContents)
            val surface = if (saved) SettingsSurface.Open else SettingsSurface.SaveFailed
            local.update {
                it.copy(
                    form = reseeded,
                    settings = Owned(joined.event.id, surface),
                    lastApplied = Owned(joined.event.id, inEffect),
                )
            }
        }

        /** "Stop sharing" on the withdrawal question: the held change applies. */
        fun onConfirmStopSharing() = enqueue {
            val joined = membership.value ?: return@enqueue
            val held = local.value.pendingWithdrawal.forMembership(joined.event.id, null) ?: return@enqueue
            applySetting(joined, settingsInEffect(joined), held)
        }

        /** "Keep sharing": the held change is dropped, and the controls — which never took it — stay as they were. */
        fun onKeepSharing() = enqueue { local.update { it.copy(pendingWithdrawal = Owned(null, null)) } }

        /**
         * The membership whose settings are open over the joined screen — where a form edit applies rather than drafts —
         * or `null` when none are.
         */
        internal fun openMembership(): Membership? = membership.value?.takeIf {
            local.value.settings.forMembership(it.event.id, SettingsSurface.Closed) != SettingsSurface.Closed
        }
    }

    /**
     * The member's edits to the participation form, grouped.
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
            if (membership.value != null) settings.enqueue(act) else intent { act() }
        }

        // At the join gate each edit drafts the form; with the event's settings open it applies at once.
        fun onShareOn(to: SwitchTo) = formIntent {
            val on = to.isOn
            val open = settings.openMembership()
            if (open != null) {
                settings.change(open) { now, _ ->
                    now.copy(direction = directionOf(on, now.direction.includesDownload))
                }
            } else {
                local.editForm { it.copy(shareOn = on) }
            }
        }

        fun onReceiveOn(to: SwitchTo) = formIntent {
            val on = to.isOn
            val open = settings.openMembership()
            if (open != null) {
                settings.change(open) { now, _ -> now.copy(direction = directionOf(now.direction.includesUpload, on)) }
            } else {
                local.editForm { it.copy(receiveOn = on) }
            }
        }

        fun onSaveToAlbum(to: SwitchTo) = formIntent {
            val on = to.isOn
            val open = settings.openMembership()
            if (open != null) {
                settings.change(open) { now, _ -> now.copy(saveToAlbum = on) }
            } else {
                local.editForm {
                    it.copy(saveToAlbum = on)
                }
            }
        }

        fun onRangePreset(preset: RangeChoice) {
            // A preset chip is a complete choice: the calendar it sits on closes.
            local.update { it.copy(picking = null) }
            formIntent { editRange { it.copy(preset = preset) } }
        }

        /** A custom range from the calendar; a `null` bound keeps the one already picked. */
        fun onRangeCustom(from: LocalDateTime?, until: LocalDateTime?) = formIntent {
            editRange { f ->
                f.copy(
                    preset = RangeChoice.CUSTOM,
                    customFrom = from ?: f.customFrom,
                    customUntil = until ?: f.customUntil,
                )
            }
        }

        /**
         * A committed range (a preset chip, or the calendar's OK). With the settings open it is the one place the
         * bounds are re-resolved from the form; every other change carries the bounds in effect untouched.
         */
        private suspend fun editRange(edit: (RangeForm) -> RangeForm) {
            val open = settings.openMembership()
            if (open == null) {
                local.editForm(edit)
                return
            }
            val form = edit(local.value.form)
            settings.change(open) { now, c ->
                val range = resolveRange(form, c.event.range.start, c.event.range.end)
                now.copy(from = range.chosenFrom, until = range.chosenUntil)
            }
        }
    }

    /**
     * An invite the app was opened with, read at the boundary (`LinkReading`): what is no invite flashes the transient
     * error without touching state; an invite whose key cannot be read — cut short in
     * sharing — opens the incomplete-invite screen, as a key that does not open its event does; an invite opens the
     * **join gate** — a first join the full-screen confirmation, a different event while joined a switch
     * confirmation. Nothing a link says joins without a tap. Re-scanning the already-joined event is a no-op (never
     * re-enrolls).
     */
    fun onInvite(read: InviteLinkVerdict) = intent {
        when (read) {
            InviteLinkVerdict.NotAnInvite -> showTransientError()
            is InviteLinkVerdict.KeyUnreadable -> when {
                pending.value?.eventId == read.event -> ignoreRepeat(read.event, "a pending join is open")
                membership.value?.let { it.event.id } == read.event -> ignoreRepeat(read.event, "already joined")
                else -> startUnreadable(read.event)
            }
            is InviteLinkVerdict.Invite -> {
                val eventId = read.link.event
                val key = read.link.key
                val current = membership.value
                when {
                    // The two DUPLICATE rungs, first because they outrank every other reading of the same link.
                    pending.value?.eventId == eventId -> ignoreRepeat(eventId, "a pending join is open")
                    // The joined event's own invite gives a LOST key back; any other reopening of it changes nothing.
                    current?.let { it.event.id } == eventId && key != null && keyPresence.value == KeyPresence.Lost ->
                        restoreKey(eventId, key)
                    current?.let { it.event.id } == eventId -> ignoreRepeat(eventId, "already joined")
                    // First join → JoiningEvent; a different event while joined → Joined.pendingSwitch (the
                    // same-event case is the duplicate rung above).
                    else -> startPending(eventId, key, leavesQuietly = current != null)
                }
            }
        }
    }

    /**
     * A delivery of a link this gate is already acting on, or has already acted on — **recorded, then
     * ignored**.
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
    private fun ignoreRepeat(eventId: EventId, because: String) {
        log("join gate: ignoring a repeated delivery of $eventId — $because")
    }

    /**
     * The joined event's reopened invite, offered to give its lost key back — kept only if it is the event's own. The
     * screen follows the key's presence, not the request's answer.
     */
    private fun restoreKey(eventId: EventId, key: EventKey) {
        val request = ids.next()
        commands.restoreKey.fire(KeyRestoreRequest(request, key))
        log("join gate: the reopened invite of $eventId offers its key back ($request)")
    }

    /** Retry the details fetch after a transient load failure. */
    fun onRetryLoad(): Job = intent {
        val p = pending.value ?: return@intent
        maintenanceReload?.cancel()
        pending.value = p.copy(phase = JoinProgress.Loading, service = null)
        val loaded = load(p.eventId, p.key)
        pending.update { it.showing(p.eventId, loaded) }
        scope.reloadWhileUpdating(loaded, pending) { onRetryLoad() }?.let { maintenanceReload = it }
    }

    /**
     * Confirm a first join with the chosen capture-date [cutoff], participation [direction], and album
     * choice [saveToAlbum]: enroll → provision (no leave).
     *
     * Where the surface said so ([asksAccessOnJoin]), the same tap first raises iOS's photo-access dialog —
     * the one deliberate action the join gate has that may. The join does not
     * wait for the answer: `request()` returns nothing and cannot suspend, the grant arrives only through the
     * permission source, and the join goes ahead whatever it is — so the dialog lands over Committing or the
     * joined screen, and a later grant starts sharing through the existing subscription (decision record
     * `simplify-join-screen`, D3). A retry never re-requests: by then iOS has an answer.
     */
    fun onConfirmJoin() = intent {
        if (asksAccessOnJoin(membership.value, permission.value)) commands.platform.requestAccess.fire(Unit)
        commit()
    }

    /**
     * Confirm a switch: run the **leave and nothing else**, and choose nothing
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
        if ((p.phase as? JoinProgress.Detailed)?.step != JoinPhase.Detailed.Step.Ready) return@guardedIntent
        val left = membership.value?.let { it.event.id } ?: return@guardedIntent
        local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
        commands.leave.fire(Unit)
        // The leave answers nothing but the membership: the guard is held until it is gone — or the switch was given
        // up, since a leave that cannot be written leaves the member joined — so a second tap asks for no second leave.
        combine(
            membership,
            pending,
        ) { joined, open -> joined?.let { it.event.id } != left || open?.eventId != p.eventId }.first { it }
    }

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
     * A create just minted [eventId]: route it into the **same**
     * pending-join gate a scanned QR uses — non-auto-confirmed — so the creator loads the event, picks a
     * capture-date cutoff, and confirms like any joiner. The `POST /events` already minted the event, so
     * the gate holds a real `eventId` and performs a real details load; provision happens on confirm.
     */
    private suspend fun eventCreated(eventId: EventId, key: EventKey?) = startPending(eventId, key)

    // The gate's async work runs INLINE within the orbit intent (not on a side scope) so each pending
    // transition reduces through the container's own pipeline deterministically. A modal join is fine
    // to serialize; a real fetch suspends here, yielding a Loading frame before the result.
    /**
     * An invite to [eventId] whose key cannot be read: the incomplete-invite screen, with nothing loaded and nothing
     * left — what the member needs is the whole invite, from whoever shared it.
     */
    private fun startUnreadable(eventId: EventId) {
        log("join gate: the invite to $eventId carries a key that cannot be read")
        local.update { it.copy(form = freshForm, picking = null) }
        pending.value = PendingJoin(eventId, JoinProgress.WrongLink, key = null)
    }

    private suspend fun startPending(eventId: EventId, key: EventKey?, leavesQuietly: Boolean = false) {
        // A fresh surface starts from the defaults — all on, the full event window. Seeding HERE rather
        // than in the reduction is what keeps the member's edits from being overwritten by every
        // subsequent reduction, and what stops a previous surface's choices leaking into this one.
        local.update { it.copy(form = freshForm, picking = null) }
        // Pending BEFORE any read: a repeated delivery of the same link is then the duplicate rung's, and a loading
        // switch shows no dialog yet.
        pending.value = PendingJoin(eventId, JoinProgress.Loading, key)
        val loaded = load(eventId, key)
        // Another event's invite while joined: when leaving the joined event loses nothing for anyone (decision record
        // `changes/back-dated-events`, D8), it is left at once, without the switch question — the join that follows is
        // then a first join, whose Cancel leaves the device in no event, exactly as after a confirmed switch. Asked
        // only once the invite has loaded: an invite that cannot be joined never leaves the joined event.
        if (leavesQuietly && pending.value?.eventId == eventId) {
            val id = ids.next()
            commands.switch.fire(SwitchRequest(id, loaded.invite))
            val verdict = requests.switch.answerTo(id)
            if (verdict == SwitchVerdict.Quiet && pending.value?.eventId == eventId) {
                log("join gate: leaving the joined event for $eventId without asking — it loses nothing")
                local.update { it.copy(overlays = Overlays()) } // every overlay belongs to the layer being left
                commands.leave.fire(Unit)
            }
        }
        pending.update { it.showing(eventId, loaded) }
        scope.reloadWhileUpdating(loaded, pending) { onRetryLoad() }?.let { maintenanceReload = it }
    }

    /**
     * Load the event's join details. The cutoff's floor is the event's start, canonical by construction: a membership
     * always carries a cutoff, so a details read that cannot supply one lands on a retryable failure, never on an empty
     * cutoff row and an enabled confirm, which would join at whole-library scope and upload the whole camera roll.
     */
    private suspend fun load(eventId: EventId, key: EventKey?): Loaded {
        val load = try {
            joinDetails.ask(InviteLink(eventId, key))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // The `Loading` twin of `commit()`'s repair: `Loading` pins no action either, so a throw here would park a
            // full-screen spinner with no Cancel — or, while joined, an invisible pending join — until the process
            // restarts. `LoadFailed` is exactly where a details source REPORTING a transient failure lands. Defence in
            // depth: the production read answers every failure as a value, but the seam is a constructor parameter.
            pending.update { if (it?.eventId == eventId) it.copy(phase = JoinProgress.LoadFailed) else it }
            throw t
        }
        // The headless negative oracle: a gate parked on a failed details load shows a dialog nobody headless
        // watches — without this line, `debug.log` shows only the HTTP `404`.
        if (load !is JoinDetails.Found) log("join gate: details load did not succeed for $eventId ($load)")
        return when (load) {
            // An event whose photos are deleted is refused before any choice is offered.
            is JoinDetails.Found -> if (load.completed) {
                Loaded(JoinProgress.Closed, load.event, InviteFact.NotJoinable)
            } else {
                Loaded(
                    JoinProgress.Detailed(load.event.details(), JoinPhase.Detailed.Step.Ready),
                    load.event,
                    InviteFact.Joinable,
                )
            }
            JoinDetails.NotFound -> Loaded(JoinProgress.NotFound, null, InviteFact.NotJoinable)
            is JoinDetails.Unanswered -> Loaded(JoinProgress.LoadFailed, null, InviteFact.Unknown, failed = load)
            JoinDetails.WrongLink -> Loaded(JoinProgress.WrongLink, null, InviteFact.NotJoinable)
        }
    }

    private suspend fun commit() {
        val p = pending.value ?: return
        // Only a loaded (Ready) or previously-failed (CommitFailed) surface can be confirmed; a
        // still-loading/blocked/committing phase ignores the action. Both carry a non-null name, startsAt,
        // endsAt AND deletesAt — so a commit can never reach the join without the floor, the ceiling,
        // and the retention deadline.
        val detailed = p.phase as? JoinProgress.Detailed ?: return
        if (detailed.step !in RETRYABLE_STEPS) return
        val event = detailed.event
        // What is committed is what the reduction RESOLVED — the same value the surface rendered.
        val form = local.value.form
        val range = resolveRange(form, event.startsAt, event.endsAt)
        // A join always carries a direction: with both switches off the confirm is
        // disabled on screen, and a confirm that arrives anyway joins nothing rather than a membership doing nothing.
        if (!range.commitEnabled) return
        val served = p.loaded ?: return
        val choice = JoinChoice(
            served,
            InviteLink(p.eventId, p.key),
            range.chosenFrom,
            range.chosenUntil,
            range.direction,
            AlbumSwitch.choice(form.saveToAlbum),
        )
        pending.value = p.copy(phase = JoinProgress.Detailed(event, JoinPhase.Detailed.Step.Committing), service = null)
        beginMembership()
        val commit = try {
            val id = ids.next()
            commands.join.fire(JoinRequest(id, choice))
            requests.join.answerTo(id)
        } catch (cancelled: CancellationException) {
            // Cancellation is teardown, not failure: rethrow before the repair below, or a cancelled
            // commit would rewrite the phase on its way out (the `StatusCountsPoller` shape).
            throw cancelled
        } catch (t: Throwable) {
            // `Committing` pins NO action (`JoiningEventScreen`: "In-flight phases offer no actions"), so a
            // throw here would otherwise park the gate on a dead-end spinner for the life of the process, and the
            // join gate never rests in a phase that offers no action.
            pending.update {
                it.settled(
                    p,
                    detailed,
                    JoinCommit.Failed(),
                    membership.value,
                    refusal.value,
                    attestFault.value,
                )
            }
            // Rethrown, never swallowed: the container's `exceptionHandler` reports it at `Error`, which is
            // what reaches the crash reporter. That handler is also why rethrowing is safe here — without it
            // Orbit's re-throw would cancel the intent job and take every later command with it.
            throw t
        }
        pending.update { it.settled(p, detailed, commit, membership.value, refusal.value, attestFault.value) }
    }

    /**
     * A membership BEGINS here, so its surface state starts here — in one place every new membership passes
     * through, a rejoin of the same event included. A collector watching the config
     * could miss that: it is a StateFlow, and a leave and a rejoin of one event can conflate into A → A.
     */
    private suspend fun beginMembership() {
        local.update { it.copy(settings = Owned(null, SettingsSurface.Closed), renameRequest = null) }
    }
}

/**
 * How often the joined screen re-checks the wall clock. One minute: the
 * dates line's finest unit is a minute ("ends in 40 min"), so a finer tick would buy nothing visible, and
 * nothing of the member's can upload before the start regardless.
 */
private const val NOT_STARTED_TICK_MILLIS = 60_000L

/** How long the transient invalid-link error stays on screen after it last appeared. */
private const val TRANSIENT_ERROR_MILLIS = 4_000L

/** How long the word on a sent report stays: long enough to read, then gone. */
private const val REPORT_NOTICE_MILLIS = 4_000L
