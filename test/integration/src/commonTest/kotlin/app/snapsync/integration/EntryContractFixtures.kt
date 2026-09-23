package app.snapsync.integration

import app.snapsync.compose.EntryHooks
import app.snapsync.compose.extensionEntries
import app.snapsync.compose.platformEntries
import app.snapsync.contracts.Entered
import app.snapsync.contracts.EntryIdentifiers
import app.snapsync.contracts.ExtensionEntriesObservations
import app.snapsync.contracts.ExtensionEntriesState
import app.snapsync.contracts.ExtensionEntriesSubject
import app.snapsync.contracts.PlatformEntriesObservations
import app.snapsync.contracts.PlatformEntriesState
import app.snapsync.contracts.PlatformEntriesSubject
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.Layer
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusSources
import app.snapsync.world.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The world side of the inbound-port contracts' bindings (capability `port-contracts`): enter a named state over a
 * fresh [World], and adapt that world to the contract's observation handle. The bindings themselves live in the
 * platform test source sets, because a binding states its host as a literal; both call this, so the JVM and the
 * simulator enter every state the same way.
 *
 * The world runs on its own real-time scope rather than the clause's virtual clock — its mini-edge serves on a real
 * dispatcher (see `worldTest`) — and the binding's disposal cancels it.
 */
internal object EntryContractFixtures {

    // Canonical UUIDs: the link codec refuses anything else, exactly as the edge does.
    private const val INVITED_EVENT = "11111111-1111-4111-8111-111111111111"
    private const val JOINED_EVENT = "22222222-2222-4222-8222-222222222222"

    /** Stand-ins for the operating system's identifiers; the real ones are the iOS adapters' constants. */
    private val identifiers = EntryIdentifiers(
        downloadBackstopTask = "world.download.backstop",
        uploadHeartbeatTask = "world.upload.heartbeat",
        uploadTransferChannel = "world.upload.session",
        downloadTransferChannel = "world.download.session",
    )

    suspend fun enter(state: PlatformEntriesState): Entered<PlatformEntriesSubject> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val w = World(scope)
        when (state) {
            PlatformEntriesState.UNJOINED -> w.store.registerEvent(INVITED_EVENT, "Anna's Wedding")
            PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO -> {
                w.provision(JOINED_EVENT)
                w.addForeignDevice("DEV-F", JOINED_EVENT, listOf(World.foreignAsset("FQ")))
            }
        }
        val host = statusHost(w, scope)
        var active = false
        val tokens = mutableListOf<String>()
        val entries = platformEntries(
            core = { w.core },
            hooks = EntryHooks(
                markActive = { active = true },
                openUrl = host::onOpenUrl,
                assembleHost = {},
                deliverPushToken = { tokens += it },
                downloadBackstopTaskId = identifiers.downloadBackstopTask,
                uploadHeartbeatTaskId = identifiers.uploadHeartbeatTask,
                uploadTransferChannel = identifiers.uploadTransferChannel,
            ),
        )
        val observe = object : PlatformEntriesObservations {
            override val identifiers = EntryContractFixtures.identifiers
            override val inviteUrl = encodeEventUrl(EventLinkPayload(INVITED_EVENT))
            override val invitedEventId = INVITED_EVENT
            override val joinedEventId = JOINED_EVENT
            override fun joinGateEventId() = (host.container.stateFlow.value.layer as? Layer.JoiningEvent)?.eventId
            override fun transientError() = (host.container.stateFlow.value.layer as? Layer.CreateEvent)?.error
            override fun becameActive() = active
            override fun plannedForeignDownloads() = w.downloadStore.enqueueRequests.size
            override fun backstopsScheduled() = w.backstopsScheduled
            override fun deliveredPushTokens() = tokens.toList()
            override fun appUploaderBackgroundTasks() = w.operatorEngine.backgroundTasks
            override fun appUploaderTransferHandbacks() = w.operatorEngine.transferHandbacks
            override fun downloadSessionRealized() = w.downloadTransport != null
        }
        return Entered.Ready(PlatformEntriesSubject(entries, observe)) { scope.cancel() }
    }

    suspend fun enter(state: ExtensionEntriesState): Entered<ExtensionEntriesSubject> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val w = World(scope)
        when (state) {
            ExtensionEntriesState.UNJOINED -> Unit
            ExtensionEntriesState.JOINED_WITH_A_NEW_PHOTO -> {
                w.provision(JOINED_EVENT)
                w.addOwnAsset("A")
            }
            ExtensionEntriesState.JOINED_WITH_NOTHING_NEW -> w.provision(JOINED_EVENT)
        }
        val entries = extensionEntries(ports = { w.uploadPorts }, cycle = { w.cycle })
        val observe = object : ExtensionEntriesObservations {
            override fun uploadsStarted() = w.platform.created.size
        }
        return Entered.Ready(ExtensionEntriesSubject(entries, observe)) { scope.cancel() }
    }

    /** The container the world's links open into — the same composed join gate the iOS root builds. */
    private fun statusHost(w: World, scope: CoroutineScope) = StatusContainerHost(
        StatusSources(
            sync = w.syncStatusSource,
            permission = w.permission.permission,
            config = w.configSource.config,
        ),
        scope = scope,
        queries = w.core.userQueries,
        commands = w.userCommands,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
    )
}
