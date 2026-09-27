package app.snapsync.rig

import app.snapsync.jvm.JvmMocks
import app.snapsync.ports.Completion
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The operating system's side of the completion handlers the JVM host hands the app (`docs/testing.md`, "One control
 * protocol, served by two hosts"): what the OS itself would know about them — how many it handed over, how many the
 * app released, and how many releases arrived for a handler already released — and the one thing only an OS can do to
 * them, **expire** them (`/os/app/onExpiry`).
 *
 * The iOS rig cannot do this: a real operating system's expiry is its own, so the app host refuses the lever. Here
 * the operating system is played, so its expiry is too — the same signal a `BGTask`'s expiration handler and
 * `UIApplication`'s background-time expiry deliver, through the same `onExpired` registration and the background-time
 * mock.
 */
internal class JvmOs(private val mocks: JvmMocks) {
    private val lock = Any()
    private val held = mutableListOf<Handler>()
    private var handed = 0
    private var released = 0
    private var releasedAgain = 0

    /** Set by `onExpiry?arg=next`: the next handler is handed over with its time already up. */
    private var nextExpired = false

    /** A completion handler for [done], counted, and expirable by [expire]. */
    fun completion(done: () -> Unit): Completion {
        val handler = Handler(done)
        synchronized(lock) {
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
        val expiring = synchronized(lock) { held.filterNot { it.released }.onEach { it.expired = true } }
        expiring.forEach { it.expiry?.invoke() }
        mocks.backgroundTime.operator.expireAll()
    }

    /** The next handler handed over arrives with its time already up. */
    fun expireNext() = synchronized(lock) { nextExpired = true }

    /** What the operating system recorded of the app, read off the mocks' operator faces — `/device/os-record`. */
    fun record(): String = buildJsonObject {
        synchronized(lock) {
            putJsonObject("completions") {
                put("handed", handed)
                put("released", released)
                put("releasedAgain", releasedAgain)
                put("held", held.count { !it.released })
            }
        }
        put("screenShown", mocks.screen.operator.shown.value != null)
        put("selectionObserved", mocks.library.operator.observing)
        put("heartbeatsScheduled", mocks.wakes.operator.heartbeatsScheduled)
        // By the name the app began each under, as `beginBackgroundTask(withName:)` names them to the system.
        putJsonArray("backgroundTimeHolds") { mocks.backgroundTime.operator.holds.value.forEach { add(JsonPrimitive(it.label)) } }
        put("pushRegistrations", mocks.pushService.operator.registrations)
        putJsonObject("downloadSession") {
            put("up", mocks.downloads.operator.realized)
            put("started", mocks.downloads.operator.started.size)
            put("inFlight", mocks.downloads.operator.inFlight().size)
        }
        put("uploadSessionHandbacks", mocks.uploadSession.operator.handbacks)
    }.toString()

    private inner class Handler(private val done: () -> Unit) : Completion {
        var released = false
        var expired = false
        var expiry: (() -> Unit)? = null

        override fun complete() {
            val first = synchronized(lock) {
                if (released) {
                    releasedAgain++
                    false
                } else {
                    released = true
                    this@JvmOs.released++
                    true
                }
            }
            if (first) done()
        }

        override fun onExpired(action: () -> Unit) {
            val now = synchronized(lock) {
                expiry = action
                expired && !released
            }
            if (now) action()
        }
    }
}
