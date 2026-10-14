package app.snapsync.rig

import app.snapsync.jvm.JvmLanes
import app.snapsync.jvm.JvmMocks
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.SettledState
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WorldLevers
import app.snapsync.logging.JvmThreadLabel
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.InviteLinkHints
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.ThreadLabel
import app.snapsync.rig.time.Interleaving
import app.snapsync.rig.time.LaneLoop
import app.snapsync.rig.time.LoopClock
import app.snapsync.rig.time.LoopTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * **The JVM host's threads** (`docs/testing.md`, "How a test waits"): its [loop], and every lane both processes and the
 * channel run on — each a lane of the loop, so the loop sees every task — plus the one thread that is not: the operating
 * system's, which the extension's `process()` is invoked on and blocks until the answer, counted as work outside the app
 * for as long as it runs.
 *
 * Built before the app, over the device's mocks, so the stream hears everything from the first composition on: what the
 * app shows ([JvmMocks.screen]), what the backend records, each act, each settle and each lane rule broken.
 */
internal class HostThreads private constructor(
    val time: LoopTime,
    val mocks: JvmMocks,
    val loop: LaneLoop,
    val events: WorldEvents,
    /** The OS upload's network to a real backend, its requests counted as outside work; `null` for the mocks' byte route. */
    val network: UploadNetwork?,
    private val answersOnTheLoop: Boolean,
    /** Every lane rule broken, in order. */
    val violations: List<String>,
) : AutoCloseable {

    /** The app's composition lane. */
    val composition: CoroutineDispatcher = loop.lane("composition", checked = true)

    /** The CPU lane presentation reduces on. */
    val reduction: CoroutineDispatcher = loop.lane("reduction", checked = true)

    /** The upload extension's lane: its detached work, its expiry grace, and its `process()`'s cycle. */
    val extension: CoroutineDispatcher = loop.lane("extension", checked = true)

    /** The channel's own lane, which every act runs on: the rig's, so a server thread may resume it. */
    val operator: CoroutineDispatcher = loop.lane("operator", checked = false)

    /** The operating system's thread the extension's `process()` is invoked on. */
    val osThread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor {
            Thread(
                it,
                "os-extension",
            ).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /** The lanes the JVM root composes both processes on. */
    val lanes = JvmLanes(
        composition = composition,
        reduction = reduction,
        extension = extension,
        // On mocked time the cycle runs on the extension's lane while the operating system's thread waits, so the loop
        // sees its timers. On real time it answers on that thread, in the blocking call's own loop, as on every phone.
        extensionAnswers = if (time == LoopTime.MOCKED) extension else null,
        threadLabel = { CheckedThreadLabel(loop) },
    )

    /** Every act a caller makes: a task of the [operator] lane, announced first. */
    val acts: RigActs = LoopActs(operator, events)

    /**
     * [transport], its requests counted as work outside the app while the backend answers them — unless the backend is
     * the mock, which answers on the requesting lane: a request it holds is parked, not in flight.
     */
    fun counted(transport: HttpTransport): HttpTransport =
        if (answersOnTheLoop) {
            transport
        } else {
            HttpTransport { request ->
                loop.outside(
                    "a request to the backend",
                ) { transport.send(request) }
            }
        }

    override fun close() {
        loop.close()
        osThread.close()
    }

    companion object {
        fun open(time: LoopTime, startAt: Instant?, backend: RigBackend): HostThreads {
            val events = WorldEvents()
            var opened: HostThreads? = null
            // An OS upload's request to a real backend crosses its network off the loop, so the loop waits on it.
            val network = backend.network?.let { network ->
                UploadNetwork { url, headers, bytes ->
                    checkNotNull(
                        opened,
                    ).loop.outside("an operating-system upload's request") { network.put(url, headers, bytes) }
                }
            }
            // Invite-link hints honoured, as the rig's development controls answer them on a device: this host IS the
            // control channel, whose callers join headlessly with `autoJoin`.
            val mocks = JvmMocks(inviteLinkHints = InviteLinkHints.Honoured, network = network)
            startAt?.let { mocks.clock.now = it }
            val clock = object : LoopClock {
                override var now: Instant by mocks.clock::now
            }
            val violations = CopyOnWriteArrayList<String>()
            val loop = LaneLoop(
                time,
                Interleaving.parse(System.getProperty(LANES_PROPERTY)),
                clock,
                onSettled = {
                    val state = SettledState(appStorageBytes = mocks.disk.appStorageBytes())
                    events.tell(WorldEvent.Settled(it.now.toString(), it.nextTimer?.toString(), state))
                },
                onViolation = {
                    violations += it
                    events.tell(WorldEvent.LaneViolation(it))
                },
            )
            mocks.backend.watch { events.tell(it) }
            mocks.library.watch { events.tell(it) }
            mocks.screen.watch { events.tell(WorldEvent.UiChanged(it)) }
            mocks.uploadQueue.watch { events.tell(it) }
            mocks.uploadSession.watch { events.tell(it) }
            mocks.downloads.watch { events.tell(it) }
            mocks.systemUi.watch { events.tell(it) }
            mocks.crashReporter.watch { events.tell(it) }
            mocks.databases.watch { events.tell(it) }
            // What the operating system records of the app: its wakes, holds, push registrations and extension
            // registration (the completion handlers are the played OS's own, told where it is made).
            mocks.wakes.watch { events.tell(it) }
            mocks.backgroundTime.watch { events.tell(it) }
            mocks.pushService.watch { events.tell(it) }
            mocks.extensionRegistry.watch { events.tell(it) }
            return HostThreads(
                time,
                mocks,
                loop,
                events,
                network,
                backend.answersOnTheLoop,
                violations,
            ).also { opened = it }
        }

        /** The system property naming how the lanes take turns (`turns` · `shuffled:<seed>` · `parallel`). */
        const val LANES_PROPERTY = "snapsync.lanes"
    }
}

/** Every act: a task of [lane], announced to [events] before it does anything. */
private class LoopActs(private val lane: CoroutineDispatcher, private val events: WorldEvents) : RigActs {
    override suspend fun <T> act(name: String, block: suspend () -> T): T = withContext(lane) {
        events.acted(name)
        block()
    }

    override suspend fun <T> read(block: suspend () -> T): T = withContext(lane) { block() }
}

/** The world service the JVM host serves: its events, its clock and why it is not settled. */
internal class JvmWorld(private val threads: HostThreads) : WorldLevers {
    override fun events(): Flow<WorldEvent> = threads.events.flow()

    override suspend fun tagActs(tag: String?) = threads.events.tagActs(tag)

    override suspend fun actsTagged(tag: String): Int = threads.events.actsTagged(tag)

    override suspend fun advance(millis: Long) {
        if (threads.time != LoopTime.MOCKED) {
            throw LeverRefused(
                "world.advance is refused here: this host's timers run on the wall clock, which nothing advances",
            )
        }
        threads.acts.act("world.advance") { threads.loop.advance(millis.milliseconds) }
    }

    override suspend fun advanceTo(at: String) {
        val to = Instant.parse(at)
        threads.acts.act("world.advanceTo") {
            // Under real time the app's timers keep the wall clock, so only the clock it reads moves: nothing is due.
            if (threads.time == LoopTime.MOCKED) threads.loop.advanceTo(to) else threads.mocks.clock.now = to
        }
    }

    override suspend fun whyNotSettled(): String = threads.loop.report()
}

/**
 * The JVM's thread label, each write checked against the loop: the runtime labels a thread only for a task it runs, so a
 * label written on a thread the loop does not own — while no outside work is counted — is app work the loop cannot see.
 */
private class CheckedThreadLabel(private val loop: LaneLoop) : ThreadLabel {
    private val label = JvmThreadLabel()

    override fun swap(label: String?): String? {
        loop.checkReach("a thread labelled for $label")
        return this.label.swap(label)
    }

    override fun current(): String? = label.current()
}
