package app.snapsync.mock

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.BackendCall
import app.snapsync.levers.BackendFault
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.BackendSwitches
import app.snapsync.levers.EventState
import app.snapsync.levers.MembershipRecord
import app.snapsync.levers.PublishedAsset
import app.snapsync.levers.PublishedManifest
import app.snapsync.levers.SentPush
import app.snapsync.levers.StoredDeviceConfig
import app.snapsync.levers.UnionFetch
import app.snapsync.levers.WorldEvent
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.MemberCounts
import app.snapsync.model.PushEndpoint
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionAsset
import app.snapsync.model.withFinal
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.Routes
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * **The backend's mock** (`docs/testing.md`, "Mocks"): its durable state — the events, memberships, stored bytes,
 * device configs and the pushes it would send, which a relaunch of the app does not touch — the [port] a process
 * talks to, and the [operator] face a test or harness pulls levers and reads outcomes through.
 *
 * The app only ever holds what [port] returns — the device API at the [HttpTransport] port, answered as
 * `api/openapi.yaml` declares ([BackendRouter]); nothing here reaches it otherwise.
 */
class BackendMock internal constructor(internal val state: BackendState) {
    constructor(
        /** The creation time every event it mints is stamped with — whole-second UTC, as the real backend's. */
        createdAt: Instant,
        /**
         * The backend's clock — what an open-until date is judged against, as the real one reads its own: a device's
         * mocks pass the time they play.
         */
        now: () -> Instant,
    ) : this(BackendState(mutableMapOf(), createdAt, now))

    /** A backend on the wall clock, minting events stamped [createdAt]. */
    constructor(
        /** The creation time every event it mints is stamped with — whole-second UTC, as the real backend's. */
        createdAt: Instant = Instant.parse(DEFAULT_CREATED_AT),
    ) : this(createdAt, { Clock.System.now() })

    /**
     * One process's face: the device API over HTTP. The version a request declares is its own header, as the
     * gateway sends it from the process's build facts.
     */
    fun port(): HttpTransport = BackendRouter(state)

    /**
     * Tells [listener] what this backend records as it records it — each request that reaches it, each one an operator's
     * hold parks — on the requesting thread, so it is told before the request goes on. One listener: the control
     * channel's event stream (`docs/testing.md`, "How a test waits").
     */
    fun watch(listener: BackendWatcher) {
        state.watcher = listener
    }

    /**
     * [port], for a process whose build's version is baked but which an operator plays as another one: every request
     * declares what [declared] holds now in place of its own header — nothing, when it holds none.
     */
    fun port(declared: DeclaredVersion): HttpTransport {
        val router = BackendRouter(state)
        return HttpTransport { request ->
            val headers = request.headers.filterKeys { !it.equals(Limits.VERSION_HEADER, ignoreCase = true) } +
                listOfNotNull(declared.value?.let { Limits.VERSION_HEADER to it })
            router.send(HttpRequest(request.method, request.url, headers, request.body))
        }
    }

    /** The levers and reads — what the backend's operator, and a test, can do to it. */
    val operator: BackendOperator = BackendOperator(state)

    /**
     * The byte route, as an OS transfer reaches it: `PUT <base>/events/<event>/files/devices/<device>/<asset>/<role>?filename=…`
     * — or the event-less `<base>/files/devices/<device>/…` an earlier build's job still carries. Answers the HTTP status
     * the backend would, or stores the object and answers `201`. What a device's upload network delivers to.
     */
    val byteRoute: UploadNetwork = UploadNetwork { url, headers, _ -> state.locked { state.receive(url, headers) } }

    /**
     * Every push the backend has handed the push service, in order — what the played operating system delivers from
     * ([BackendOperator.pushes] is the operator's read of the same).
     */
    fun pushesHandedOver(): List<SentPush> = state.locked { state.pushes.toList() }

