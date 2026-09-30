package app.snapsync.mock

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.PushEndpoint
import app.snapsync.model.AssetId
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.MemberCounts
import app.snapsync.model.withFinal
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionAsset
import app.snapsync.model.isCanonicalAssetId
import app.snapsync.ports.Backend
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

/**
 * **The backend's mock** (`docs/testing.md`, "Mocks"): its durable state — the events, memberships, stored bytes,
 * device configs and the pushes it would send, which a relaunch of the app does not touch — the [port] a process
 * talks to, and the [operator] face a test or harness pulls levers and reads outcomes through.
 *
 * The app only ever holds what [port] returns, typed as the [Backend] port; nothing here reaches it otherwise.
 */
class BackendMock(
    /** Devices an event admits, active or departed, before a join answers `409`. */
    capacity: Int = DEFAULT_CAPACITY,
    /**
     * The creation time every event it mints is stamped with — carrying milliseconds, as the real backend's
     * `toISOString()` does, so the app's normalization of it is exercised rather than assumed.
     */
    createdAt: Instant = Instant.parse(DEFAULT_CREATED_AT),
) {
    internal val state = BackendState(mutableMapOf(), capacity, createdAt)

    /** One process's face: [declared] is the version its build declares, read per call. */
    fun port(declared: DeclaredVersion = DeclaredVersion(null)): Backend = InMemoryBackend(state, declared)

    /** The levers and reads — what the backend's operator, and a test, can do to it. */
    val operator: BackendOperator = BackendOperator(state)

    companion object {
        const val DEFAULT_CAPACITY: Int = 10
        const val DEFAULT_CREATED_AT: String = "2026-01-01T00:00:00.000Z"
    }
}

/**
 * The marketing version a build declares on every call — a cell rather than a constant so an operator can play the
 * member updating the app in place. `null` declares none, which an armed gate refuses.
 */
class DeclaredVersion(var value: String?)

/** The backend calls an operator can hold unanswered — each one a screen the app shows only while it waits. */
enum class BackendCall(val key: String) {
    /** The event's details, which the join gate loads. */
    EVENT("event"),

    /** Creating an event. */
    CREATE("create"),

    /** A device's enrolment in an event. */
    JOIN("join"),

    /** A device leaving an event. */
    LEAVE("leave"),
    ;

    companion object {
        fun ofKey(key: String?): BackendCall? = entries.firstOrNull { it.key == key }
    }
}

/** A push the backend would have sent: the event it announces, and the member and token it was addressed to. */
data class SentPush(val eventId: String, val deviceId: String, val token: String)

/**
 * What the backend's operator can do to it and read from it — the levers a real backend has no route for, and the
 * outcomes a test asserts. Nothing here is on the [Backend] port.
 */
class BackendOperator internal constructor(private val state: BackendState) {

    // ---- reads -----------------------------------------------------------------------------------

    /** The object keys stored for [deviceId]. */
    fun objectsOf(deviceId: String): Set<String> = state.storedFiles[deviceId].orEmpty().mapTo(mutableSetOf(), ::storedKey)

    /** The event's union — every member's complete assets, departed members included; `null` for an unknown event. */
    fun unionOf(eventId: String): List<UnionAsset>? = state.union(eventId)?.map { (deviceId, asset) ->
        UnionAsset(deviceId, asset.assetId, asset.creationDate, emptyList())
    }

    /** Whether the backend holds [eventId]. */
    fun isRegistered(eventId: String): Boolean = eventId in state.events

    /** The name the backend serves for [eventId]. */
    fun eventNameOf(eventId: String): String? = state.events[eventId]?.name

    /**
     * The manifest the backend holds for [deviceId] in [eventId] — an empty one for a member that has published nothing,
     * as a joined device's membership declares no asset — or null when it holds no membership.
     */
    fun manifestOf(eventId: String, deviceId: String): DeviceManifest? =
        state.memberships[eventId to deviceId]?.let { it.manifest ?: DeviceManifest(deviceId, emptyList()) }

    /** How many publishes the backend applied for this membership. */
    fun publishesOf(eventId: String, deviceId: String): Int = state.publishes[eventId to deviceId] ?: 0

