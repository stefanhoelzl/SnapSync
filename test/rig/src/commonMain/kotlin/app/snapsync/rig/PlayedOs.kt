@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.rig
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Running
import app.snapsync.levers.AtRelease
import app.snapsync.levers.Completions
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.Released
import app.snapsync.levers.WorldEvent
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.OsWatcher
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The operating system's side of the completion handlers the control channel hands the app when it PLAYS the
 * operating system (`docs/testing.md`, "One control protocol, served by two hosts"): what the OS itself would know
 * about them — how many it handed over, how many the app released, and how many releases arrived for a handler already
 * released — and the one thing only an OS can do to them, **expire** them. The `played-os` service ([PlayedOsLevers]).
 *
 * Played wherever the system is mocked: always on the JVM host, and on the app host for the systems an adapter choice mocks
 * (`docs/testing.md`, "Launch-time adapters"). A real operating system's expiry is its own, so the verb needs the
 * background-time holds mocked — the same signal a `BGTask`'s expiration handler and `UIApplication`'s background-time
 * expiry deliver, through the run each delivery answered and the background-time mock.
 */
class PlayedOs(
    private val device: MockDevice,
    private val mocked: (MockedSystem) -> Boolean = { true },
) : PlayedOsLevers {
    private val lock = AtomicBoolean(false)
    private val held = mutableListOf<Handler>()
    private var handed = 0
    private var released = 0
    private var releasedAgain = 0

    /** Set by [expireNext]: the next grant of time is handed over already withdrawn. */
    private var nextExpired = false

    /**
     * The app's process, as the host that owns it lets the operating system end it and open it again — `null` where the
     * host does not own it (a device's app host), which refuses a force-quit.
     */
    @Volatile var process: AppProcess? = null

    /** Set by [forceQuit] until the member opens the app ([opened]): the operating system launches it for nothing. */
    @Volatile private var quit = false

    /** Who is told each release — the control channel's event stream, or no one. */
    @Volatile private var watcher: OsWatcher = OsWatcher {}

    /** Tells [listener] each release of a handler, as the app releases it (`docs/testing.md`, "How a test waits"). */
    fun watch(listener: OsWatcher) {
        watcher = listener
    }

    /** A few-instruction critical section, shared by every thread a handler is released on. */
    private inline fun <T> locked(block: () -> T): T {
        while (true) {
            if (lock.compareAndSet(expectedValue = false, newValue = true)) break
        }
        try {
            return block()
        } finally {
            lock.store(false)
        }
    }

    /**
     * A completion handler for [done], counted and handed to [deliver] — the delivery it rides. Its time is the
     * app's own background time, which [expire] ends.
     */
    fun deliver(done: () -> Unit, deliver: (Completion) -> Unit) {
        deliver(handOver(done))
    }

    /**
     * A completion handler for [done], counted and handed to [deliver] — a grant of time (a scheduled wake), which
     * [expire] withdraws through the run the delivery answers.
     */
    fun grant(done: () -> Unit, deliver: (Completion) -> Running) {
        val handler = handOver(done)
        val expired = locked {
            nextExpired.also { nextExpired = false }
        }
        val running = deliver(handler)
        val expireNow = locked {
            handler.running = running
            (expired || handler.expired) && !handler.released
        }
        if (expireNow) running.expire()
    }

    private fun handOver(done: () -> Unit): Handler {
        val handler = Handler(done)
        locked {
            handed++
            held += handler
        }
        return handler
    }

    /**
     * The operating system says time is up: every handler it still holds is told so (its registered expiry runs), and
     * every outstanding background-time hold expires.
     */
    override suspend fun expire() {
        val expiring = locked { held.filterNot { it.released }.onEach { it.expired = true } }
        expiring.mapNotNull { it.running }.distinct().forEach { it.expire() }
        device.backgroundTime.expireAll()
    }

    /** The next grant of time handed over arrives already withdrawn. */
    override suspend fun expireNext() = locked { nextExpired = true }

    override suspend fun forceQuit() {
        val process = process ?: throw LeverRefused(FORCE_QUIT_NEEDS_THE_PROCESS)
        device.uploadSession.forceQuit()
        process.die()
        quit = true
        watcher.told(WorldEvent.ForceQuit)
    }

    /** Refuses [what] while the app is force-quit: the operating system does not launch it for that. */
    fun launching(what: String) {
        if (quit) {
            throw LeverRefused(
                "the app was force-quit and the member has not opened it since, so iOS delivers no $what",
            )
        }
    }

    /** The member opens the app: a force-quit app is launched cold, in front, and the operating system serves it again. */
    fun opened() {
        if (!quit) return
        quit = false
        checkNotNull(process).open()
        // The opened app brings its session up, and iOS reports the transfers the force-quit cancelled.
        device.uploadSession.adopted()
    }

    override suspend fun completions(): Completions =
        locked { Completions(handed, released, releasedAgain, held.count { !it.released }) }

    /**
     * What the device held at a completion handler's release ([Released.atRelease]), read off the mocks' operator faces on
     * the releasing thread. A system the launch leaves real keeps its own counsel, so its field is `null` rather than
     * read off an idle mock.
     */
    fun atRelease(): AtRelease = AtRelease(
        stagedFiles = device.disk.stagedFiles().takeIf { mocked(MockedSystem.FILES) },
        uploadSessionHandbacks = device.uploadSession.view().handbacks.takeIf { mocked(MockedSystem.UPLOAD_SESSION) },
    )

    private inner class Handler(private val done: () -> Unit) : Completion {
        var released = false
        var expired = false
        var running: Running? = null

        override fun complete() {
            val first = locked {
                if (released) {
                    releasedAgain++
                    false
                } else {
                    released = true
                    this@PlayedOs.released++
                    true
                }
            }
            watcher.told(WorldEvent.CompletionReleased(again = !first))
            if (first) done()
        }
    }
}

/** The app's process, as a host that owns it hands it to the played operating system. */
interface AppProcess {
    /** The process dies and is not started again: nothing is delivered and no screen is built. */
    suspend fun die()

    /** The member opens the app: its screen is built, as a cold foreground launch builds it. */
    fun open()
}

/** Why a host that does not own the app's process refuses a force-quit. */
const val FORCE_QUIT_NEEDS_THE_PROCESS: String =
    "this host does not own the app's process, so it cannot end it as a force-quit does — the JVM host's"