    companion object {
        /**
         * What an event's `capacity` says on the wire: the real api's placeholder, which limits nothing (decision record
         * `changes/remove-event-device-cap`, D4). An event admits any number of devices.
         */
        const val CAPACITY_PLACEHOLDER: Int = 1_000_000

        /** The most members one wake reaches, chosen at random — the real api's fan-out bound (D1). */
        const val FANOUT_MAX_RECIPIENTS: Int = 40
        const val DEFAULT_CREATED_AT: String = "2026-01-01T00:00:00Z"
    }
}

/**
 * The marketing version a build declares on every call — a cell rather than a constant so an operator can play the
 * member updating the app in place. `null` declares none, which an armed gate refuses.
 */
class DeclaredVersion(value: String?) {
    @Volatile var value: String? = value
}

/**
 * What the backend's operator can do to it and read from it — the levers a real backend has no route for, and the
 * outcomes a test asserts ([BackendLevers], which the control channel serves). Nothing here is on the device API.
 *
 * Its other members are reached through the backend's public surface instead ([PublicBackendLevers] stands in for
 * each), so a test that uses them holds against the real `api/` too; the harness and the mocks' own tests call them
 * here. An `event` or `device` the interface lets a caller omit is resolved by the channel before it gets here.
 */
class BackendOperator internal constructor(private val state: BackendState) : BackendLevers {

    // ---- levers ----------------------------------------------------------------------------------

    override suspend fun offline(on: Boolean) {
        state.offline = on
    }

    override suspend fun failListing(on: Boolean) {
        state.failDeviceListing = on
    }

    override suspend fun failUnion(on: Boolean) {
        state.failUnion = on
    }

    override suspend fun minAppVersion(minimum: String?) {
        state.minAppVersion = minimum
    }

    override suspend fun nextEventId(id: String) {
        state.nextEventId = id
    }

    override suspend fun hold(call: BackendCall) {
        state.locked { state.holds.getOrPut(call, ::OperatorHold) }.hold()
    }

    override suspend fun release(call: BackendCall) {
        state.locked { state.holds.remove(call) }?.release()
    }

