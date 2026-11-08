package app.snapsync.entrypoints

import app.snapsync.data.album.AccessChange
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.PhotoAccessChanges
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.gallery.LibrarySelection
import app.snapsync.data.network.NetworkStatus
import app.snapsync.data.process.BuildRead
import app.snapsync.data.sharing.BeforePublish
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.inbound.Background
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation
import app.snapsync.presentation.BuildFacts
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.ScreenQueries
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.UserCommands
import app.snapsync.presentation.onIntent
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.sharing.PhotoGrantQuery
import app.snapsync.scheduling.Observation
import app.snapsync.scheduling.ScreenDisplay
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * **Host assembly — a person reached the app** (`docs/patterns.md`, "Composition is wiring"). Run once, by the trigger
 * that shows the app — the foreground ([OnForeground]), a live screen or a tap ([HostScreen], [HostIntents]) — and by
 * nothing else, so a cold background wake runs none of it: it installs the permission subscriptions (the launch
 * reconcile among them), follows the network's returns, builds the one status host, and has the runtime show every
 * state it reduces.
 *
 * Built eagerly by the composition, it constructs nothing until it is run.
 */
class AssembleHost(
    private val permissions: PermissionSubscriptions,
    private val network: NetworkReturns,
    private val parts: HostParts,
    private val facts: HostFacts,
    private val display: ScreenDisplay<UiState>,
) : HostAssembly {
    private val assembled = lazy(::built)

    /** The one status host — assembled here first if nothing has yet. */
    val host: StatusContainerHost get() = assembled.value

    override fun assemble() {
        assembled.value
    }

    @OptIn(CompositionOnly::class)
    private fun built(): StatusContainerHost {
        permissions.install()
        network.install()
        val host = parts.host(facts.read())
        display.show(host.screen)
        return host
    }
}

/**
 * **The subscriptions only a person's start installs** — never a cold background wake's: the launch reconcile, then
 * the grant's real changes (the value the launch reconciled against is no change, and one that lands during the launch
 * reconcile is still delivered); the event album following the grant; and the process's one adjudication of what a
 * dead process's imports left, once the presence source can answer — ordered here, after the subscriptions, rather
 * than left to a caller to sequence.
 */
class PermissionSubscriptions(
    private val observation: Observation,
    private val grants: PhotoGrantQuery,
    private val uploaderChange: Inbound<UploaderChange, Unit>,
    private val grantChanged: Background<PhotoGrant>,
    private val accessChanges: PhotoAccessChanges,
    private val onAccessChanged: Background<AccessChange>,
    private val selection: SelectionSnapshots,
    private val library: LibrarySelection,
    private val selectionAnswerable: Background<Unit>,
) {
    @CompositionOnly
    fun install() {
        val changes = grants.changesSinceLaunch()
        uploaderChange.deliver(UploaderChange.LAUNCH)
        observation.follow(changes, grantChanged)
        observation.follow(accessChanges.changes, onAccessChanged)
        observation.follow(selection.answerableOnce(library.access()), selectionAnswerable)
    }
}

/** The network's return, followed while a person can see what it tells them: from host assembly on. */
class NetworkReturns(
    private val observation: Observation,
    private val status: NetworkStatus,
    private val returned: Background<Unit>,
) {
    @CompositionOnly
    fun install() = observation.follow(status.returned, returned)
}

/** What the status host is built from, but its build facts: the screen's reads, lookups, door, formatter and lanes. */
class HostParts(
    private val queries: ScreenQueries,
    private val shareableCount: ScreenLookup<CaptureRange, Int?>,
    private val joinDetails: ScreenLookup<InviteLink, JoinDetails>,
    private val commands: UserCommands,
    private val cutoffFormatter: CutoffFormatter,
    private val scope: CoroutineScope,
    private val reduction: CoroutineDispatcher,
) {
    /** The one status host over [facts]. */
    fun host(facts: BuildFacts): StatusContainerHost = StatusContainerHost(
        queries = queries,
        shareableCount = shareableCount,
        joinDetails = joinDetails,
        commands = commands,
        facts = facts,
        cutoffFormatter = cutoffFormatter,
        scope = scope,
        reduction = reduction,
    )
}

/** The build's facts the screen states, read when the host is assembled — never as the graph is built. */
class HostFacts(private val build: BuildRead, private val crash: CrashChannel, private val albums: AlbumFiling) {
    fun read(): BuildFacts = BuildFacts(
        store = build.store(),
        // Where a bug report goes on this build — the sheet says it.
        reportDestination = crash.destination,
        // Which build this is — the app menu's footer.
        label = build.label(),
        // What the event album holds on this phone — read from the photo library.
        albumContents = albums.contents,
    )
}

/**
 * A person's tap, handed to the status host's intent table on the thread that delivered it — the host assembled first
 * if nothing has. What a tap means is the host's; what it starts is the commands it fires.
 */
@OptIn(InboundImplementation::class)
class HostIntents(private val assembly: AssembleHost) : Inbound<UiIntent, Unit> {
    override fun deliver(arg: UiIntent) = assembly.host.onIntent(arg)
}

/**
 * A live screen is about to be built: the status host — assembled first, once — answers its current state, so no
 * screen's first frame is empty. Idempotent: a platform may build the screen more than once.
 */
@OptIn(InboundImplementation::class)
class HostScreen(private val assembly: AssembleHost) : Inbound<Unit, UiState> {
    override fun deliver(arg: Unit): UiState = assembly.host.screen.value
}

/**
 * **The rig moved its uploader pin** (`DevControls`, a rig build only): the extension switched off is deregistered at
 * once, then both uploaders are reconciled by comparison, as at a launch.
 */
class UploaderPinMoved(
    private val registrar: ExtensionRegistrar,
    private val switches: UploaderSwitches,
    private val uploaderChange: Inbound<UploaderChange, Unit>,
) {
    suspend fun followed() {
        registrar.followSwitch(switches.extension())
        uploaderChange.deliver(UploaderChange.LAUNCH)
    }
}

/**
 * **The subscriptions every start installs, a background one included** — unlike [PermissionSubscriptions], which
 * only a person's start may. A new credential publishes the push registration again (the recovery for one the backend
 * refused); every membership change stands the report's event and device on the crash channel; and the selection
 * observer opens, its every snapshot seen first by [recognition] and then handed to its entry point — so a background
 * start under a partial grant learns its selection.
 */
class StartSubscriptions(
    private val observation: Observation,
    private val tokenChanged: Flow<Unit>,
    private val credentialChanged: Background<Unit>,
    private val membershipChanges: Flow<MembershipRead>,
    private val membershipChanged: Background<MembershipRead>,
    private val selection: SelectionSnapshots,
    private val library: LibrarySelection,
    private val recognition: BeforePublish,
    private val selectionChanged: Background<Unit>,
) {
    @CompositionOnly
    fun install() {
        observation.follow(tokenChanged, credentialChanged)
        observation.follow(membershipChanges, membershipChanged)
        // The gallery keeps its latest snapshot, so one read before this collector runs is kept, not lost.
        observation.follow(library.published(selection, recognition), selectionChanged)
        // The selection observer opens here and nowhere else — on composition, so a background start reads it too.
        library.open()
    }
}
