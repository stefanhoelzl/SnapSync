package app.snapsync.integration

import app.snapsync.control.Reply
import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.model.AssetId
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import app.snapsync.rig.AssetView
import app.snapsync.rig.GalleryView
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One integration test's host: a fresh control-channel JVM host, in-process, over the mini-edge — closed when the
 * test ends. The test drives it through [Rig] and nothing else (`docs/testing.md`, "The
 * seam-to-UI-state integration surface"), so a test body names no world, port or composition type.
 */
fun rigTest(body: suspend Rig.() -> Unit): Unit = runBlocking {
    val host = JvmRigHost.start("mini")
    try {
        RigClient("http://127.0.0.1:${host.port}").use { Rig(it).body() }
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
 * recorded (the backend, the photo library, the operating system's jobs, the staging directory, the reporter, the
 * pushes sent). Never [RigState.ledger]: the ledger is the app's own bookkeeping.
 */
class Rig(val client: RigClient) {
    suspend fun state(): RigState = client.state()

    suspend fun awaitState(timeout: Duration = 10.seconds, until: (RigState) -> Boolean): RigState =
        client.awaitState(timeout, until)

    /** A user command, which must be accepted. */
    suspend fun user(name: String, vararg params: Pair<String, String>): String = client.user(name, mapOf(*params)).done()

    /** A user command, answered however the host answers it. */
    suspend fun userReply(name: String, vararg params: Pair<String, String>): Reply = client.user(name, mapOf(*params))

    /** An operating-system entry point, which must be answered. */
    suspend fun os(root: String, member: String, arg: String? = null): String = client.os(root, member, arg).done()

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
        const val EVENT_NAME = "Anna's Birthday"

        /** The capture date every added photo carries by default — inside the default event window. */
        const val PHOTO_DATE = "2026-06-01T10:00:00Z"

        /** The default event window: 30 days, containing [PHOTO_DATE]. Local date-times, as the create form takes. */
        const val WINDOW_START = "2026-05-15T00:00:00"
        const val WINDOW_END = "2026-06-14T00:00:00"

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
