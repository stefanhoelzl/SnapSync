@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.rig

import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.FileArea
import app.snapsync.ports.Completion
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The operating system's side of the completion handlers the control channel hands the app when it PLAYS the
 * operating system (`docs/testing.md`, "One control protocol, served by two hosts"): what the OS itself would know
 * about them — how many it handed over, how many the app released, and how many releases arrived for a handler already
 * released — and the one thing only an OS can do to them, **expire** them (`/os/app/onExpiry`).
 *
 * Played wherever the system is mocked: always on the JVM host, and on the app host for the systems an adapter choice mocks
 * (`docs/testing.md`, "Launch-time adapters"). A real operating system's expiry is its own, so the verb needs the
 * background-time holds mocked — the same signal a `BGTask`'s expiration handler and `UIApplication`'s background-time
 * expiry deliver, through the same `onExpired` registration and the background-time mock.
 */
class PlayedOs(private val device: MockDevice, private val mocked: (MockedSystem) -> Boolean = { true }) {
    private val lock = AtomicBoolean(false)
    private val held = mutableListOf<Handler>()
    private var handed = 0
    private var released = 0
    private var releasedAgain = 0

    /** Set by `onExpiry?arg=next`: the next handler is handed over with its time already up. */
    private var nextExpired = false

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

    /** A completion handler for [done], counted, and expirable by [expire]. */
    fun completion(done: () -> Unit): Completion {
        val handler = Handler(done)
        locked {
            handed++
            if (nextExpired) {
                nextExpired = false
                handler.expired = true
            }
            held += handler
        }
        return handler
    }

    /**
     * The operating system says time is up: every handler it still holds is told so (its registered expiry runs), and
     * every outstanding background-time hold expires.
     */
    fun expire() {
        val expiring = locked { held.filterNot { it.released }.onEach { it.expired = true } }
        expiring.forEach { it.expiry?.invoke() }
        device.backgroundTime.operator.expireAll()
    }

    /** The next handler handed over arrives with its time already up. */
    fun expireNext() = locked { nextExpired = true }

    /**
     * What the operating system recorded of the app, read off the mocks' operator faces — `/device/os-record`. A
     * system the launch leaves real keeps its own counsel, so its fields are absent rather than read off an idle mock.
     */
    fun record(): String = buildJsonObject {
        locked {
            putJsonObject("completions") {
                put("handed", handed)
                put("released", released)
                put("releasedAgain", releasedAgain)
                put("held", held.count { !it.released })
            }
        }
        if (mocked(MockedSystem.SCREEN)) put("screenShown", device.screen.operator.shown.value != null)
        if (mocked(MockedSystem.LIBRARY)) put("selectionObserved", device.library.operator.observing)
        if (mocked(MockedSystem.WAKE)) put("heartbeatsScheduled", device.wakes.operator.heartbeatsScheduled)
        // By the name the app began each under, as `beginBackgroundTask(withName:)` names them to the system.
        if (mocked(MockedSystem.BACKGROUND_TIME)) {
            putJsonArray("backgroundTimeHolds") { device.backgroundTime.operator.holds.value.forEach { add(JsonPrimitive(it.label)) } }
        }
        if (mocked(MockedSystem.PUSH)) put("pushRegistrations", device.pushService.operator.registrations)
        if (mocked(MockedSystem.DOWNLOADS)) {
            putJsonObject("downloadSession") {
                put("up", device.downloads.operator.realized)
                put("started", device.downloads.operator.started.size)
                put("inFlight", device.downloads.operator.inFlight().size)
            }
        }
        if (mocked(MockedSystem.UPLOAD_SESSION)) put("uploadSessionHandbacks", device.uploadSession.operator.handbacks)
        // The device's disk: every database any process opened, and the files in the download staging directory.
        if (mocked(MockedSystem.DATABASES)) {
            putJsonArray("databasesOpened") { device.databases.operator.opened.forEach { add(JsonPrimitive(it)) } }
        }
        if (mocked(MockedSystem.FILES)) {
            put("stagedFiles", device.disk.operator.area(FileArea.SHARED).keys.count { it.startsWith("$DOWNLOAD_STAGING_DIR/") })
        }
    }.toString()

    private inner class Handler(private val done: () -> Unit) : Completion {
        var released = false
        var expired = false
        var expiry: (() -> Unit)? = null

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
            if (first) done()
        }

        override fun onExpired(action: () -> Unit) {
            val now = locked {
                expiry = action
                expired && !released
            }
            if (now) action()
        }
    }
}
