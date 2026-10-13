package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The backend operator's face** — the levers a real backend has no route for, and the outcomes a test reads off it.
 * The backend mock's operator implements it; a host whose backend is the real `api/` refuses every method.
 *
 * An omitted `event` is the joined event and an omitted `device` this device ([JoinedEvent], [ThisDevice]); every
 * other argument is required unless it is nullable, and then an omitted one is `null`, which the method says the
 * meaning of — the curl verb has only the interface's types, never its default values.
 */
@Rpc
interface BackendLevers {
    // ---- levers ----------------------------------------------------------------------------------

    /** Listing, union, event details, rename, join, publish, leave and bytes answer `502` — or, [on] `false`, answer. */
    suspend fun offline(on: Boolean)

    /** Only the per-device listing answers `502`. */
    suspend fun failListing(on: Boolean)

    /** Only the event's union answers `502`: what a rejoin meets when the event cannot be read; the join goes through. */
    suspend fun failUnion(on: Boolean)

    /**
     * Whether a request carrying no `Authorization` header is served, as the local dev server's fallback bearer serves
     * it (`api/src/dev/serve.ts`) — on by default, which the rig and the seeding stand on. Off, it is `401`, as deployed.
     */
    suspend fun fallbackBearer(on: Boolean)

    /** The minimum app version every route demands; omitted, the gate is off. */
    suspend fun minAppVersion(minimum: String? = null)

    /**
     * The id the next created event is minted with, once — so a screen that renders it (the invite QR) renders the same
     * every run.
     */
    suspend fun nextEventId(id: String)

    /** Every [call] waits until [release]d: the backend that has not answered yet — the only time the app shows the
     *  screen that waits on it. */
    suspend fun hold(call: BackendCall)

    /** A held [call], and every later one, is answered. */
    suspend fun release(call: BackendCall)

    /**
     * The next [times] requests (omitted: one) to [operation] — an `operationId` of `api/openapi.yaml` — are answered
     * with [fault] before the route runs: nothing they ask for happens. An operation the device API does not declare is
     * refused.
     */
    suspend fun fail(operation: String, fault: BackendFault, times: Int? = null)

    /** The nightly sweep deleting [event]: every later read of it is `404`. */
    suspend fun sweep(@JoinedEvent event: String? = null)

    /** The sweep's early completion of [event]: its memberships and photos go, its record stays and answers "completed". */
    suspend fun complete(@JoinedEvent event: String? = null)

    /** A storage reset wiping every byte object of [device], in every event, while every record that names it stays. */
    suspend fun wipeBytes(@ThisDevice device: String? = null)

    /** The next token-bearing call to a route that checks its token is answered `401`, once. */
    suspend fun refuseNextCredential()

    /**
     * The backend refuses this phone as not genuine, for [reason], until called without one — a refused phone is told
     * why; [detail] names the diagnostic code beside the reason (`certificate`), as v2 does for a failed certificate.
     */
    suspend fun refuseAttestation(reason: AttestationRefusal? = null, detail: String? = null)

    // ---- reads -----------------------------------------------------------------------------------

    /** Every switch an operator set: what the levers above put the backend in, as it stands now. */
    suspend fun switches(): BackendSwitches

    /** What the backend holds of [device]'s membership of [event]: whether it left, its publishes, its manifest. */
    suspend fun membership(@JoinedEvent event: String? = null, @ThisDevice device: String? = null): MembershipRecord

    /** What the backend logged of [event]: the reads that reached it, its union's position, and where the sweep stands. */
    suspend fun eventState(@JoinedEvent event: String? = null): EventState

    /** What [device] registered for pushes, as the backend stored it, and how many registrations it stored. */
    suspend fun deviceConfig(@ThisDevice device: String? = null): StoredDeviceConfig

