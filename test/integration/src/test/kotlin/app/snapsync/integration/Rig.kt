package app.snapsync.integration

import app.snapsync.control.Reply
import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.Layer
import app.snapsync.presentation.SyncHealth
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
 * test ends. The test drives it through [Rig] and nothing else (capability `testing-architecture`, "The
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

    // ---- the raw protocol -----------------------------------------------------------------------

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

    // ---- events ---------------------------------------------------------------------------------

    /**
     * Create an event over the join gate, as a person does, and wait for the gate to load its details. Answers the
     * minted event id; the gate is left open for [join], [cancelJoin] or a test's own confirm.
     *
     * The default window contains [PHOTO_DATE], the capture date every added and seeded photo carries.
     */
    suspend fun create(
        name: String = EVENT_NAME,
        startsAt: String = WINDOW_START,
        endsAt: String = WINDOW_END,
    ): String {
        user("create", "name" to name, "startsAt" to startsAt, "endsAt" to endsAt)
        val gate = awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
        return (gate.ui.layer as Layer.JoiningEvent).eventId
    }

    /** Confirm the open join gate with [choices] (`direction`, `saveToAlbum`, `cutoff`, `until`), and wait to be joined. */
    suspend fun join(vararg choices: Pair<String, String>): String {
        user("confirmJoin", *choices)
        return awaitState { it.ready.configResolved }.ready.eventId!!
    }

    /** Create an event and join it. Answers its id. */
    suspend fun createAndJoin(
        vararg choices: Pair<String, String>,
        name: String = EVENT_NAME,
        startsAt: String = WINDOW_START,
        endsAt: String = WINDOW_END,
    ): String {
        create(name, startsAt, endsAt)
        return join(*choices)
    }

    /** An event that exists on the backend and that this device has not joined: created, then the gate abandoned. */
    suspend fun registerEvent(name: String = EVENT_NAME, startsAt: String = WINDOW_START, endsAt: String = WINDOW_END): String {
        val event = create(name, startsAt, endsAt)
        user("cancelJoin")
        awaitState { it.ui.layer is Layer.CreateEvent }
        return event
    }

    /** Open an event link, as the operating system delivers a scanned one. */
    suspend fun openLink(url: String) {
        os("app", "onSceneContinueActivity", url)
    }

    /** The link a member scans for [eventId]. */
    fun inviteLink(
        eventId: String,
        autoJoin: Boolean = false,
        minPhotoDate: String? = null,
        direction: String? = null,
        saveToAlbum: Boolean? = null,
    ): String = encodeEventUrl(EventLinkPayload(eventId, autoJoin, minPhotoDate, direction = direction, saveToAlbum = saveToAlbum))

    suspend fun leave() {
        user("leave")
        awaitState { !it.ready.configResolved }
    }

    // ---- the photo library ----------------------------------------------------------------------

    /** Add one own photo — a camera photo unless [kind] says otherwise (`gallery/add`'s kinds). */
    suspend fun addPhoto(id: String, date: String = PHOTO_DATE, kind: String = "photo") {
        device("gallery/add", "id" to id, "date" to date, "kind" to kind)
    }

    /** The own photo's asset in the library, by id. */
    suspend fun asset(id: String, resources: Boolean = false): AssetView? =
        gallery(resources).policy?.assets?.firstOrNull { it.assetId == id }

    suspend fun permission(status: String) {
        device("permission", "status" to status)
    }

    // ---- uploads --------------------------------------------------------------------------------

    /** One upload cycle, as the OS-driven extension runs it. Answers the cycle's result (`completed`, `processing`, …). */
    suspend fun cycle(): String =
        Json.parseToJsonElement(os("photokit-ext", "processRawValue")).jsonObject.getValue("result").jsonPrimitive.content

    /** The operating system's upload jobs: the keys still live, and how many were ever created. */
    suspend fun jobs(): Jobs = deviceJson("jobs").let { json ->
        Jobs(
            live = json.getValue("live").jsonArray.map { it.jsonPrimitive.content },
            created = json.getValue("created").jsonPrimitive.content.toInt(),
        )
    }

    /** The OS finishes every live job (or one), as a transfer landing. */
    suspend fun completeJobs(key: String? = null) {
        if (key == null) device("jobs/complete") else device("jobs/complete", "key" to key)
    }

    /** Upload everything the cycle picks up: a cycle, every job landing, and a cycle to settle and publish. */
    suspend fun uploadAll() {
        cycle()
        completeJobs()
        cycle()
    }

    // ---- the backend ----------------------------------------------------------------------------

    /** The object keys the backend lists for [device] (this device by default). */
    suspend fun objects(device: String? = null): Set<String> =
        deviceJson("backend/objects", *listOfNotNull(device?.let { "device" to it }).toTypedArray())
            .getValue("objects").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }

    /** The asset ids the event's union serves, with the roles each is served with. */
    suspend fun union(event: String? = null): Map<String, Set<String>> =
        deviceJson("backend/union", *listOfNotNull(event?.let { "event" to it }).toTypedArray())
            .getValue("assets").jsonArray.associate { a ->
                val o = a.jsonObject
                o.getValue("asset").jsonPrimitive.content to
                    o.getValue("roles").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
            }

    /** The asset ids — and each one's declared roles — the backend holds in [device]'s manifest for [event]; null when none. */
    suspend fun manifest(event: String? = null, device: String? = null): Map<String, Set<String>>? {
        val params = listOfNotNull(event?.let { "event" to it }, device?.let { "device" to it }).toTypedArray()
        val manifest = deviceJson("backend/manifest", *params)["manifest"]
        if (manifest == null || manifest is JsonNull) return null
        return manifest.jsonObject.getValue("assets").jsonArray.associate { a ->
            val o = a.jsonObject
            o.getValue("assetId").jsonPrimitive.content to
                o.getValue("resources").jsonArray.mapTo(mutableSetOf()) { it.jsonObject.getValue("role").jsonPrimitive.content }
        }
    }

    // ---- other members and downloads ------------------------------------------------------------

    /** A fellow member of [event] (the joined one by default) whose [assets] are complete on the backend. */
    suspend fun foreignDevice(device: String, vararg assets: String, event: String? = null, filename: String? = null): String {
        val params = listOfNotNull(
            "device" to device,
            "assets" to assets.joinToString(","),
            event?.let { "event" to it },
            filename?.let { "filename" to it },
        ).toTypedArray()
        return deviceJson("foreign-device", *params).getValue("eventId").jsonPrimitive.content
    }

    /** The download controller reads the union and starts the transfers — the foreground's reconcile. */
    suspend fun reconcile() {
        device("downloads/reconcile")
    }

    /** The OS delivers every in-flight download; the imports they start are awaited unless [wait] is false. */
    suspend fun stage(wait: Boolean = true) {
        device("downloads/stage", "wait" to wait.toString())
    }

    /** Download and import every foreign photo the union serves. */
    suspend fun downloadAll() {
        reconcile()
        stage()
    }

    /** How many photos the library holds, of every origin. */
    suspend fun libraryTotal(): Long = gallery().census.total

    /** The foreground's status read, as the operator plays it. */
    suspend fun refresh() {
        device("status/refresh")
    }

    // ---- UiState ----------------------------------------------------------------------------------

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
    app.snapsync.model.uploadKey(assetId, app.snapsync.model.ResourceRole.PRIMARY, filename)
