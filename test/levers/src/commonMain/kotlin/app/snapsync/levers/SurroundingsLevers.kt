package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.Serializable

// The device's surroundings as typed services (`docs/testing.md`, "Typed services"): what the operating system tells the
// app about its network, its power and its own process, the wall clock, and what the app hands the platform's own UI and
// the crash reporter. Each is a mock's operator face; a host whose app runs the real system refuses it.

/** **The device's network** as the operating system reports it to the app. */
@Rpc
interface NetworkLevers {
    /** The operating system reports [access] from now on: every watch of the network sees the change. */
    suspend fun set(access: NetworkState)

    /** What the operating system reports now. */
    suspend fun access(): NetworkState

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<NetworkLevers>(
        NetworkLevers::class,
        "network",
        setOf(LeverNeed.NETWORK_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The network as the operating system reports it. */
@Serializable
enum class NetworkState {
    /** A network, and one the member need not keep photos off. */
    ONLINE,

    /** A network the member may want photos kept off: mobile data, a hotspot, Low Data Mode or Data Saver. */
    RESTRICTED,

    /** No network at all. */
    OFFLINE,

    /** A network, withheld from this app (the member switched its mobile data off in Settings). */
    BLOCKED,
}

/**
 * **The device's power saving, battery, thermal state and background allowance**, as the operating system reports them
 * to the app — what a bug report reads.
 */
@Rpc
interface DeviceConditionsLevers {
    /**
     * Every field [fields] names, by the bug report's own key (`power_saving`, `background_refresh`, `standby_bucket`,
     * `battery_optimization_exempt`, `battery_percent`, `battery_charging`, `thermal`), set to its value's label,
     * `unsupported` or `failed:<reason>`; a field not named keeps its value. An unknown key or value is refused.
     */
    suspend fun set(fields: Map<String, String>)

    /** Every read is left unanswered — the platform call that stalls — until called with [on] `false`. */
    suspend fun hold(on: Boolean)

    /** What the operating system reports now, and whether its reads are held. */
    suspend fun reading(): DeviceConditionsView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<DeviceConditionsLevers>(
        DeviceConditionsLevers::class,
        "device-conditions",
        setOf(LeverNeed.DEVICE_CONDITIONS_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** What the device's conditions read: every field by the bug report's key, as [DeviceConditionsLevers.set] takes it. */
@Serializable
data class DeviceConditionsView(val fields: Map<String, String>, val held: Boolean)

/**
 * **The device's wall clock**, where nothing else moves it: a device host whose adapter choice mocks the clock. The
 * JVM host refuses it — its loop owns the clock, and `world.advanceTo` moves it, firing every timer due on the way.
 */
@Rpc
interface ClockLevers {
    /** Every read of the clock answers [at], an ISO instant, from now on. Nothing the app scheduled fires early. */
    suspend fun set(at: String)

    /** The instant every read of the clock answers now. */
    suspend fun now(): String

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ClockLevers>(
        ClockLevers::class,
        "clock",
        setOf(LeverNeed.CLOCK_MOCKED, LeverNeed.CLOCK_UNOWNED),
        { serviceDescriptorOf() },
    )
}

/** **What the operating system says of the app's own process**: whether protected data is readable, and its memory. */
@Rpc
interface ProcessInfoLevers {
    /** Whether the device's protected data is readable — `false` plays a phone locked since it restarted. */
    suspend fun protectedData(available: Boolean)

    /** The process's memory footprint, as the app reads it when it records one; `null` answers none. */
    suspend fun footprint(footprint: Footprint?)

    /** What the operating system says now. */
    suspend fun info(): ProcessInfoView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ProcessInfoLevers>(
        ProcessInfoLevers::class,
        "process-info",
        setOf(LeverNeed.PROCESS_INFO_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** A process's memory footprint: what the platform charges it, its peak and its headroom, each in bytes. */
@Serializable
data class Footprint(val bytes: Long, val peakBytes: Long? = null, val headroomBytes: Long? = null)

/** What the operating system says of the process now. */
@Serializable
data class ProcessInfoView(val protectedDataAvailable: Boolean, val footprint: Footprint?)

/** **The crash reporter's backend**: whether the app started reporting, and what reached it. */
@Rpc
interface CrashReporterLevers {
    /** Whether the observed process started reporting. */
    suspend fun started(): Boolean

    /** Every diagnostic dump the reporter's backend received, in order. */
    suspend fun sent(): List<ReceivedDump>

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<CrashReporterLevers>(
        CrashReporterLevers::class,
        "crash-reporter",
        setOf(LeverNeed.CRASH_REPORTER_MOCKED),
        { serviceDescriptorOf() },
    )
}

/**
 * One diagnostic dump as the reporter's backend received it: the member's [note], the app's [state] and [ledger]
 * sections, the tail of both logs, and how many bytes of log it carried.
 */
@Serializable
data class ReceivedDump(
    val note: String,
    val state: Map<String, String>,
    val ledger: Map<String, String>,
    val appLog: String,
    val extensionLog: String,
    val logBytes: Int,
)

/**
 * **The platform's own UI**, where the app hands something over: the share sheet, the URL opener and the Settings page.
 * Off device each is accepted and recorded, and nothing opens.
 */
@Rpc
interface SystemUiLevers {
    /** What the app handed the platform's UI so far. */
    suspend fun record(): SystemUiRecord

    /** The member leaves the app's Settings page, back to the app. */
    suspend fun leaveSettings()

    /** The app has no window to present from, [on] `true` — a share is refused, as on a device in the background. */
    suspend fun noWindow(on: Boolean)

    /** No app on the device presents the share sheet, [on] `true`: sharing is refused. */
    suspend fun noSheet(on: Boolean)

    /** No app on the device claims [urls]: opening one is refused. */
    suspend fun unclaimed(urls: List<String>)

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<SystemUiLevers>(
        SystemUiLevers::class,
        "system-ui",
        setOf(LeverNeed.SYSTEM_UI_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** What the app handed the platform's UI, each list in order. */
@Serializable
data class SystemUiRecord(
    /** Every text the share sheet was handed. */
    val shared: List<String>,
    /** The title each share carried, in the order of [shared]. */
    val sharedTitles: List<String>,
    /** Every URL the app asked the platform to open. */
    val opened: List<String>,
    /** How many times the app opened its Settings page. */
    val settingsOpened: Int,
    /** Whether the app's Settings page is in front. */
    val settingsInFront: Boolean,
)