    /** Every push the backend would have sent, in order. It delivers none: the operating system's side is played. */
    suspend fun pushes(): List<SentPush>

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<BackendLevers>(
        BackendLevers::class,
        "backend",
        setOf(LeverNeed.BACKEND_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** The backend calls an operator can hold unanswered — each one a screen the app shows only while it waits. */
@Serializable
enum class BackendCall(val key: String) {
    /** The event's details, which the join gate loads. */
    @SerialName("event")
    EVENT("event"),

    /** Creating an event. */
    @SerialName("create")
    CREATE("create"),

    /** A device's enrolment in an event. */
    @SerialName("join")
    JOIN("join"),

    /** A device leaving an event. */
    @SerialName("leave")
    LEAVE("leave"),
    ;

    companion object {
        fun ofKey(key: String?): BackendCall? = entries.firstOrNull { it.key == key }
    }
}

/** A fault an operator injects into the backend's answers ([BackendLevers.fail]). */
@Serializable
enum class BackendFault {
    /** `502` — a store or upstream failure. */
    SERVER_ERROR,

    /** `503 maintenance` with a `Retry-After` — a deploy's migration window. */
    MAINTENANCE,

    /** No answer at all: the request never reached the backend. */
    UNREACHABLE,

    /** A `200` whose body is no JSON — an answer outside the contract. */
    MALFORMED,

    /** `410 closed` — an event that ended for good, as a backend that still closed events answered every write. */
    ENDED_FOR_GOOD,

    /** `409 closed` — an event closed before events stopped closing, answering a member's changed asset set. */
    CLOSED,
}

/** Why the backend refuses a phone as not genuine — the reasons the attestation routes name. */
@Serializable
enum class AttestationRefusal {
    /** An unlocked bootloader, a system its maker did not ship, or a key held only in software. */
    @SerialName("device-modified")
    DEVICE_MODIFIED,

    /** The proof the phone's hardware gives is not one the service recognises. */
    @SerialName("device-unverifiable")
    DEVICE_UNVERIFIABLE,

    /** This copy of the app is not the official one. */
    @SerialName("app-not-genuine")
    APP_NOT_GENUINE,
}

/**
 * A push the backend would have sent: the event it announces, and the member and token it was addressed to. [seq] is
 * the union position a wake for a gained photo announces; the close wake carries none (decision record
 * `changes/incremental-union`, D6).
 */
@Serializable
data class SentPush(val eventId: String, val deviceId: String, val token: String, val seq: Long? = null)

/**
 * One read of an event's union as the backend logged it: the reading device when its token verified (null otherwise),
 * why it said it read, the position it read from (null for a full read) and to, and how many assets it was given.
 */
@Serializable
data class UnionFetch(
    val deviceId: String?,
    val trigger: String?,
    val from: Long?,
    val to: Long,
    val served: Int,
)

/** Every switch an operator set ([BackendLevers]) as it stands now. */
@Serializable
data class BackendSwitches(
    val offline: Boolean,
    val failListing: Boolean,
    val failUnion: Boolean,
    val fallbackBearer: Boolean,
    /** The minimum app version every route demands, or `null` while the gate is off. */
    val minAppVersion: String?,
    /** Why the backend refuses this phone, or `null` while it does not. */
    val refusingAttestation: AttestationRefusal?,
    /** The calls held unanswered. */
    val held: List<BackendCall>,
)

/**
 * What the backend holds of one device's membership of an event: whether it left, how many publishes it applied, and
 * the manifest — an empty one for a member that has published nothing, `null` when it holds no membership.
 */
@Serializable
data class MembershipRecord(val departed: Boolean, val publishes: Int, val manifest: PublishedManifest?)

/**
 * What the backend logged of an event: how many of the devices' reads of its union and of its details reached it, every
 * union read in order (gone once the event completes), the position its union stands at (its last gained photo's),
 * whether it is FINISHED — its open-until date passed, someone joined, and every member still in it has shared and
 * received everything, which lets the nightly sweep delete its photos early — and whether it was completed.
 */
@Serializable
data class EventState(
    val unionReads: Int,
    val detailReads: Int,
    val unionFetches: List<UnionFetch>,
    val unionPosition: Long,
    val finished: Boolean,
    val completed: Boolean,
)

/** A member's manifest as the backend holds it: each asset with the roles it declares, and whether the share is final. */
@Serializable
data class PublishedManifest(val assets: List<PublishedAsset>, val final: Boolean, val version: Long?) {
    /** The declared roles, by asset id. */
    val roles: Map<String, Set<String>> get() = assets.associate { it.assetId to it.roles.toSet() }
}

/** One declared asset of a [PublishedManifest]. */
@Serializable
data class PublishedAsset(val assetId: String, val roles: List<String>)

/** A device's push registration as the backend stored it — none yet is `null` — and how many registrations it stored. */
@Serializable
data class StoredDeviceConfig(val token: String?, val env: String?, val writes: Int)
