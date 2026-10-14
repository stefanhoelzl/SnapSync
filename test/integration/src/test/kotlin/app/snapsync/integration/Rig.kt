package app.snapsync.integration

import app.snapsync.control.Reply
import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.domain.asset.AssetId
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.BackgroundTimeLevers
import app.snapsync.levers.BuildLevers
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.CrashReporterLevers
import app.snapsync.levers.DatabasesLevers
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DeviceConditionsLevers
import app.snapsync.levers.DownloadSessionLevers
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.FilesLevers
import app.snapsync.levers.KeychainLevers
import app.snapsync.levers.LibraryLevers
import app.snapsync.levers.NetworkLevers
import app.snapsync.levers.OlderInstallLevers
import app.snapsync.levers.OsLevers
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.PushLevers
import app.snapsync.levers.SystemUiLevers
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.UploadSessionLevers
import app.snapsync.levers.WakeLevers
import app.snapsync.levers.WorldLevers
import app.snapsync.rig.GalleryView
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigState
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One integration test's host: a fresh control-channel JVM host, in-process, over the backend mock — closed when the
 * test ends. The test drives it through [Rig] and nothing else (`docs/testing.md`, "The
 * seam-to-UI-state integration surface"), so a test body names no mock, port or composition type.
 */
fun rigTest(body: suspend Rig.() -> Unit): Unit = runBlocking {
    // The app reads the clock mock, never the wall clock: every test starts inside the default event window, and one
    // that needs another moment moves the clock itself (`world.advanceTo`).
    val host = JvmRigHost.start("mock", startAt = Instant.parse(Rig.NOW))
    try {
        RigClient("http://127.0.0.1:${host.port}").use { client -> Rig(client).body() }
        // The host's loop saw every task, or the test's waits could not be trusted (`docs/testing.md`, "How a test waits").
        assertTrue(
            host.violations.isEmpty(),
            "work reached the app from a thread the host's loop does not own:\n  " + host.violations.joinToString("\n  "),
        )
    } finally {
        host.close()
    }
}

/**
 * The protocol, spoken the way the integration tests speak it: [RigClient]'s calls, and a few compositions of them
 * that every test would otherwise spell out. Each composition is only protocol calls, named for what a person or
 * the operating system does.
 *
 * Every assertion a test makes reads through here — the reduced [RigState.ui], or what a system outside the app
 * recorded (the backend, the photo library, the operating system's jobs and its record of the storage the app takes, the
 * reporter, the pushes sent). Never the ledger: it is the app's own bookkeeping, and the protocol no longer carries it.
 */
class Rig(val client: RigClient) {
    /** The backend operator's levers and reads — the backend mock's, refused where the backend is real. */
    val backend: BackendLevers = client.levers(BackendLevers)

    /** The backend through its public surface, as another member reaches it — on either backend. */
    val publicBackend: PublicBackendLevers = client.levers(PublicBackendLevers)

    /** The operating system's upload-job queue, which the OS-driven extension creates jobs on. */
    val uploadQueue: UploadQueueLevers = client.levers(UploadQueueLevers)

    /** The app uploader's background transfer session. */
    val uploadSession: UploadSessionLevers = client.levers(UploadSessionLevers)

    /** The background download session. */
    val downloadSession: DownloadSessionLevers = client.levers(DownloadSessionLevers)

    /** The photo library operator's levers and reads — the library mock's. */
    val library: LibraryLevers = client.levers(LibraryLevers)

    /** The device's network, as the operating system reports it to the app. */
    val network: NetworkLevers = client.levers(NetworkLevers)

    /** The device's power, battery, thermal state and background allowance, as the operating system reports them. */
    val deviceConditions: DeviceConditionsLevers = client.levers(DeviceConditionsLevers)

    /** What the crash reporter's backend received. */
    val crashReporter: CrashReporterLevers = client.levers(CrashReporterLevers)

    /** What the app handed the platform's own UI. */
    val systemUi: SystemUiLevers = client.levers(SystemUiLevers)

    /** The build's development controls. */
    val devControls: DevControlsLevers = client.levers(DevControlsLevers)

    /** The build itself: the version it declares. */
    val build: BuildLevers = client.levers(BuildLevers)

    private val world: WorldLevers = client.levers(WorldLevers)

    private val clock: ClockLevers = client.levers(ClockLevers)

    /**
     * The clock moves to [at], an ISO instant: what the app reads from then on. The JVM host's world moves it, with the
     * timers due on the way; a device host, which serves no world, sets its mocked clock (`docs/testing.md`, "How a
     * test waits").
     */
    suspend fun advanceTo(at: String) =
        if (WORLD_ADVANCE in client.device().honoured) world.advanceTo(at) else clock.set(at)

    /** The device's databases' faults. */
    val databases: DatabasesLevers = client.levers(DatabasesLevers)

    /** What a test may add to the device's files. */
    val files: FilesLevers = client.levers(FilesLevers)

    /** The device's Keychain. */
    val keychain: KeychainLevers = client.levers(KeychainLevers)

    /** What an older build left on the device. */
    val olderInstall: OlderInstallLevers = client.levers(OlderInstallLevers)

    /** The operating system's deliveries to the app's entry points. */
    val os: OsLevers = client.levers(OsLevers)

    /** What only a played operating system does — its expiry — and its record of the completion handlers. */
    val playedOs: PlayedOsLevers = client.levers(PlayedOsLevers)

    /** The operating system's invocations of the upload extension. */
    val extensionHost: ExtensionHostLevers = client.levers(ExtensionHostLevers)

    /** The push service: what it is, whether it issues tokens, and what the app asked of it. */
    val push: PushLevers = client.levers(PushLevers)

    /** The operating system's scheduled wakes. */
    val wake: WakeLevers = client.levers(WakeLevers)

    /** The operating system's background-time holds. */
    val backgroundTime: BackgroundTimeLevers = client.levers(BackgroundTimeLevers)

    suspend fun state(): RigState = client.state()

    suspend fun awaitState(timeout: Duration = 10.seconds, until: (RigState) -> Boolean): RigState =
        client.awaitState(timeout, until)

    /** A user command, which must be accepted. */
    suspend fun user(name: String, vararg params: Pair<String, String>): String = client.user(
        name,
        mapOf(*params),
    ).done()

    /** A user command, answered however the host answers it. */
    suspend fun userReply(name: String, vararg params: Pair<String, String>): Reply = client.user(name, mapOf(*params))

    /** A device verb, which must be honoured. */
    suspend fun device(name: String, vararg params: Pair<String, String>, body: String? = null): String =
        client.deviceVerb(name, mapOf(*params), body).done()

    /** A device verb's JSON answer. */
    suspend fun deviceJson(name: String, vararg params: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(device(name, *params)).jsonObject

    /**
     * The photo library through the app's own candidate seam. The route lists assets only under a cutoff, so the
     * default one admits every capture date: the whole library, each with its verdict under that cutoff.
     */
    suspend fun gallery(resources: Boolean = false, cutoff: String = WHOLE_LIBRARY): GalleryView =
        client.gallery(cutoff = cutoff, resources = resources)

    /** Wait until the joined screen's health satisfies [until]. */
    suspend fun awaitHealth(timeout: Duration = 10.seconds, until: (SyncHealth) -> Boolean): SyncHealth =
        awaitState(timeout) { s -> s.joined?.health?.let(until) == true }.joined!!.health

    suspend fun awaitInSync(): SyncHealth = awaitHealth { it == SyncHealth.InSync }

    /**
     * Assert [condition] never holds for [window] — the only way to observe that something does NOT happen. Keep it
     * for negatives the rest of a test cannot prove another way.
     */
    suspend fun neverWithin(window: Duration = 500.milliseconds, what: String, condition: (RigState) -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + window
        while (deadline.hasNotPassedNow()) {
            val s = state()
            if (condition(s)) fail("$what — but it did: $s")
            delay(POLL)
        }
    }

    /** Assert [read] satisfies [condition] for the whole [window] — a true negative over a system outside the app. */
    suspend fun <T> holdsWithin(
        window: Duration = 500.milliseconds,
        what: String,
        read: suspend () -> T,
        condition: (T) -> Boolean,
    ) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + window
        while (deadline.hasNotPassedNow()) {
            val value = read()
            assertTrue(condition(value), "$what — but it read $value")
            delay(POLL)
        }
    }

    /** Poll [read] until it satisfies [until], failing after [timeout] with the last value. */
    suspend fun <T> eventually(timeout: Duration = 10.seconds, read: suspend () -> T, until: (T) -> Boolean): T {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + timeout
        var last = read()
        while (!until(last)) {
            assertTrue(deadline.hasNotPassedNow(), "never reached within $timeout; last: $last")
            delay(POLL)
            last = read()
        }
        return last
    }

    class Jobs(val live: List<String>, val created: Int) {
        override fun toString() = "Jobs(live=$live, created=$created)"
    }

    companion object {
        /** The world's clock, as a host advertises it: the JVM host's, never a device's. */
        private const val WORLD_ADVANCE = "world.advanceTo"

        const val EVENT_NAME = "Anna's Birthday"

        /** The capture date every added photo carries by default — inside the default event window. */
        const val PHOTO_DATE = "2026-06-01T10:00:00Z"

        /** The default event window: 30 days, containing [PHOTO_DATE]. Local date-times, as the create form takes. */
        const val WINDOW_START = "2026-05-15T00:00:00"
        const val WINDOW_END = "2026-06-14T00:00:00"

        /** Where every test's mocked clock starts: inside the default window, on the day [PHOTO_DATE] was taken. */
        const val NOW = "2026-06-01T12:00:00Z"

        /** A cutoff before any capture date, so a gallery read under it lists the whole library. */
        const val WHOLE_LIBRARY = "1970-01-01T00:00:00Z"

        private val POLL = 50.milliseconds
    }
}

/** The joined screen, when this state is one. */
val RigState.joined: Layer.Joined? get() = ui.layer as? Layer.Joined

/** The joined screen's health, when joined. */
val RigState.health: SyncHealth? get() = joined?.health

/** The primary upload key of an own photo [assetId] with the default capture name — what the backend lists. */
fun primaryKey(assetId: String, filename: String = "IMG.JPG"): String =
    app.snapsync.model.uploadKey(AssetId(assetId), app.snapsync.model.ResourceRole.PRIMARY, filename)

/**
 * The OS's upload job for an own photo [assetId]'s primary: named by its destination's last two segments, as the OS
 * keeps nothing else of what a job is for.
 */
fun primaryJob(assetId: String): String = "$assetId/primary"

/** The photo an OS upload job ([primaryJob]) sends. */
fun assetIdOfJob(job: String): AssetId = AssetId(job.substringBefore('/'))

/**
 * The fellow member's device most tests play — `foreignDevice(DEV_F, …)`. A device id, as the backend issues every one:
 * the app reads no other, so neither does a fixture.
 */
const val DEV_F = "def0f000-0000-4000-8000-00000000000f"

/** The guest the marketing scenes and the full-stack tests play. */
const val GUEST_DEVICE = "9e570000-0000-4000-8000-0000000000a1"