    override suspend fun fail(operation: String, fault: BackendFault, times: Int?, after: Int?) {
        require(operation in OPERATIONS) {
            "operation must be one of the device API's operationIds (${OPERATIONS.sorted().joinToString(
                "|",
            )}), was '$operation'"
        }
        val count = times ?: 1
        val skipped = after ?: 0
        require(count > 0) { "times must be positive, was $count" }
        require(skipped >= 0) { "after must not be negative, was $skipped" }
        state.locked { state.faults[operation] = OperationFault(fault, count, skipped) }
    }

    override suspend fun recover(operation: String?) {
        state.locked { if (operation == null) state.faults.clear() else state.faults.remove(operation) }
    }

    override suspend fun sweep(event: String?) {
        val id = resolved(event)
        state.locked { state.events.remove(id) }
    }

    override suspend fun complete(event: String?) {
        val id = resolved(event)
        state.locked { state.complete(id) }
    }

    override suspend fun wipeBytes(device: String?) {
        val id = resolved(device)
        state.locked { state.storedFiles.keys.removeAll { it.second == id } }
    }

    override suspend fun refuseNextCredential() {
        state.refuseNextCredential = true
    }

    override suspend fun refuseAttestation(reason: AttestationRefusal?, detail: String?) {
        state.refuseAttestation = reason?.let { DeviceRefusal.valueOf(it.name) }
        state.refuseAttestationDetail = detail?.takeIf { reason != null }
    }

    // ---- reads -----------------------------------------------------------------------------------

    override suspend fun switches(): BackendSwitches = state.locked {
        BackendSwitches(
            offline = state.offline,
            failListing = state.failDeviceListing,
            failUnion = state.failUnion,
            minAppVersion = state.minAppVersion,
            refusingAttestation = state.refuseAttestation?.let { AttestationRefusal.valueOf(it.name) },
            held = BackendCall.entries.filter { it in state.holds },
        )
    }

    override suspend fun membership(event: String?, device: String?): MembershipRecord {
        val (e, d) = resolved(event) to resolved(device)
        return state.locked {
            val membership = state.memberships[e to d]
            MembershipRecord(
                departed = membership?.departed == true,
                publishes = state.publishes[e to d] ?: 0,
                manifest = membership?.let { (it.manifest ?: DeviceManifest(deviceIdOf(d), emptyList())).published() },
            )
        }
    }

    override suspend fun eventState(event: String?): EventState {
        val id = resolved(event)
        return state.locked {
            EventState(
                unionReads = state.unionReads[id] ?: 0,
                detailReads = state.eventReads[id] ?: 0,
                unionFetches = state.fetches[id].orEmpty().toList(),
                unionPosition = state.position(id),
                finished = state.finished(id),
                completed = state.events[id]?.completed == true,
            )
        }
    }

    override suspend fun deviceConfig(device: String?): StoredDeviceConfig {
        val id = resolved(device)
        return state.locked {
            val config = state.deviceConfigs[id]
            StoredDeviceConfig(config?.token, config?.env, state.deviceConfigWrites[id] ?: 0)
        }
    }

    override suspend fun pushes(): List<SentPush> = state.locked { state.pushes.toList() }

    // ---- what the public surface stands in for ([PublicBackendLevers]) -------------------------

    /** The object keys stored for [deviceId], in every event. */
    fun objectsOf(deviceId: String): Set<String> = state.locked {
        state.storedFiles.filterKeys { it.second == deviceId }.values.flatten().mapTo(mutableSetOf(), ::storedKey)
    }

    /** The event's union — every member's complete assets, departed members included; `null` for an unknown event. */
    fun unionOf(eventId: String): List<UnionAsset>? = state.locked {
        state.union(
            eventId,
        )?.map { (deviceId, asset) -> UnionAsset(deviceIdOf(deviceId), asset.assetId, asset.creationDate, emptyList()) }
    }

    /** Whether the backend holds [eventId]. */
    fun isRegistered(eventId: String): Boolean = state.locked { eventId in state.events }

    /** The name the backend serves for [eventId]. */
    fun eventNameOf(eventId: String): String? = state.locked { state.events[eventId]?.name }

    /** Another member joining [eventId] and publishing [manifest] there — the routes' rules, without the requests. */
    fun seedMember(eventId: String, manifest: DeviceManifest) {
        state.locked {
            state.join(eventId, manifest.deviceId.value)
            state.publish(eventId, manifest.deviceId.value, manifest)
        }
    }

    /**
     * One resource's bytes land for [deviceId] in [eventId] — the byte route without the request; by default in the
     * device's present membership, as the event-less route files them. Nowhere when it has none.
     */
    fun deposit(deviceId: String, assetId: AssetId, role: ResourceRole, filename: String, eventId: String? = null) {
        state.locked {
            val event = eventId ?: state.presentEventOf(deviceId) ?: return@locked
            state.deposit(event, deviceId, DeviceFile(assetId, role, filename))
        }
    }

    private companion object {
        /** The device API's operations — the `operationId`s a fault may name. */
        val OPERATIONS: Set<String> = BackendRouter.OPERATION_IDS
    }
}

/** An omitted event or device, which the control channel names before a call gets here. */
private fun resolved(id: String?): String = requireNotNull(id) {
    "no event or device was named: the control channel names the joined event and this device for an omitted one"
}

/** A held manifest as the operator reads it. */
private fun DeviceManifest.published() = PublishedManifest(
    assets = assets.map { a -> PublishedAsset(a.assetId.value, a.resources.map { it.role.wire }) },
    final = final,
    version = version,
)

/**
 * The backend's durable state, as the real `api/` keeps it in its database and byte store. Every [BackendMock.port]
 * and the [BackendOperator] read and write this one value, each request and each operator read [locked] whole — the
 * transaction a real backend runs a request in.
 */
