package app.snapsync.mock

import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.CrashReporterLevers
import app.snapsync.levers.Footprint
import app.snapsync.levers.MetricReportKind
import app.snapsync.levers.ProcessInfoLevers
import app.snapsync.levers.ProcessInfoView
import app.snapsync.levers.ProcessMetricsLevers
import app.snapsync.levers.ReceivedDump
import app.snapsync.levers.ReceivedEvent
import app.snapsync.levers.SystemUiLevers
import app.snapsync.levers.SystemUiRecord
import app.snapsync.levers.WorldEvent
import app.snapsync.model.Availability
import app.snapsync.model.CrashEvent
import app.snapsync.model.Handoff
import app.snapsync.model.MemoryFootprint
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.MetricNode
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.SystemUi
import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/** Told what a mocked system records, on the app's thread, as the app hands it over — the control channel's stream. */
fun interface WorldWatcher {
    fun told(event: WorldEvent)
}

// The mocks of what the platform tells a process about itself, and what it takes off the process's hands
// (`docs/testing.md`, "Mocks"): each a durable state, a port-typed face per process, and an operator face.

/** The crash reporter's channel: what left the device — the dumps its backend received — survives the process. */
class CrashReporterMock {
    internal val startedCell = MutableStateFlow(false)
    internal val dumps = ReceivedDumps()
    internal val events = ReceivedEvents()

    /** Whether the observed process started reporting. In-process plumbing; the channel's is the [operator]. */
    val started: StateFlow<Boolean> = startedCell.asStateFlow()

    /** Every diagnostic dump the observed processes transmitted, in order, as it left. In-process plumbing. */
    val sent: StateFlow<List<CrashEvent>> = dumps.cell.asStateFlow()

    /** Every automatic event the observed processes transmitted, in order, as it left. In-process plumbing. */
    val received: StateFlow<List<CrashEvent>> = events.cell.asStateFlow()

    /**
     * Tells [listener] each dump and each automatic event as the reporter's backend receives it (`docs/testing.md`,
     * "How a test waits").
     */
    fun watch(listener: WorldWatcher) {
        dumps.watch(listener)
        events.watch(listener)
    }

    /** The observed process's face. A new process has not started reporting until its root starts it. */
    fun port(): CrashReporter {
        startedCell.value = false
        return InMemoryCrashReporter(startedCell, dumps, events)
    }

    /** A face for a process nobody observes — the upload extension's. What it sends goes nowhere a test reads. */
    fun unobservedPort(): CrashReporter = InMemoryCrashReporter()

    val operator: CrashReporterOperator = CrashReporterOperator(this)
}

/** What the reporter's backend received from the observed process. */
class CrashReporterOperator internal constructor(private val mock: CrashReporterMock) : CrashReporterLevers {
    override suspend fun started(): Boolean = mock.started.value

    override suspend fun sent(): List<ReceivedDump> = mock.sent.value.map(::received)

    override suspend fun received(): List<ReceivedEvent> = mock.received.value.map(::receivedEvent)
}

/** An automatic event as the reporter's backend shows it. */
internal fun receivedEvent(event: CrashEvent): ReceivedEvent =
    ReceivedEvent(message = event.message.orEmpty(), tags = event.tags, contexts = event.contexts)

/** A dump as the reporter's backend shows it: its sections, as the app put them in. */
internal fun received(dump: CrashEvent): ReceivedDump {
    val section = { name: String -> dump.contexts[name].orEmpty() }
    val appLog = section("app_log")["text"].orEmpty()
    val extensionLog = section("ext_log")["text"].orEmpty()
    return ReceivedDump(
        note = section("note")["text"].orEmpty(),
        state = section("state"),
        ledger = section("ledger"),
        appLog = appLog,
        extensionLog = extensionLog,
        logBytes = appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size,
    )
}

/** What the OS says about the device: whether protected data is readable. */
class ProcessInfoMock(protectedData: Availability = Availability.AVAILABLE) {
    internal val cell = MutableStateFlow(protectedData)
    internal val footprint = MutableStateFlow<MemoryFootprint?>(DEFAULT_FOOTPRINT)

    /** The device's protected data, locked or not. In-process plumbing; the channel's is the [operator]. */
    var protectedData: Availability by cell::value

    /** The process's memory footprint, as the app reads it when it records one; `null` reads as none answered. */
    var memory: MemoryFootprint? by footprint::value

