package app.snapsync.mock

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Running
import app.snapsync.levers.BackgroundTimeLevers
import app.snapsync.levers.ExtensionRegistryLevers
import app.snapsync.levers.PendingWake
import app.snapsync.levers.WakeLevers
import app.snapsync.levers.WakeView
import app.snapsync.levers.WorldEvent
import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

// The mocks of the operating system's background execution (`docs/testing.md`, "Mocks"): when it wakes the app, how
// long it keeps it awake, and whether it runs the upload extension. Nothing here fires on its own — the operator plays
// the operating system.

/**
 * The operating system's scheduled wakes: its queue — at most one request per wake — survives the app's process, and
 * the handlers are those of the process that registered last.
 */
class WakeMock(
    /** The wakes this platform has; iOS has only the heartbeat. */
    internal val supported: Set<WakeId> = setOf(WakeId.Heartbeat),
    /** Why the operating system refuses this process's requests, or `null` where it takes them. */
    internal val refusal: String? = null,
) {
    internal val pending = MutableStateFlow<Map<WakeId, WakeTrigger>>(emptyMap())
    internal val handlers = HandlerSlot<WakeHandlers>("Wake", BeforeListen.Thrown)

    @Volatile internal var scheduled = 0

    /** Who is told each request the app makes — the control channel's event stream, or no one. */
    @Volatile private var watcher: OsWatcher = OsWatcher {}

    /** Tells [listener] each request the app makes, as it makes it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: OsWatcher) {
        watcher = listener
    }

    fun port(): Wake = Face()

    /** The port face a process is handed. */
    internal inner class Face : Wake {
        private val queue = InMemoryWake(pending, supported)

        @CompositionOnly
        override fun inbound(handlers: WakeHandlers) {
            this@WakeMock.handlers.set(handlers)
        }

        override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
            refusal?.let { ScheduleResult.Refused(it) } ?: queue.schedule(id, trigger).also {
                if (it != ScheduleResult.Scheduled) return@also
                if (id == WakeId.Heartbeat) scheduled++
                watcher.told(WorldEvent.WakeScheduled(pendingWake(id, trigger)))
            }

        override fun cancel(id: WakeId) {
            queue.cancel(id)
            watcher.told(WorldEvent.WakeCancelled(wakeName(id)))
        }
    }

    /**
     * The operating system wakes the app for [id], handing it [completion]; answers the run its expiry expires.
     * Plumbing: the completion is the played operating system's, and crosses no wire (`OsLevers.backgroundTask`).
     */
    fun fire(id: WakeId, completion: Completion): Running {
        val current = handlers.require("a $id wake")
        // Every wake is one-shot: the operating system launching it is what consumes the request.
        pending.value -= id
        return current.onWake.handle(id, completion)
    }

    val operator: WakeOperator = WakeOperator(this)
}

class WakeOperator internal constructor(private val mock: WakeMock) : WakeLevers {
    override suspend fun wakes(): WakeView =
        WakeView(mock.scheduled, mock.pending.value.map { (id, trigger) -> pendingWake(id, trigger) })
}

/** A wake request as the operating system holds it, on the wire. */
internal fun pendingWake(id: WakeId, trigger: WakeTrigger): PendingWake = when (trigger) {
    is WakeTrigger.After -> PendingWake(wakeName(id), trigger.cadence.name.lowercase(), trigger.earliest.inWholeSeconds)
    is WakeTrigger.LibraryChange -> PendingWake(wakeName(id))
}

/** A wake's name on the wire: `heartbeat`, `library-changed`. */
internal fun wakeName(id: WakeId): String = when (id) {
    WakeId.Heartbeat -> "heartbeat"
    WakeId.LibraryChanged -> "library-changed"
}

/**
 * The operating system's table of outstanding background-time holds. It survives a process in the sense a real table
 * does not end a dead process's holds: they simply never end.
 */
class BackgroundTimeMock {
    internal val held = MutableStateFlow<List<HeldBackgroundTime>>(emptyList())

    /** Who is told each hold begun and ended — the control channel's event stream, or no one. */
    @Volatile private var watcher: OsWatcher = OsWatcher {}

    /** Tells [listener] each hold the app begins and ends, as it does (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: OsWatcher) {
        watcher = listener
    }

    fun port(): BackgroundTime = InMemoryBackgroundTime(held) { watcher.told(it) }

    /** The operating system says every outstanding hold's time is up. */
    fun expireAll() {
        held.value.forEach { it.expire() }
    }

    val operator: BackgroundTimeOperator = BackgroundTimeOperator(this)
}

class BackgroundTimeOperator internal constructor(private val mock: BackgroundTimeMock) : BackgroundTimeLevers {
    override suspend fun holds(): List<String> = mock.held.value.map { it.label }

    override suspend fun expireAll() = mock.expireAll()
}

/**
 * The operating system's record of the upload extension's registration, and whether this operating system carries the
 * extension at all ([carried]: iOS 26.1 and later). Without it — the JVM by default, iOS below 26.1 — every port answers
 * `Unsupported` to everything.
 *
 * [carried] is a durable fact of the device, read when a launch builds its [port]: changing it reaches the app at its
 * next launch, as a phone's root reads it once (`SnapSyncRoot.osSupportsOsDrivenUpload`). The [record] is kept
 * whatever [carried] says, so a registration outlives the fact being toggled off and on.
 *
 * [grant] is the photo library's grant now: under a partial one the platform refuses every change, either way, and the
 * record a full grant made survives — yet reads absent until the grant is full again (`ExtensionRegistryContract`,
 * recorded on the SE2). [ExtensionRegistryOperator.registered] is the OS's record itself, whatever the grant.
 */
class ExtensionRegistryMock(
    carried: Boolean = false,
    /** The photo grant the platform judges a change by — the library's, read at every call. */
    private val grant: StateFlow<LibraryGrant> = MutableStateFlow(LibraryGrant.GRANTED),
    registered: Boolean = false,
) {
    /** Whether this operating system carries the upload extension, as the next launch reads it. */
    @Volatile var carried: Boolean = carried

    internal val record: MutableStateFlow<Boolean> = MutableStateFlow(registered)

    /** Who is told each registration change — the control channel's event stream, or no one. */
    @Volatile private var watcher: OsWatcher = OsWatcher {}

    /** Tells [listener] each change the operating system takes, as it takes it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: OsWatcher) {
        watcher = listener
    }

    /** One launch's registry: whether the extension is carried is fixed when the launch builds it. */
    fun port(): ExtensionRegistry =
        InMemoryExtensionRegistry(record.takeIf { carried }, grant) { watcher.told(it) }

    val operator: ExtensionRegistryOperator = ExtensionRegistryOperator(this)
}

class ExtensionRegistryOperator internal constructor(private val mock: ExtensionRegistryMock) : ExtensionRegistryLevers {
    override suspend fun registered(): Boolean? = mock.record.value.takeIf { mock.carried }

    override suspend fun carried(on: Boolean) {
        mock.carried = on
    }
}