    /** How many of the devices' reads of each event's union (`GET /events/:id/files`) reached the backend, by event. */
    val unionReads: Map<String, Int> get() = state.unionReads.toMap()

    /** How many of the devices' reads of each event's details (`GET /events/:id`) reached the backend, by event. */
    val eventReads: Map<String, Int> get() = state.eventReads.toMap()

    /** How many publishes it refused as older than the one it held. */
    fun refusedPublishesOf(eventId: String, deviceId: String): Int = state.refused[eventId to deviceId] ?: 0

    /** The manifest version it holds for this membership. */
    fun manifestVersionOf(eventId: String, deviceId: String): Long? = state.memberships[eventId to deviceId]?.manifestVersion

    /** Whether [deviceId] has left [eventId]. */
    fun isDeparted(eventId: String, deviceId: String): Boolean = state.memberships[eventId to deviceId]?.departed == true

    /** The push registration [deviceId] stored, or null. */
    fun deviceConfigOf(deviceId: String): PushEndpoint? = state.deviceConfigs[deviceId]

    /** How many push registrations it stored for [deviceId] — the config is last-write-wins, this count is not. */
    fun deviceConfigWritesOf(deviceId: String): Int = state.deviceConfigWrites[deviceId] ?: 0

    /** Every push it would have sent, in order. It delivers none: the operating system's side is played elsewhere. */
    fun pushesSent(): List<SentPush> = state.pushes.toList()

    // ---- levers ----------------------------------------------------------------------------------

    /** Listing, union, event details, rename, join, publish, leave and bytes answer `502`. */
    var offline: Boolean
        get() = state.offline
        set(value) { state.offline = value }

    /**
     * Play the nightly sweep's COMPLETION of [eventId] (capability `event-lifetime`): its memberships and assets go,
     * its record stays and answers "completed".
     */
    fun complete(eventId: String) = state.complete(eventId)

    /** Whether [eventId] has closed. */
    fun isClosed(eventId: String): Boolean = state.events[eventId]?.closed == true

    /** Whether [eventId] was completed. */
    fun isCompleted(eventId: String): Boolean = state.events[eventId]?.completed == true

    /** Only the per-device listing answers `502`. */
    var failDeviceListing: Boolean
        get() = state.failDeviceListing
        set(value) { state.failDeviceListing = value }

    /** The minimum app version every route demands, or null for a gate that is off. */
    var minAppVersion: String?
        get() = state.minAppVersion
        set(value) { state.minAppVersion = value }

    /** Devices an event admits before its join answers `409`. */
    var capacity: Int
        get() = state.capacity
        set(value) { state.capacity = value }

    /** The next token-bearing call to a gated route is answered `401`, once. */
    fun refuseNextCredential() {
        state.refuseNextCredential = true
    }

    /**
     * The id the next created event is minted with, once — so a screen that renders it (the invite QR) renders the same
     * every run. `null` mints a random one, as the real backend does.
     */
    var nextEventId: String?
        get() = state.nextEventId
        set(value) { state.nextEventId = value }

    /** Every [call] waits until [release]: the backend that has not answered yet. */
    fun hold(call: BackendCall) {
        if (call !in state.holds) state.holds[call] = CompletableDeferred()
    }

    /** Whether [call] is held. */
    fun isHeld(call: BackendCall): Boolean = call in state.holds

    /** A held [call], and every later one, is answered. */
    fun release(call: BackendCall) {
        state.holds.remove(call)?.complete(Unit)
    }

    /** The nightly sweep deleting [eventId]: every later read of it is `404`. */
    fun sweepEvent(eventId: String) {
        state.events.remove(eventId)
    }

    /** A storage reset wiping every byte object of [deviceId]. */
    fun wipeBytes(deviceId: String) {
        state.storedFiles.remove(deviceId)
    }

    /** The backend collecting one of [deviceId]'s objects, by key. */
    fun collectBytes(deviceId: String, key: String) {
        state.storedFiles[deviceId]?.removeAll { storedKey(it) == key }
    }

