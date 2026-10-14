package app.snapsync.control.behaviour

import app.snapsync.control.Person
import app.snapsync.control.RigClient
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.BuildLevers
import app.snapsync.levers.DatabasesLevers
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DeviceConditionsLevers
import app.snapsync.levers.DownloadSessionLevers
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.FilesLevers
import app.snapsync.levers.GalleryLevers
import app.snapsync.levers.HostLevers
import app.snapsync.levers.KeychainLevers
import app.snapsync.levers.LibraryLevers
import app.snapsync.levers.LinksLevers
import app.snapsync.levers.NetworkLevers
import app.snapsync.levers.OlderInstallLevers
import app.snapsync.levers.OsLevers
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.ProcessInfoLevers
import app.snapsync.levers.ProcessMetricsLevers
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.PushLevers
import app.snapsync.levers.ScreenLevers
import app.snapsync.levers.SystemUiLevers
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.UploadSessionLevers
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WorldLevers
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.time.LoopTime
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * **One behaviour of the app, as a test states it** (`docs/testing.md`, "How a test waits"): a fresh JVM host over mocks
 * on mocked time starting at [startAt], the world's events streamed from its start, and an ordered chain of [given]
 * steps and [whenever]/[then] pairs — each `then` judged against the events from its own act on.
 *
 * A test waits in exactly one way: on the event it cares about. There is no poll, no timeout to pass and no read of the
 * app's own state; a negative holds once the app has settled. The one bound is the guard: a behaviour that has not
 * finished in [GUARD] fails with why the app is not settled — a hung harness, never a slow app.
 */
fun behaviour(backend: String = "mock", startAt: String = NOW, body: suspend Behaviour.() -> Unit): Unit = runBlocking {
    val host = JvmRigHost.start(backend, time = LoopTime.MOCKED, startAt = Instant.parse(startAt))
    try {
        RigClient("http://127.0.0.1:${host.port}").use { client ->
            val world = client.levers(WorldLevers)
            val seen = MutableStateFlow<List<WorldEvent>>(emptyList())
            coroutineScope {
                // From the stream's start, so nothing the first act causes can be missed.
                val stream = launch(start = CoroutineStart.UNDISPATCHED) {
                    world.events().collect { event -> seen.update { it + event } }
                }
                val behaviour = Behaviour(client, world, seen)
                val finished = withTimeoutOrNull(GUARD) { behaviour.body() }
                stream.cancel()
                if (finished == null) throw AssertionError(behaviour.hung())
            }
        }
        if (host.violations.isNotEmpty()) {
            throw AssertionError(
                "work reached the app from a thread the host's loop does not own:\n  " + host.violations.joinToString("\n  "),
            )
        }
    } finally {
        host.close()
    }
}

/** Where every behaviour's mocked clock starts: inside the default event window, on the day its photos were taken. */
const val NOW = "2026-06-01T12:00:00Z"

/** The one bound on a behaviour: long past anything the app does on mocked time, so only a hung harness reaches it. */
val GUARD: Duration = 30.seconds

/** A behaviour under way: its acts, its expectations, and the events seen so far. */
class Behaviour internal constructor(
    private val client: RigClient,
    private val world: WorldLevers,
    private val seen: MutableStateFlow<List<WorldEvent>>,
) {
    private val acts = Acts(client) { what, shown -> awaitShown(what, shown) }
    private var acted = 0
    private var current: String = "the start"
    private var from: Int = 0

    /** Where, from [from], the act's last request was announced: a settle before it is between its requests. */
    private var last: Int = 0

    /** Steps that bring the world to where the behaviour starts, each awaiting its own outcome ([Given.step]). */
    suspend fun <T> given(steps: suspend Given.() -> T): T = Given(this).steps()

    /** The one act the next [then] is judged from. */
    suspend fun whenever(act: suspend Acts.() -> Unit) {
        act("whenever", act)
    }

    /** What the last act led to, judged against the events from that act on ([Expectations]). */
    suspend fun then(expect: Expectations.() -> Unit) = judge("then", expect)

    internal suspend fun <T> act(name: String, act: suspend Acts.() -> T): T {
        val tag = "act-${++acted}"
        current = name
        world.tagActs(tag)
        val answer = try {
            acts.act()
        } finally {
            world.tagActs(null)
        }
        val made = world.actsTagged(tag)
        check(made > 0) { "$name made no act: a `whenever` or a step acts — a read alone changes nothing to wait on" }
        // Every one of its acts announced, which every event they caused follows.
        val events = seen.first { list -> list.count { it is WorldEvent.Acted && it.tag == tag } == made }
        from = events.indexOfFirst { it is WorldEvent.Acted && it.tag == tag }
        last = events.indexOfLast { it is WorldEvent.Acted && it.tag == tag } - from
        return answer
    }

    internal suspend fun judge(name: String, expect: Expectations.() -> Unit) {
        val judge = Judge(Expectations().apply(expect).all, settlesFrom = last)
        val decided = seen.first { list -> judge.verdict(list.subList(from, list.size)) != Verdict.Pending }
        val events = decided.subList(from, decided.size)
        if (judge.verdict(events) == Verdict.Failed) {
            throw AssertionError(
                "$name, after $current:\n${judge.report(events)}",
            )
        }
        judge.keep(events)
    }

    /** Returns once the screen's latest state satisfies [shown] — an event already seen, or the next one. */
    private suspend fun awaitShown(what: String, shown: (UiState) -> Boolean) {
        current = "$current, waiting for the screen to show $what"
        seen.first { list ->
            val latest = list.lastOrNull { it is WorldEvent.UiChanged } as WorldEvent.UiChanged?
            latest != null && shown(latest.ui)
        }
    }

    /** What a hung behaviour reports: where it was, the events since, and why the app is not settled. */
    internal suspend fun hung(): String {
        val events = seen.value
        return "the behaviour did not finish within $GUARD, waiting after $current.\n" +
            "why the app is not settled: ${world.whyNotSettled()}\n" +
            "events since the act:\n" + events.subList(from.coerceAtMost(events.size), events.size).render()
    }
}