    fun port(): ProcessInfo = InMemoryProcessInfo(cell, footprint)

    val operator: ProcessInfoOperator = ProcessInfoOperator(this)
}

class ProcessInfoOperator internal constructor(private val mock: ProcessInfoMock) : ProcessInfoLevers {
    override suspend fun protectedData(available: Boolean) {
        mock.protectedData = if (available) Availability.AVAILABLE else Availability.UNAVAILABLE
    }

    override suspend fun footprint(footprint: Footprint?) {
        mock.memory = footprint?.let { MemoryFootprint(it.bytes, it.peakBytes, it.headroomBytes) }
    }

    override suspend fun info(): ProcessInfoView = ProcessInfoView(
        protectedDataAvailable = mock.protectedData == Availability.AVAILABLE,
        footprint = mock.memory?.let { Footprint(it.footprintBytes, it.peakBytes, it.headroomBytes) },
    )
}

/**
 * The platform's reports on the app's process — MetricKit's on an iPhone: a report is held until the process listens,
 * then handed over exactly once, inline on the thread that hands it (the provider's own, on a device), as the port's
 * one-shot delivery promises. A new process's face listens afresh; a report already handed over is gone.
 */
class ProcessMetricsMock {
    private val lock = mockLock()
    private var handlers: MetricHandlers? = null
    private val held = mutableListOf<ProcessMetricPayload>()

    /** The observed process's face: it has not listened until its composition registers its handlers. */
    fun port(): ProcessMetrics {
        lock.locked { handlers = null }
        return Face()
    }

    /** The provider hands [report] to the listening process now, or holds it for the next one that listens. */
    fun hand(report: ProcessMetricPayload) {
        val listening = lock.locked { handlers.also { if (it == null) held += report } }
        listening?.onReport?.deliver(report)
    }

    internal inner class Face : ProcessMetrics {
        @CompositionOnly
        override fun inbound(handlers: MetricHandlers) {
            val due = lock.locked {
                this@ProcessMetricsMock.handlers = handlers
                held.toList().also { held.clear() }
            }
            due.forEach(handlers.onReport::deliver)
        }
    }

    val operator: ProcessMetricsOperator = ProcessMetricsOperator(this)
}

/** Plays the platform handing the app a report — a real one, its identifying values randomized ([metricReport]). */
class ProcessMetricsOperator internal constructor(private val mock: ProcessMetricsMock) : ProcessMetricsLevers {
    override suspend fun deliver(report: MetricReportKind) = mock.hand(metricPayloadOf(metricReport(report)))
}

/** The device's wall clock and zone — stopped wherever the operator sets it, read at every call. */
class ClockMock(now: Instant = Instant.fromEpochMilliseconds(0), zone: TimeZone = TimeZone.UTC) {
    @Volatile internal var zone: TimeZone = zone

    /** The time every face reads. In-process plumbing; the channel's is the [operator]. */
    @Volatile var now: Instant = now

    fun port(): Clock = Face()

    /** The port face a process is handed. */
    internal inner class Face : Clock {
        override fun now(): Instant = this@ClockMock.now
        override fun timeZone(): TimeZone = zone
    }

    val operator: ClockOperator = ClockOperator(this)
}

class ClockOperator internal constructor(private val mock: ClockMock) : ClockLevers {
    override suspend fun set(at: String) {
        mock.now = Instant.parse(at)
    }

    override suspend fun now(): String = mock.now.toString()
}

/** The device's Secure Enclave: its keys outlive a process. Nothing to pull, so no operator face. */
class DeviceIntegrityMock {
    internal val keys = EnclaveKeys()

    /** A process's face; a simulator has no App Attest: `available = false`. */
    fun port(available: Boolean = true): DeviceIntegrity = InMemoryDeviceIntegrity(available, keys)
}

/**
 * The platform's own UI, where the app hands something over: the share sheet, the URL opener and the Settings page.
 * Off device each is accepted and recorded, and nothing opens.
 */
class SystemUiMock {
    internal val sharedCell = MutableStateFlow<List<String>>(emptyList())
    internal val sharedTitlesCell = MutableStateFlow<List<String>>(emptyList())
    internal val openedCell = MutableStateFlow<List<String>>(emptyList())
    internal val settings = MutableStateFlow(0)
    internal val settingsInFrontCell = MutableStateFlow(false)