    /**
     * An event registered before start dates existed — no start, which the backend synthesizes from its creation
     * time on read. Returns the id, minted as the backend mints one.
     */
    fun registerLegacyEvent(name: String): String = state.registerLegacy(name)

    // ---- the operating system's transfer of bytes ------------------------------------------------

    /**
     * The byte route, as an OS transfer reaches it: `PUT <base>/files/devices/<device>/<asset>/<role>?filename=…` with
     * [headers]. Answers the HTTP status the backend would, or stores the object and answers `201`.
     */
    fun receive(url: String, headers: Map<String, String>): Int = state.receive(url, headers)

    /** One resource's bytes land for [deviceId] — the byte route without the request. */
    fun deposit(deviceId: String, assetId: AssetId, role: ResourceRole, filename: String) {
        state.deposit(deviceId, DeviceFile(assetId, role, filename))
    }
}

/**
 * The backend's durable state, as the real `api/` keeps it in its database and byte store. Every [BackendMock.port]
 * and the [BackendOperator] read and write this one value.
 */
internal class BackendState(
    /** The byte store, by device: what the byte route stored. */
    val storedFiles: MutableMap<String, MutableSet<DeviceFile>>,
    var capacity: Int,
    val createdAt: Instant,
) {
    class Event(
        var name: String,
        val createdAt: Instant,
        val startsAt: Instant?,
        val endsAt: Instant?,
        /** Closed (capability `event-lifetime`): no join, no rename, no change to a member's asset set. */
        var closed: Boolean = false,
        /** Completed — the sweep's verdict: memberships and assets gone, the record kept. */
        var completed: Boolean = false,
    )

    class Membership(var departed: Boolean = false, var manifest: DeviceManifest? = null, var manifestVersion: Long? = null)

    enum class JoinOutcome { ENROLLED, FULL, CLOSED, NO_SUCH_EVENT }

    enum class PublishOutcome { APPLIED, OLDER, NOT_A_MEMBER, NO_SUCH_EVENT, CLOSED, COMPLETED }

    val events = mutableMapOf<String, Event>()
    val memberships = mutableMapOf<Pair<String, String>, Membership>()
    val deviceConfigs = mutableMapOf<String, PushEndpoint>()
    val deviceConfigWrites = mutableMapOf<String, Int>()
    val publishes = mutableMapOf<Pair<String, String>, Int>()

    /** Reads of each event's union and of its details, as the backend served them — what a device cost it. */
    val unionReads = mutableMapOf<String, Int>()
    val eventReads = mutableMapOf<String, Int>()
    val refused = mutableMapOf<Pair<String, String>, Int>()
    val pushes = mutableListOf<SentPush>()
    val challenges = mutableSetOf<String>()
    val minted = mutableSetOf<String>()

    var offline = false
    var failDeviceListing = false
    var refuseNextCredential = false
    val holds = mutableMapOf<BackendCall, CompletableDeferred<Unit>>()
    var nextEventId: String? = null
    var minAppVersion: String? = null
    internal var legacyCounter = 0L

    /** Wait while an operator holds [call]. */
    suspend fun awaitRelease(call: BackendCall) {
        holds[call]?.await()
    }

    fun issueChallenge(): String = "in-memory-challenge-${challenges.size + 1}".also { challenges += it }

    /** A well-formed token, distinct per mint: its signature is the challenge it was minted over. */
    fun mint(deviceId: String, challenge: String): String =
        "$deviceId.$TOKEN_EXPIRES_AT_EPOCH_SECONDS.$challenge".also { minted += it }

    /** The `426` body for a build declaring [declared], or null while the gate is off or the build is new enough. */
    fun refusalFor(declared: String?): String? {
        val minimum = minAppVersion ?: return null
        if (declared != null && compareAppVersions(declared, minimum) >= 0) return null
        return """{"error":"app too old","minAppVersion":"$minimum"}"""
    }

    fun join(eventId: String, deviceId: String): JoinOutcome {
        val event = events[eventId] ?: return JoinOutcome.NO_SUCH_EVENT
        // A closed event admits nobody, a returning device included — as the real enrolment's existence test.
        if (event.closed) return JoinOutcome.CLOSED
        val existing = memberships[eventId to deviceId]
        if (existing != null) {
            // A rejoin clears the stored version, as the real enrolment does: a device whose counter restarted must
            // not have every publish refused as older.
            existing.departed = false
            existing.manifestVersion = null
            // A rejoined device has settled nothing yet for its new membership.
            existing.manifest = existing.manifest?.withFinal(false)
            return JoinOutcome.ENROLLED
        }
        // Every membership ever enrolled counts, active or departed — leaving frees no slot.
        if (memberships.keys.count { it.first == eventId } >= capacity) return JoinOutcome.FULL
        memberships[eventId to deviceId] = Membership()
        return JoinOutcome.ENROLLED
    }

    fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): PublishOutcome {
        val event = events[eventId] ?: return PublishOutcome.NO_SUCH_EVENT
        if (event.completed) return PublishOutcome.COMPLETED
        val membership = memberships[eventId to deviceId] ?: return PublishOutcome.NOT_A_MEMBER
        // A closed event's asset sets are fixed: the set already declared is answered and changes nothing, any other
        // is refused (capability `photo-sharing`).
        if (event.closed) {
            val held = membership.manifest?.assets.orEmpty().associate { a -> a.assetId to a.resources.map { it.role }.toSet() }
            val incomingSet = manifest.assets.associate { a -> a.assetId to a.resources.map { it.role }.toSet() }
            return if (held == incomingSet) PublishOutcome.APPLIED else PublishOutcome.CLOSED
        }
        val held = membership.manifestVersion
        val incoming = manifest.version
        // An older snapshot landing last is answered, and changes nothing: one at least as new is already there.
        if (held != null && incoming != null && incoming < held) {
            refused[eventId to deviceId] = (refused[eventId to deviceId] ?: 0) + 1
            return PublishOutcome.OLDER
        }
        val before = servable(eventId, deviceId)
        membership.manifest = manifest
        membership.manifestVersion = incoming
        publishes[eventId to deviceId] = (publishes[eventId to deviceId] ?: 0) + 1
        // The publish that leaves every active member settled closes the event, and wakes its members once. The mock
        // does not judge the range's end: a device declares itself settled only after it (the real route also
        // ignores an earlier declaration, which the Backend contract pins against the real api/).
        val closes = closesNow(eventId)
        if (closes) event.closed = true
        // Only a publish that made something newly servable wakes anyone, as on the real route — or one that closed it.
        if (closes || !before.containsAll(servable(eventId, deviceId))) notifyMembers(eventId, deviceId)
        return PublishOutcome.APPLIED
    }

    private fun closesNow(eventId: String): Boolean {
        val active = memberships.filter { (key, m) -> key.first == eventId && !m.departed }.values
        return active.isNotEmpty() && active.all { it.manifest?.final == true }
    }

    /** The event's active members and how many of them have settled what they share. */
    fun members(eventId: String): MemberCounts {
        val active = memberships.filter { (key, m) -> key.first == eventId && !m.departed }.values
        return MemberCounts(active.size, active.count { it.manifest?.final == true })
    }

    /** The sweep's completion: memberships and their assets go, the record stays (capability `event-lifetime`). */
    fun complete(eventId: String) {
        val event = events[eventId] ?: return
        event.closed = true
        event.completed = true
        memberships.keys.filter { it.first == eventId }.forEach { memberships.remove(it) }
    }

    /** Every member's complete assets — an asset is served once every resource it declares has landed. */
    fun union(eventId: String): List<Pair<String, DeviceManifestAsset>>? {
        if (eventId !in events) return null
        return memberships.filterKeys { it.first == eventId }.flatMap { (key, membership) ->
            val deviceId = key.second
            val stored = storedFiles[deviceId].orEmpty().mapTo(mutableSetOf(), ::storedKey)
            membership.manifest?.assets.orEmpty()
                .filter { asset -> asset.resources.isNotEmpty() && asset.resources.all { it.key in stored } }
                .map { deviceId to it }
        }
    }

    fun deposit(deviceId: String, file: DeviceFile) {
        val before = memberships.keys.filter { it.second == deviceId }.associate { it.first to servable(it.first, deviceId) }
        storedFiles.getOrPut(deviceId) { mutableSetOf() } += file
        // After the write, as the real byte route does: an event this byte completed an asset in wakes its other members.
        before.forEach { (eventId, was) -> if (!was.containsAll(servable(eventId, deviceId))) notifyMembers(eventId, deviceId) }
    }

    fun receive(url: String, headers: Map<String, String>): Int {
        val version = headers.entries.firstOrNull { it.key.equals(APP_VERSION_HEADER, ignoreCase = true) }?.value
        if (refusalFor(version) != null) return UPGRADE_REQUIRED
        val route = url.substringAfter("/files/devices/", missingDelimiterValue = "")
        val segments = route.substringBefore('?').split('/').map(::percentDecoded)
        val filename = route.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("filename=") }?.removePrefix("filename=")?.let(::percentDecoded)
        val role = segments.getOrNull(2)?.let { wire -> ResourceRole.entries.firstOrNull { it.wire == wire } }
        return when {
            segments.size != 3 || role == null || !isCanonicalAssetId(segments[1]) || filename.isNullOrEmpty() -> BAD_REQUEST
            offline -> BAD_GATEWAY
            else -> {
                deposit(segments[0], DeviceFile(AssetId(segments[1]), role, filename))
                CREATED
            }
        }
    }

    fun registerLegacy(name: String): String {
        legacyCounter += 1
        val eventId = "00000000-0000-4000-9000-" + legacyCounter.toString().padStart(LEGACY_ID_DIGITS, '0')
        events[eventId] = Event(name, createdAt, startsAt = null, endsAt = null)
        return eventId
    }

    private fun servable(eventId: String, deviceId: String): Set<AssetId> =
        union(eventId).orEmpty().filter { it.first == deviceId }.mapTo(mutableSetOf()) { it.second.assetId }

    /** Every OTHER active member of [eventId] holding a push registration is sent one silent push — recorded here. */
    private fun notifyMembers(eventId: String, publisherId: String) {
        memberships.filter { (key, membership) -> key.first == eventId && key.second != publisherId && !membership.departed }
            .forEach { (key, _) -> deviceConfigs[key.second]?.let { pushes += SentPush(eventId, key.second, it.token) } }
    }

    companion object {
        const val BAD_REQUEST = 400
        const val CREATED = 201
        const val UPGRADE_REQUIRED = 426
        const val BAD_GATEWAY = 502
        const val LEGACY_ID_DIGITS = 12

        /** Epoch seconds a minted token expires at: 90 days, which outlives any test's pinned clock. */
        const val TOKEN_EXPIRES_AT_EPOCH_SECONDS: Long = 90L * 24 * 60 * 60

        /** The download handle a union resource carries — `https`, so the real download guard fetches it. */
        fun syntheticUrl(deviceId: String, key: String): String = "https://in-memory.store/$deviceId/$key"
    }
}

/**
 * Compare two `X.Y` marketing versions numerically, part by part; an unparseable version sorts oldest — as the real
 * gate does, so `0.10` is newer than `0.9`.
 */
internal fun compareAppVersions(a: String, b: String): Int {
    fun parts(v: String): List<Int>? =
        v.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }
            ?.split('.')?.map { it.toIntOrNull() ?: return null }
    val pa = parts(a)
    val pb = parts(b)
    if (pa == null) return if (pb == null) 0 else -1
    if (pb == null) return 1
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

/** `%XX` escapes decoded byte-wise as UTF-8 — the byte route's path and query arrive as the app's request builder encoded them. */
private fun percentDecoded(raw: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < raw.length) {
        val escaped = raw.takeIf { raw[i] == '%' && i + 2 <= raw.lastIndex }
            ?.substring(i + 1, i + 3)?.toIntOrNull(HEX)
        if (escaped != null) {
            bytes += escaped.toByte()
            i += 3
        } else {
            bytes += raw[i].toString().encodeToByteArray().toList()
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = 16