/** The steps of a [Behaviour.given]: each an act and the outcome it awaits. Shared steps are extensions on this. */
class Given internal constructor(private val behaviour: Behaviour) {
    /**
     * One named step: [act], then wait for [until] — judged as a `then` is, so a step that cannot happen fails fast.
     * Answers what the act answered.
     */
    suspend fun <T> step(name: String, act: suspend Acts.() -> T, until: Expectations.() -> Unit): T =
        behaviour.act("given: $name", act).also { behaviour.judge("given: $name", until) }
}

/**
 * What an act may do — and nothing else: what a person does, what the operating system does, what the device's
 * mocked systems do, and the clock. There is no read here: a test learns the outcome from the events.
 */
class Acts internal constructor(
    private val client: RigClient,
    private val awaitShown: suspend (String, (UiState) -> Boolean) -> Unit,
) : Person {
    /** The screen a person taps. */
    override val screen: ScreenLevers = client.levers(ScreenLevers)

    override suspend fun awaitScreen(what: String, shown: (UiState) -> Boolean) = awaitShown(what, shown)

    /** The backend operator's levers — the backend mock's. */
    val backend: BackendLevers = client.levers(BackendLevers)

    /** The backend through its public surface, as another member reaches it. */
    val publicBackend: PublicBackendLevers = client.levers(PublicBackendLevers)

    /** The operating system's upload-job queue: its cap, and the jobs it performs or fails. */
    val uploadQueue: UploadQueueLevers = client.levers(UploadQueueLevers)

    /** The app uploader's background transfer session: the transfers it lands. */
    val uploadSession: UploadSessionLevers = client.levers(UploadSessionLevers)

    /** The background download session: the transfers it finishes, and its drain report. */
    val downloadSession: DownloadSessionLevers = client.levers(DownloadSessionLevers)

    /** The photo library operator's levers — the library mock's. */
    val library: LibraryLevers = client.levers(LibraryLevers)

    /** The device's network, as the operating system reports it to the app. */
    val network: NetworkLevers = client.levers(NetworkLevers)

    /** The device's power, battery, thermal state and background allowance, as the operating system reports them. */
    val deviceConditions: DeviceConditionsLevers = client.levers(DeviceConditionsLevers)

    /** What the operating system says of the app's process: its protected data and its memory footprint. */
    val processInfo: ProcessInfoLevers = client.levers(ProcessInfoLevers)

    /** The platform's reports on the app's process, which it hands the app as they come — the JVM host's mock. */
    val processMetrics: ProcessMetricsLevers = client.levers(ProcessMetricsLevers)

    /** The platform's own UI: what refuses a hand-over, and the member leaving the Settings page. */
    val systemUi: SystemUiLevers = client.levers(SystemUiLevers)

    /** The build's development controls. */
    val devControls: DevControlsLevers = client.levers(DevControlsLevers)

    /** The build itself: the version it declares. */
    val build: BuildLevers = client.levers(BuildLevers)

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

    /** What only a played operating system does: its expiry. */
    val playedOs: PlayedOsLevers = client.levers(PlayedOsLevers)

    /** The operating system's invocations of the upload extension. */
    val extensionHost: ExtensionHostLevers = client.levers(ExtensionHostLevers)

    /** The push service: what it is, and whether it issues tokens. */
    val push: PushLevers = client.levers(PushLevers)

    /** The links only an installation's store delivers. */
    val links: LinksLevers = client.levers(LinksLevers)

    /** The host's process: its relaunch and reinstall, and the invite of an event it created. */
    val host: HostLevers = client.levers(HostLevers)

    /** The device's photo library. */
    val gallery: GalleryLevers = client.levers(GalleryLevers)

    private val world: WorldLevers = client.levers(WorldLevers)

    /** The mocked clock moves [by], each timer due on the way firing at its own instant. */
    suspend fun advance(by: Duration) = world.advance(by.inWholeMilliseconds)

    /** The mocked clock moves to [at], an ISO instant, each timer due on the way firing at its own instant. */
    suspend fun advanceTo(at: String) = world.advanceTo(at)
}