internal class BackendState(
    /** The byte store, by (event, device): what the byte route stored, each event its own (change `per-event-storage-layout`). */
    val storedFiles: MutableMap<Pair<String, String>, MutableSet<DeviceFile>>,
    val createdAt: Instant,
    /** The backend's clock — what an open-until date is judged against. */
    val now: () -> Instant,
) {
    private val lock = mockLock()

    fun <T> locked(block: () -> T): T = lock.locked(block)

    /**
     * The SHA-256 each landed resource's bytes were declared with, by `<event>/<device>/<asset>/<role>` — what the
     * upload-state read answers "landed" for.
     */
    val landedSha256 = mutableMapOf<String, String>()

    /**
     * What an upload that ended short of its declared range kept, by the same key: its SHA-256 and how many bytes
     * arrived — what the upload-state read answers "stored" for. The mock's OS uploader lands a transfer whole, so only
     * a request to its HTTP face leaves one.
     */
    val partial = mutableMapOf<String, Pair<String, Long>>()

    class Event(
        var name: String,
        val createdAt: Instant,
        val startsAt: Instant,
        val endsAt: Instant,
        /**
         * Until when the event stays open — its end, or the date its host chose for an
         * event created after its end. Write-once, like the real row.
         */
        val openUntil: Instant = endsAt,
        /**
         * Closed before events stopped closing (`PENDING_CLEANUPS.md`): no join, no rename, no change to a member's
         * asset set. Nothing closes an event any more; only a state saved by an earlier mock carries `true`.
         */
        var closed: Boolean = false,
        /** Completed — the sweep's verdict: memberships and assets gone, the record kept. */
        var completed: Boolean = false,
        /** An ENCRYPTED event's key id (16 lowercase hex); `null` for a plain event. Write-once, like the real row. */
        val keyId: String? = null,
    )

    class Membership(
        var departed: Boolean = false,
        var manifest: DeviceManifest? = null,
        var manifestVersion: Long? = null,
        /** The union position up to which the device said it holds every photo of the others; `null` before any. */
        var receivedPosition: Long? = null,
    )

    enum class JoinOutcome { ENROLLED, CLOSED, NO_SUCH_EVENT }

    enum class PublishOutcome { APPLIED, OLDER, NOT_A_MEMBER, NO_SUCH_EVENT, CLOSED, COMPLETED }

    val events = mutableMapOf<String, Event>()
    val memberships = mutableMapOf<Pair<String, String>, Membership>()
    val deviceConfigs = mutableMapOf<String, PushEndpoint>()
    val deviceConfigWrites = mutableMapOf<String, Int>()
    val publishes = mutableMapOf<Pair<String, String>, Int>()

    /** Reads of each event's union and of its details, as the backend served them — what a device cost it. */
    val unionReads = mutableMapOf<String, Int>()
    val eventReads = mutableMapOf<String, Int>()
    val pushes = mutableListOf<SentPush>()

    /**
     * The union log (decision record `changes/incremental-union`, D4): every asset an event's union gained or lost, in
     * one global order — what a delta read is served from — and every read of it.
     */
    class Change(val seq: Long, val eventId: String, val deviceId: String, val assetId: AssetId, val gained: Boolean)
    val changes = mutableListOf<Change>()
    var nextSeq = 1L
    val fetches = mutableMapOf<String, MutableList<UnionFetch>>()
    val challenges = mutableSetOf<String>()
    val minted = mutableSetOf<String>()

    /** The key each device attested with — what a renewal must assert with. */
    val attestedKeys = mutableMapOf<String, String>()

    @Volatile var offline = false

    @Volatile var failDeviceListing = false

    @Volatile var failUnion = false

    @Volatile var refuseNextCredential = false

    @Volatile var refuseAttestation: DeviceRefusal? = null

    @Volatile var refuseAttestationDetail: String? = null
    val holds = mutableMapOf<BackendCall, OperatorHold>()

    /** The fault each operation answers with, and for which of its next requests. */
    val faults = mutableMapOf<String, OperationFault>()

    /** The fault an operator set for [operationId]'s next request, taken — or `null`. */
    fun takeFault(operationId: String): BackendFault? {
        val set = faults[operationId] ?: return null
        val next = when {
            set.skipped > 0 -> set.copy(skipped = set.skipped - 1)
            set.left > 1 -> set.copy(left = set.left - 1)
            else -> null
        }
        if (next == null) faults.remove(operationId) else faults[operationId] = next
        return set.fault.takeIf { set.skipped == 0 }
    }

    /** A read of the event's details reached the backend — counted before an operator's hold answers it. */
    fun countEventRead(eventId: String) {
        eventReads[eventId] = (eventReads[eventId] ?: 0) + 1
    }

    @Volatile var nextEventId: String? = null

    @Volatile var minAppVersion: String? = null

    /** Wait while an operator holds [call] — outside the lock, which the operator's release needs. */
    suspend fun awaitRelease(call: BackendCall) {
        locked { holds[call] }?.let { hold ->
            watcher.told(WorldEvent.BackendHeld(call))
            hold.await()
        }
    }

    /** Who is told what this backend records, as it records it — the control channel's event stream, or no one. */
    @Volatile var watcher: BackendWatcher = BackendWatcher {}

    fun issueChallenge(): String = "in-memory-challenge-${challenges.size + 1}".also { challenges += it }

    /**
     * A well-formed token, distinct per mint — `<deviceId>.<expiry>.<signature>`, as the real backend's — that lives
     * [TOKEN_LIFETIME_SECONDS] from now on this backend's clock; its signature is the challenge it was minted over.
     */
    fun mint(deviceId: String, challenge: String): String =
        "$deviceId.${now().epochSeconds + TOKEN_LIFETIME_SECONDS}.$challenge".also { minted += it }

    /** The minimum a build declaring [declared] falls short of, or null while the gate is off or the build is new enough. */
    fun minimumRefusing(declared: String?): String? {
        val minimum = minAppVersion ?: return null
        if (declared != null && compareAppVersions(declared, minimum) >= 0) return null
        return minimum
    }

    fun join(eventId: String, deviceId: String): JoinOutcome {
        val event = events[eventId] ?: return JoinOutcome.NO_SUCH_EVENT
        // A completed event — or one closed before events stopped closing — admits nobody, a returning device
        // included, as the real enrolment's existence test.
        if (event.closed || event.completed) return JoinOutcome.CLOSED
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
        // An open event admits any number of devices (decision record `changes/remove-event-device-cap`, D3).
        memberships[eventId to deviceId] = Membership()
        return JoinOutcome.ENROLLED
    }

    fun publish(eventId: String, deviceId: String, manifest: DeviceManifest): PublishOutcome {
        val event = events[eventId] ?: return PublishOutcome.NO_SUCH_EVENT
        if (event.completed) return PublishOutcome.COMPLETED
        val membership = memberships[eventId to deviceId] ?: return PublishOutcome.NOT_A_MEMBER
        // An event closed before events stopped closing keeps its asset sets fixed: the set already declared is
        // answered and changes nothing, any other is refused.
        if (event.closed) {
            val held = membership.manifest?.assets.orEmpty().associate { a -> a.assetId to a.resources.map { it.role }.toSet() }
            val incomingSet = manifest.assets.associate { a -> a.assetId to a.resources.map { it.role }.toSet() }
            return if (held == incomingSet) PublishOutcome.APPLIED else PublishOutcome.CLOSED
        }
        val held = membership.manifestVersion
        val incoming = manifest.version
        // An older snapshot landing last is answered, and changes nothing: one at least as new is already there.
        if (held != null && incoming != null && incoming < held) {
            return PublishOutcome.OLDER
        }
        val before = servable(eventId, deviceId)
        membership.manifest = manifest
        membership.manifestVersion = incoming
        publishes[eventId to deviceId] = (publishes[eventId to deviceId] ?: 0) + 1
        val after = servable(eventId, deviceId)
        logChanges(eventId, deviceId, before, after)
        // Nothing closes. A settled declaration is kept as declared, whenever it comes: a device settles once its
        // member's own range has ended, which may be before the event's end (the real route does the same, which the
        // Backend contract pins against the real api/). Only a publish that made something newly servable wakes
        // anyone, as on the real route.
        if (!before.containsAll(after)) notifyMembers(eventId, deviceId, announce = true)
        return PublishOutcome.APPLIED
    }

    /**
     * A leave: the membership is gone, and keeps what it shared in the union. Whether the member left having
     * everything (`done` or `left` on the real backend) is observable through no route, so the mock keeps no record of
     * it.
     */
    fun leave(eventId: String, deviceId: String) {
        memberships[eventId to deviceId]?.takeUnless { it.departed }?.departed = true
    }

    /**
     * What a present member said it received: the highest [position] reported, never lowered — `false` for a device
     * that is not in the event, as the real route answers it.
     */
    fun received(eventId: String, deviceId: String, position: Long): Boolean {
        val membership = memberships[eventId to deviceId]?.takeUnless { it.departed } ?: return false
        membership.receivedPosition = maxOf(membership.receivedPosition ?: -1L, position)
        return true
    }

    /** The event's present members, how many of them settled, and how many are still sharing. */
    fun members(eventId: String): MemberCounts {
        val active = present(eventId)
        return MemberCounts(
            active.size,
            active.values.count { it.manifest?.final == true },
            active.count { (key, m) -> !sharedAll(eventId, key.second, m) },
        )
    }

    /** The sweep's completion: memberships and their assets go, the record stays. */
    fun complete(eventId: String) {
        val event = events[eventId] ?: return
        event.completed = true
        memberships.keys.filter { it.first == eventId }.forEach { memberships.remove(it) }
        // The union log goes with the photos.
        changes.removeAll { it.eventId == eventId }
        fetches.remove(eventId)
    }

    /** The position [eventId]'s union stands at: its last gain's, or 0 before any. */
    fun position(eventId: String): Long = changes.lastOrNull { it.eventId == eventId && it.gained }?.seq ?: 0L

    /**
     * The union from [after] — only the assets gained past it, still through the union's own filter — and the position
     * it covers; `null` for an unknown event. Logs the read under [reader].
     */
    fun unionPage(
        eventId: String,
        after: Long?,
        reader: String?,
        trigger: String?,
    ): Pair<List<Pair<String, DeviceManifestAsset>>, Long>? {
        val all = union(eventId) ?: return null
        val position = position(eventId)
        val page = if (after == null) {
            all
        } else {
            val since = changes.filter { it.eventId == eventId && it.gained && it.seq > after }
                .mapTo(mutableSetOf()) { it.deviceId to it.assetId }
            all.filter { (deviceId, asset) -> (deviceId to asset.assetId) in since }
        }
        if (!events.getValue(eventId).completed) {
            fetches.getOrPut(eventId) { mutableListOf() } += UnionFetch(reader, trigger, after, position, page.size)
        }
        return page to position
    }

    private fun logChanges(eventId: String, deviceId: String, before: Set<AssetId>, after: Set<AssetId>) {
        fun log(asset: AssetId, gained: Boolean) {
            changes += Change(nextSeq++, eventId, deviceId, asset, gained)
            watcher.told(WorldEvent.UnionChanged(eventId, deviceId, asset.value, gained))
        }
        (after - before).sorted().forEach { log(it, gained = true) }
        (before - after).sorted().forEach { log(it, gained = false) }
    }

    /** Every member's complete assets — an asset is served once every resource it declares has landed. */
    fun union(eventId: String): List<Pair<String, DeviceManifestAsset>>? {
        if (eventId !in events) return null
        return memberships.filterKeys { it.first == eventId }.flatMap { (key, membership) ->
            val deviceId = key.second
            val stored = storedFiles[eventId to deviceId].orEmpty().mapTo(mutableSetOf(), ::storedKey)
            membership.manifest?.assets.orEmpty()
                .filter { asset -> asset.resources.isNotEmpty() && asset.resources.all { it.key in stored } }
                .map { deviceId to it }
        }
    }

    /**
     * The event's DECLARED total — what a member's received total counts — as the real union read's
     * `SnapSync-Declared` header answers it: every asset the whole [union] serves, plus every asset a member still in
     * the event declares that is not complete yet. A departed member's undelivered assets never land, so they do not
     * count.
     */
    fun declared(eventId: String): Long {
        val complete = union(eventId)?.size ?: return 0L
        val pending = present(eventId).entries.sumOf { (key, membership) ->
            val stored = storedFiles[eventId to key.second].orEmpty().mapTo(mutableSetOf(), ::storedKey)
            membership.manifest?.assets.orEmpty()
                .count { asset -> asset.resources.isEmpty() || !asset.resources.all { it.key in stored } }
        }
        return (complete + pending).toLong()
    }

    fun deposit(eventId: String, deviceId: String, file: DeviceFile) {
        val was = servable(eventId, deviceId)
        storedFiles.getOrPut(eventId to deviceId) { mutableSetOf() } += file
        // After the write, as the real byte route does: when this byte completed an asset, the event logs the gain and
        // wakes its other members.
        val now = servable(eventId, deviceId)
        logChanges(eventId, deviceId, was, now)
        if (!was.containsAll(now)) notifyMembers(eventId, deviceId, announce = true)
    }

    /** The event [deviceId] is still in — where an event-less byte route files its upload — or `null`. */
    fun presentEventOf(deviceId: String): String? =
        memberships.entries.firstOrNull { (key, m) -> key.second == deviceId && !m.departed && events[key.first]?.completed == false }
            ?.key?.first

    fun receive(url: String, headers: Map<String, String>): Int {
        val version = headers.entries.firstOrNull { it.key.equals(Limits.VERSION_HEADER, ignoreCase = true) }?.value
        if (minimumRefusing(version) != null) return UPGRADE_REQUIRED
        val route = url.substringAfter("/files/devices/", missingDelimiterValue = "")
        // `/events/<event>/files/devices/…` names its event; the event-less form of an earlier build does not.
        val named = url.substringBefore("/files/devices/").substringAfter("/events/", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }?.let(::percentDecoded)
        val segments = route.substringBefore('?').split('/').map(::percentDecoded)
        val filename = route.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("filename=") }?.removePrefix("filename=")?.let(::percentDecoded)
        val role = segments.getOrNull(2)?.let { wire -> ResourceRole.entries.firstOrNull { it.wire == wire } }
        if (segments.size != 3 || role == null || !AssetId.isCanonical(segments[1]) || filename.isNullOrEmpty()) return BAD_REQUEST
        if (offline) return BAD_GATEWAY
        val deviceId = segments[0]
        // The byte route is gated like every other: a token this backend minted, unexpired, for the device it names.
        val token = headers.entries.firstOrNull { it.key.equals(AUTHORIZATION, ignoreCase = true) }?.value
            ?.removePrefix("Bearer ")?.trim()
        if (token == null || !authorizes(token)) return UNAUTHORIZED
        if (token.substringBefore('.') != deviceId) return FORBIDDEN
        val eventId = when (named) {
            // Only a member still in the event writes into it.
            null -> presentEventOf(deviceId) ?: return CONFLICT
            else -> named.takeIf { isPresent(it, deviceId) } ?: return FORBIDDEN
        }
        deposit(eventId, deviceId, DeviceFile(AssetId(segments[1]), role, filename))
        val key = "$eventId/$deviceId/${segments[1]}/${role.wire}"
        val shaHeader = Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256
        val sha256 = headers.entries.firstOrNull { it.key.equals(shaHeader, ignoreCase = true) }?.value
        if (sha256 == null) landedSha256 -= key else landedSha256[key] = sha256
        partial -= key
        return CREATED
    }

    /** Whether [deviceId] is still a member of [eventId] — who may write into it, and ask where its upload stands. */
    fun mayUpload(eventId: String, deviceId: String): Boolean = isPresent(eventId, deviceId)

    private fun isPresent(eventId: String, deviceId: String): Boolean =
        memberships[eventId to deviceId]?.departed == false && events[eventId]?.completed == false

    private fun servable(eventId: String, deviceId: String): Set<AssetId> =
        union(eventId).orEmpty().filter { it.first == deviceId }.mapTo(mutableSetOf()) { it.second.assetId }

    /**
     * Every OTHER active member of [eventId] holding a push registration — at most
     * [BackendMock.FANOUT_MAX_RECIPIENTS] of them, picked at random as the real fan-out does — is sent one silent push,
     * recorded here. A wake that [announce]s a gain names the union position.
     */
    private fun notifyMembers(eventId: String, publisherId: String, announce: Boolean) {
        val seq = if (announce) position(eventId) else null
        val recipients = memberships
            .filter { (key, membership) -> key.first == eventId && key.second != publisherId && !membership.departed }
            .mapNotNull { (key, _) -> deviceConfigs[key.second]?.let { key.second to it.token } }
        // Sampled only past the bound, and kept in membership order, so a small event's pushes read as they always did.
        val woken = if (recipients.size <= BackendMock.FANOUT_MAX_RECIPIENTS) {
            recipients.toSet()
        } else {
            recipients.shuffled().take(BackendMock.FANOUT_MAX_RECIPIENTS).toSet()
        }
        recipients.filter { it in woken }.forEach { (deviceId, token) ->
            pushes += SentPush(eventId, deviceId, token, seq)
            watcher.told(WorldEvent.PushSent(eventId, deviceId, token))
        }
    }

    companion object {
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val CONFLICT = 409
        const val CREATED = 201
        const val UPGRADE_REQUIRED = 426
        const val BAD_GATEWAY = 502

        const val UNAUTHORIZED = 401
        const val AUTHORIZATION = "Authorization"

        /** How long a minted token lives: 30 days, the real backend's lifetime. */
        const val TOKEN_LIFETIME_SECONDS: Long = 30L * 24 * 60 * 60
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
internal fun percentDecoded(raw: String): String {
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

/** The memberships of [eventId] still in it. */
internal fun BackendState.present(eventId: String) =
    memberships.filter { (key, m) -> key.first == eventId && !m.departed }

/** Whether [membership] of [deviceId] has SHARED everything: settled, and every resource it declares landed. */
internal fun BackendState.sharedAll(eventId: String, deviceId: String, membership: BackendState.Membership): Boolean {
    val stored = storedFiles[eventId to deviceId].orEmpty().mapTo(mutableSetOf(), ::storedKey)
    return membership.manifest?.final == true &&
        membership.manifest?.assets.orEmpty().all { asset -> asset.resources.all { it.key in stored } }
}

/**
 * Whether [eventId] is FINISHED now: its open-until date has passed, someone joined it, and every member still in it
 * shared everything and holds every photo of the others up to the union's position.
 */
internal fun BackendState.finished(eventId: String): Boolean {
    val event = events[eventId] ?: return false
    if (event.openUntil >= now() || memberships.keys.none { it.first == eventId }) return false
    val head = position(eventId)
    return present(eventId).all { (key, m) ->
        sharedAll(eventId, key.second, m) && (m.receivedPosition ?: -1L) >= head
    }
}

/**
 * The `closedAt` builds that predate open-until dates read (`PENDING_CLEANUPS.md`): set for an event closed before
 * events stopped closing, or a completed one, and otherwise once the open-until date has passed and everyone still
 * in the event has shared everything — as the real route derives it.
 */
internal fun BackendState.closedForOldBuilds(eventId: String): Boolean {
    val event = events[eventId] ?: return false
    if (event.closed || event.completed) return true
    if (event.openUntil >= now() || memberships.keys.none { it.first == eventId }) return false
    return present(eventId).all { (key, m) -> sharedAll(eventId, key.second, m) }
}

/** A device id the backend holds, as the app reads it — every one is a canonical UUID, as the routes accept no other. */
internal fun deviceIdOf(text: String): DeviceId = requireNotNull(DeviceId.parse(text)) { "not a device id: '$text'" }

/** Told what the backend records, on the requesting thread, before the request goes on. */
fun interface BackendWatcher {
    fun told(event: WorldEvent.Backend)
}

/**
 * Whether [token] authorizes a request now, as the real gate judges one: minted here, and not past its expiry (valid
 * while `now <= expiry`). The device it acts for rides inside it.
 */
internal fun BackendState.authorizes(token: String): Boolean =
    token in minted && (token.split('.').getOrNull(1)?.toLongOrNull() ?: 0) >= now().epochSeconds

/** An operator's [fault] for one operation: answered to its next [left] requests, once [skipped] more have passed. */
internal data class OperationFault(val fault: BackendFault, val left: Int, val skipped: Int)
