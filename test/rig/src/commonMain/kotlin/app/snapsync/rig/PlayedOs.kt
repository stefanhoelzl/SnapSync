@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.rig
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Running
import app.snapsync.levers.Completions
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.WorldEvent
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.OsWatcher
import app.snapsync.model.FileArea
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    override suspend fun expire() = expireNow()

    /** [expire], from a caller that does not suspend: the string verb's. */
    fun expireNow() {
        val expiring = locked { held.filterNot { it.released }.onEach { it.expired = true } }
        expiring.mapNotNull { it.running }.distinct().forEach { it.expire() }
        device.backgroundTime.expireAll()
    }

    /** The next grant of time handed over arrives already withdrawn. */
    override suspend fun expireNext() = expireNextNow()

    /** [expireNext], from a caller that does not suspend: the string verb's. */
    fun expireNextNow() = locked { nextExpired = true }

    override suspend fun completions(): Completions =
        locked { Completions(handed, released, releasedAgain, held.count { !it.released }) }

    /**
     * What the operating system recorded of the app, read off the mocks' operator faces — `/device/os-record`. A
     * system the launch leaves real keeps its own counsel, so its fields are absent rather than read off an idle mock.
     *
     * Only the systems not yet typed services: the completion handlers, the wakes, the background-time holds and the
     * push registrations are read through `played-os`, `wake`, `background-time` and `push`; each other system's fields
     * leave as its batch types it (`docs/testing.md`, "Typed services").
     */
    fun record(): String = buildJsonObject {
        if (mocked(MockedSystem.SCREEN)) put("screenShown", device.screen.operator.shown.value != null)
        if (mocked(MockedSystem.LIBRARY)) {
            put("selectionObserved", device.library.selectionObserved)
            put("pickersShown", device.library.pickersShown)
        }
        if (mocked(MockedSystem.DOWNLOADS)) {
            val session = device.downloads.view()
            putJsonObject("downloadSession") {
                put("up", session.up)
                put("started", session.started.size)
                put("inFlight", session.inFlight.size)
            }
        }
        if (mocked(MockedSystem.UPLOAD_SESSION)) put("uploadSessionHandbacks", device.uploadSession.view().handbacks)
        // The device's disk: every database any process opened, and the files in the download staging directory.
        if (mocked(MockedSystem.DATABASES)) {
            putJsonArray("databasesOpened") { device.databases.opened.forEach { add(JsonPrimitive(it)) } }
        }
        // Counted over the operator's copy, never the live area: the app writes the disk from its own thread while this
        // request runs on the server's, and iterating the live map died half-way on a concurrent stage or release.
        if (mocked(MockedSystem.FILES)) {
            put(
                "stagedFiles",
                device.disk.paths(FileArea.SHARED).count { it.startsWith("$DOWNLOAD_STAGING_DIR/") },
            )
            // The storage the app takes, as the device's storage page shows it — `Settled.state` carries the same.
            put("appStorageBytes", device.disk.appStorageBytes())
        }
    }.toString()

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
