package app.snapsync.mock

import app.snapsync.model.Availability
import app.snapsync.model.CrashEvent
import app.snapsync.model.Handoff
import app.snapsync.ports.Clock
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.SystemUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

// The mocks of what the platform tells a process about itself, and what it takes off the process's hands
// (`docs/testing.md`, "Mocks"): each a durable state, a port-typed face per process, and an operator face.

/** The crash reporter's channel: what left the device — the dumps its backend received — survives the process. */
class CrashReporterMock {
    internal val started = MutableStateFlow(false)
    internal val dumps = MutableStateFlow<List<CrashEvent>>(emptyList())

    /** The observed process's face. A new process has not started reporting until its root starts it. */
    fun port(): CrashReporter {
        started.value = false
        return InMemoryCrashReporter(started, dumps)
    }

    /** A face for a process nobody observes — the upload extension's. What it sends goes nowhere a test reads. */
    fun unobservedPort(): CrashReporter = InMemoryCrashReporter()

    val operator: CrashReporterOperator = CrashReporterOperator(this)
}

/** What the reporter's backend received from the observed process. */
class CrashReporterOperator internal constructor(mock: CrashReporterMock) {
    /** Whether the observed process started reporting. */
    val started: StateFlow<Boolean> = mock.started.asStateFlow()

    /** Every diagnostic dump the observed processes transmitted, in order, as it left. */
    val sent: StateFlow<List<CrashEvent>> = mock.dumps.asStateFlow()
}

/** What the OS says about the device: whether protected data is readable. */
class ProcessInfoMock(protectedData: Availability = Availability.AVAILABLE) {
    internal val cell = MutableStateFlow(protectedData)

    fun port(): ProcessInfo = InMemoryProcessInfo(cell)

    val operator: ProcessInfoOperator = ProcessInfoOperator(this)
}

class ProcessInfoOperator internal constructor(private val mock: ProcessInfoMock) {
    /** The device's protected data, locked or not. */
    var protectedData: Availability by mock.cell::value
}

/** The device's wall clock and zone — stopped wherever the operator sets it, read at every call. */
class ClockMock(now: Instant = Instant.fromEpochMilliseconds(0), zone: TimeZone = TimeZone.UTC) {
    internal var zone: TimeZone = zone
    internal var now: Instant = now

    fun port(): Clock = object : Clock {
        override fun now(): Instant = this@ClockMock.now
        override fun timeZone(): TimeZone = zone
    }

    val operator: ClockOperator = ClockOperator(this)
}

class ClockOperator internal constructor(private val mock: ClockMock) {
    /** The time every face reads. */
    var now: Instant by mock::now
}

/** The device's Secure Enclave: its keys outlive a process. Nothing to pull, so no operator face. */
class DeviceIntegrityMock {
    internal val keys = EnclaveKeys()

    /** A process's face; the upload extension, and a simulator, have no App Attest: `available = false`. */
    fun port(available: Boolean): DeviceIntegrity = InMemoryDeviceIntegrity(available, keys)
}

/**
 * The platform's own UI, where the app hands something over: the share sheet, the URL opener and the Settings page.
 * Off device each is accepted and recorded, and nothing opens.
 */
class SystemUiMock {
    internal val shared = MutableStateFlow<List<String>>(emptyList())
    internal val opened = MutableStateFlow<List<String>>(emptyList())
    internal val settings = MutableStateFlow(0)

    fun port(): SystemUi = object : SystemUi {
        override suspend fun share(text: String): Handoff {
            shared.value = shared.value + text
            return Handoff.Accepted
        }

        override suspend fun openUrl(url: String): Handoff {
            opened.value = opened.value + url
            return Handoff.Accepted
        }

        override fun openSettings() {
            settings.value += 1
        }
    }

    val operator: SystemUiOperator = SystemUiOperator(this)
}

/** What the app handed the platform's UI. */
class SystemUiOperator internal constructor(mock: SystemUiMock) {
    /** Every text the share sheet was handed, in order. */
    val shared: StateFlow<List<String>> = mock.shared.asStateFlow()

    /** Every URL the app asked the platform to open, in order. */
    val opened: StateFlow<List<String>> = mock.opened.asStateFlow()

    /** How many times the app opened its Settings page. */
    val settingsOpened: StateFlow<Int> = mock.settings.asStateFlow()
}