    // In-process plumbing — the desktop inspector's and the contract bindings'; the channel's is the [operator].

    /** Every text the share sheet was handed, in order. */
    val shared: StateFlow<List<String>> = sharedCell.asStateFlow()

    /** The title each share carried, in the same order as [shared]. */
    val sharedTitles: StateFlow<List<String>> = sharedTitlesCell.asStateFlow()

    /** Every URL the app asked the platform to open, in order. */
    val opened: StateFlow<List<String>> = openedCell.asStateFlow()

    /** How many times the app opened its Settings page. */
    val settingsOpened: StateFlow<Int> = settings.asStateFlow()

    /** Whether the app's Settings page is in front — until the member leaves it ([leaveSettings]). */
    val settingsInFront: StateFlow<Boolean> = settingsInFrontCell.asStateFlow()

    /** Whether the app has no window to present from — a share is refused, as on a device in the background. */
    @Volatile var noWindow = false

    /** No app on the device presents the share sheet: sharing is refused. */
    @Volatile var noSheet = false

    /** The URLs no app on the device claims: opening one is refused. */
    @Volatile var unclaimed: Set<String> = emptySet()

    /** The member leaves the Settings page, back to the app. */
    fun leaveSettings() {
        settingsInFrontCell.value = false
    }

    /** Who is told what the app hands the platform's UI — the control channel's event stream, or no one. */
    @Volatile private var watcher: WorldWatcher = WorldWatcher {}

    /** Tells [listener] each hand-over as the app makes it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: WorldWatcher) {
        watcher = listener
    }

    fun port(): SystemUi = Face()

    /** The port face a process is handed. */
    internal inner class Face : SystemUi {
        override suspend fun share(text: String, title: String): Handoff {
            if (noWindow) return Handoff.Refused("no window to present the share sheet from")
            if (noSheet) return Handoff.Refused("no app presents the share sheet")
            sharedCell.value = sharedCell.value + text
            sharedTitlesCell.value = sharedTitlesCell.value + title
            watcher.told(WorldEvent.Shared(text, title))
            return Handoff.Accepted
        }

        override suspend fun openUrl(url: String): Handoff {
            if (url in unclaimed) return Handoff.Refused("no app claims $url")
            openedCell.value = openedCell.value + url
            watcher.told(WorldEvent.UrlOpened(url))
            return Handoff.Accepted
        }

        override fun openSettings() {
            settings.value += 1
            settingsInFrontCell.value = true
            watcher.told(WorldEvent.SettingsOpened)
        }
    }

    val operator: SystemUiOperator = SystemUiOperator(this)
}

/** What the app handed the platform's UI, and the levers that refuse a hand-over. */
class SystemUiOperator internal constructor(private val mock: SystemUiMock) : SystemUiLevers {
    override suspend fun record(): SystemUiRecord = SystemUiRecord(
        shared = mock.shared.value,
        sharedTitles = mock.sharedTitles.value,
        opened = mock.opened.value,
        settingsOpened = mock.settingsOpened.value,
        settingsInFront = mock.settingsInFront.value,
    )

    override suspend fun leaveSettings() = mock.leaveSettings()

    override suspend fun noWindow(on: Boolean) {
        mock.noWindow = on
    }

    override suspend fun noSheet(on: Boolean) {
        mock.noSheet = on
    }

    override suspend fun unclaimed(urls: List<String>) {
        mock.unclaimed = urls.toSet()
    }
}

/**
 * A process-metric payload of already-dotted [fields] — each a leaf under the root — as an operator plays a delivery:
 * read as a report, its keys come back as they were given.
 */
fun metricPayloadOf(fields: Map<String, String>): ProcessMetricPayload = ProcessMetricPayload(
    object : MetricNode.Branch {
        override val keys: List<String> = fields.keys.toList()
        override fun child(key: String): MetricNode? = fields[key]?.let(MetricNode::Leaf)
    },
)

/**
 * A process's per-thread log label: the label the core's runtime puts on the thread it runs a task on, read back by the
 * log writers — per thread, as every platform's slot is, so it keeps the `ThreadLabel` contract they keep.
 */
class ThreadLabelMock : ThreadLabel {
    // Per thread, as every platform's slot is: a label one thread sets, another never reads.
    private val slot = threadSlot()

    override fun swap(label: String?): String? = slot.get().also { slot.set(label) }

    override fun current(): String? = slot.get()
}
