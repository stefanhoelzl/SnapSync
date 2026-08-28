package app.snapsync.world

import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.roleFromUploadKey
import kotlinx.serialization.Serializable

/**
 * One entry of the per-device file listing (`GET /files/devices/<id>`) — `{filename, url}`.
 *
 * `size` used to ride here and is gone with the relational store (capability `api-endpoints`): it had no
 * reader on either read route, and it was the ONE field sourced from a storage listing rather than from
 * the backend's own record — so carrying it would have made the byte route's best-effort record
 * load-bearing, and one lost write would silently drop an asset from the union.
 */
@Serializable
class FileEntryDto(val filename: String, val url: String)

/**
 * One entry of the **v2** per-device listing — `{assetId, role, filename}`, and no `url`.
 *
 * The field named `filename` carries a different thing in each version: the storage KEY under v1, the
 * CAPTURE name under v2. That collision is the reason this world serves both shapes rather than one — a
 * client that misreads the field decodes either cleanly and seeds nonsense, and only a harness that can
 * answer both can catch it.
 *
 * The world does not model a capture name distinct from the key and answers with the key. That is exact
 * for every consumer: the key is recomposed from `assetId`/`role` plus this value's EXTENSION, which the
 * two spellings share.
 */
@Serializable
class DeviceResourceDto(val assetId: String, val role: String, val filename: String)

/** The outcome of a v2 join, mirroring the backend's own three answers. */
enum class JoinOutcome { ENROLLED, NO_SUCH_EVENT, FULL }

/** One resource of a union asset (`GET /events/<id>/files`). */
@Serializable
class UnionResourceDto(
    val role: String,
    val contentType: String,
    val key: String,
    val filename: String,
    val url: String,
)

/** One complete asset of the event-wide union, tagged with its owning device. */
@Serializable
class UnionAssetDto(
    val deviceId: String,
    val assetId: String,
    val creationDate: String,
    val resources: List<UnionResourceDto>,
)

/**
 * The `201` body of `POST /events` (and the `GET /events/<id>` read). [deletesAt] is the event's
 * retention deadline (capability `event-limits`) — DERIVED by the edge, never stored as a field, and
 * required on every `200`: the client refuses a details response missing it, because an invented
 * deadline would decide whether a membership is destroyed (capability `leave-event`).
 */
@Serializable
class CreatedEventDto(
    val eventId: String,
    val name: String,
    val createdAt: String,
    val startsAt: String,
    val endsAt: String,
    val deletesAt: String,
)

/** A device's participation in one event — a STATE, not the relative age of two sibling objects. */
enum class MemberState { ACTIVE, DEPARTED }

/**
 * One membership: the device's state in the event, and the assets it shares there.
 *
 * The assets are retained across a leave, which is what lets a departed member keep contributing to the
 * union, and are REPLACED wholesale by each publish (full-state, capability `device-manifest`).
 */
data class Membership(val state: MemberState, val manifest: DeviceManifest)

/**
 * The in-memory model of the edge's byte store + registry (capability `harness-world-model`) — the
 * single source of world truth. Three maps:
 *
 * - [byteStore]: `deviceId -> stored object names` (the `files/devices/<deviceId>/<filename>` byte partitions).
 * - [memberships]: `(eventId, deviceId) -> Membership` — ONE record per pair, carrying the device's
 *   `state` and the assets it shares there. Membership is a STATE, never two sibling objects resolved by
 *   last-write-wins: that resolution and its consumers went with the object layout (capability
 *   `database`), and modelling it here would keep a retired rule alive in test equipment.
 * - [events]: the registered-event set (a `POST /events` registers; the union gate reads it).
 *
 * From these it computes the edge's read-models **faithfully in behavior** — [deviceListing], [union],
 * and (the same as the per-device listing) the reconcile-seed listing. Byte-level fidelity to the real
 * Deno `api/` edge is NOT a goal: drift is accepted, there is no golden fixture, and each `url` is a
 * synthetic presigned-link stand-in (`https://world.store/<deviceId>/<filename>`) the fake download transport resolves
 * store-direct — never a real presigned S3 URL. [offline] flips the two GET read-models to failure so
 * the mini-edge answers `502`.
 */
class BackendStore {

    private val byteStore = mutableMapOf<String, MutableSet<String>>()
    private val memberships = mutableMapOf<Pair<String, String>, Membership>()
    private val events = mutableSetOf<String>()
    // Per-event human name (from `POST /events` or a direct injection), served by `GET /events/<id>`.
    private val eventNames = mutableMapOf<String, String>()
    // Per-event start date (capability `event-creation`). Absent ⇒ a legacy marker, whose `startsAt` the
    // mini-edge synthesizes from `createdAt` on read.
    private val eventStarts = mutableMapOf<String, String>()
    // Per-event end date (capability `event-limits`). Absent ⇒ the mini-edge's GET synthesizes `+30d`.
    private val eventEnds = mutableMapOf<String, String>()
    // Per-device config docs (`devices/<id>.json`, the push token) — a SEPARATE namespace from
    // the byte store, so a config never appears in [deviceListing] or the [union].
    private val deviceConfigs = mutableMapOf<String, String>()


    /** Failure lever: when true, the per-device listing and event-union routes fail (mini-edge `502`). */
    var offline: Boolean = false

    /**
     * Operator lever: the minimum app version the **v2** routes demand, or `null` for a gate that is OFF.
     *
     * Off by default and armed deliberately. A gate that refused by default would fail every seam that
     * does not yet declare a version — which is all of them until the client half ships — so the default
     * has to be the permissive one (capability `min-app-version`).
     */
    var minAppVersion: String? = null

    /** Devices ever enrolled per event (capability `event-limits`); the v2 join is the only route that refuses on it. */
    var capacity: Int = 10

    // ---- mutations ------------------------------------------------------------------------------

    /** Deposit one stored object into a device's byte partition (store-direct byte transfer). */
    fun deposit(deviceId: String, filename: String) {
        byteStore.getOrPut(deviceId) { linkedSetOf() }.add(filename)
    }

    /**
     * Register an event marker (the `POST /events` effect / a direct injection), with an optional name and
     * an optional [startsAt] — the event's start date, which is both the default and the FLOOR for every
     * member's capture-date cutoff (capability `photo-selection-policy`).
     *
     * An event registered with **no** `startsAt` models a marker written before start dates existed; the
     * mini-edge's `GET` then synthesizes one from `createdAt`, exactly as the real backend does.
     */
    fun registerEvent(
        eventId: String,
        name: String? = null,
        startsAt: String? = null,
        endsAt: String? = null,
    ) {
        events.add(eventId)
        if (name != null) eventNames[eventId] = name
        if (startsAt != null) eventStarts[eventId] = startsAt
        if (endsAt != null) eventEnds[eventId] = endsAt
    }

    /** The event's human name for `GET /events/<id>` (null when unnamed). */
    fun nameOf(eventId: String): String? = eventNames[eventId]

    /** The event's start date for `GET /events/<id>` (null when registered without one — a legacy marker). */
    fun startsAtOf(eventId: String): String? = eventStarts[eventId]

    /** The event's end date for `GET /events/<id>` (null when registered without one — the mini-edge then
     *  synthesizes `startsAt + 30d`, exactly as the real backend's creator-supplied/fallback stamp). */
    fun endsAtOf(eventId: String): String? = eventEnds[eventId]

    /**
     * Wipe a device's stored byte objects (models an operator deleting the `devices/<id>/files/`
     * partition from the bunny zone — e.g. via the dashboard or native Storage API). The per-device
     * listing then returns empty while the extension ledger still holds `COMPLETED` rows — the
     * storage-reset condition.
     */
    fun wipeBytes(deviceId: String) {
        byteStore.remove(deviceId)
    }

    fun isRegistered(eventId: String): Boolean = eventId in events

    /**
     * Delete an event from the registry — the **nightly sweep's** effect (capability
     * `scheduled-cleanup`), which is the only thing that ever removes one. Every subsequent
     * `GET /events/<id>` then answers `404`, exactly as it would against the real backend after a sweep.
     *
     * A DELIBERATELY blunt lever: the sweep runs out-of-edge and the world models no scheduler, so what
     * matters here is the observable consequence for a device that was still joined.
     */
    fun sweepEvent(eventId: String) {
        events.remove(eventId)
        eventNames.remove(eventId)
        eventStarts.remove(eventId)
        eventEnds.remove(eventId)
    }

    /**
     * Record a manifest publish from its JSON body (the `PUT /events/<id>/devices/<id>` effect).
     * Full-state: the membership's asset set becomes exactly what the body lists, and the membership
     * becomes `active` — a publish is how a rejoin re-enters an event it had left.
     */
    fun putManifestJson(eventId: String, deviceId: String, json: String) {
        putManifest(eventId, deviceId, deviceManifestFromJson(json))
    }

    /** Inject a device's shared asset set directly (used to set up foreign devices). */
    fun putManifest(eventId: String, deviceId: String, manifest: DeviceManifest) {
        memberships[eventId to deviceId] = Membership(state = MemberState.ACTIVE, manifest = manifest)
    }

    /**
     * The **v2 join** (`PUT /events/<id>/devices/<id>`, no body): create or reactivate the membership,
     * writing no manifest.
     *
     * An existing membership keeps its asset set — which is the whole point of splitting join from
     * publish. Under v1 a rejoin wrote an empty manifest and blanked the device's contribution until the
     * next cycle republished it; here there is no window at all.
     */
    fun join(eventId: String, deviceId: String): JoinOutcome {
        if (eventId !in events) return JoinOutcome.NO_SUCH_EVENT
        val existing = memberships[eventId to deviceId]
        if (existing != null) {
            memberships[eventId to deviceId] = existing.copy(state = MemberState.ACTIVE)
            return JoinOutcome.ENROLLED
        }
        // Capacity counts every membership ever enrolled, active or departed — leaving frees no slot.
        if (memberships.keys.count { it.first == eventId } >= capacity) return JoinOutcome.FULL
        memberships[eventId to deviceId] =
            Membership(state = MemberState.ACTIVE, manifest = DeviceManifest(deviceId, emptyList()))
        return JoinOutcome.ENROLLED
    }

    /**
     * The **v2 manifest publish** — contribution only. Replaces the membership's asset set and touches
     * nothing else: it enrolls nobody and does **not** reactivate a departed member.
     *
     * Answers `false` for a device holding no membership, which the mini-edge turns into the backend's
     * refusal. Modelling that as a create is the one divergence this world exists to prevent: it would let
     * a device pass here and fail against the real backend.
     */
    fun putManifestV2Json(eventId: String, deviceId: String, json: String): Boolean {
        val existing = memberships[eventId to deviceId] ?: return false
        memberships[eventId to deviceId] = existing.copy(manifest = deviceManifestFromJson(json))
        publishes[eventId to deviceId] = (publishes[eventId to deviceId] ?: 0) + 1
        return true
    }

    private val publishes = mutableMapOf<Pair<String, String>, Int>()

    /**
     * How many times a device has PUBLISHED its asset set for an event over the manifest route —
     * inspectable outcome.
     *
     * A count rather than a value, because the publish IS the announcement now (the versioned device API
     * has no notify route, and the backend fans out from the write): asserting the resulting manifest
     * cannot distinguish "published the same set again" from "did not publish", and those are exactly
     * the two answers a test about whether a cycle touched the backend needs to tell apart.
     *
     * Counts the route only. `putManifest` is the foreign-device injection helper, not a request, and is
     * deliberately not counted.
     */
    fun publishesOf(eventId: String, deviceId: String): Int = publishes[eventId to deviceId] ?: 0

    /** Store a device's config doc (the `PUT /devices/<id>` effect — push-token registration). */
    fun putDeviceConfig(deviceId: String, json: String) {
        deviceConfigs[deviceId] = json
    }

    /**
     * Leave an event (the `DELETE /events/<id>/devices/<id>` endpoint, capability `api-endpoints`).
     * ONE COLUMN: the membership's state becomes `departed`. Its assets STAY, so the union keeps serving
     * its photos, and nothing else happens — no last-member reap, no byte/config GC. The event survives
     * (rejoinable) until the nightly sweep reclaims it (capability `scheduled-cleanup`). Idempotent, and
     * a leave for an unregistered event or a device that never joined is a no-op.
     */
    fun leave(eventId: String, deviceId: String) {
        if (eventId !in events) return
        val existing = memberships[eventId to deviceId] ?: return
        memberships[eventId to deviceId] = existing.copy(state = MemberState.DEPARTED)
    }

    // ---- inspection (for tests) -----------------------------------------------------------------

    /** The raw object names stored for a device — inspectable world outcome. */
    fun objectsOf(deviceId: String): Set<String> = byteStore[deviceId].orEmpty().toSet()

    fun manifestOf(eventId: String, deviceId: String): DeviceManifest? =
        memberships[eventId to deviceId]?.manifest

    /** Whether a device has LEFT this event (its assets persist in the union) — inspectable outcome. */
    fun isDeparted(eventId: String, deviceId: String): Boolean =
        memberships[eventId to deviceId]?.state == MemberState.DEPARTED

    /** The stored config doc for a device (the registered push token), or null — inspectable outcome. */
    fun deviceConfigOf(deviceId: String): String? = deviceConfigs[deviceId]

    // ---- read-models ----------------------------------------------------------------------------

    /**
     * The per-device file listing (`GET /files/devices/<id>`) — one entry per stored object. Serves
     * BOTH the rejoin reconcile seed (`HttpDeviceFilesSource`) and own-device status completeness
     * (the ledger-backed status source); the world computes it once.
     */
    fun deviceListing(deviceId: String): List<FileEntryDto> =
        byteStore[deviceId].orEmpty().map { filename ->
            FileEntryDto(filename = filename, url = syntheticUrl(deviceId, filename))
        }

    /**
     * The **v2** per-device listing — identity terms, and no minted URL.
     *
     * Identity is recovered from the stored key through the same shared parsers the client uses, so the
     * world cannot answer with an identity the client would not have composed.
     */
    fun deviceListingV2(deviceId: String): List<DeviceResourceDto> =
        byteStore[deviceId].orEmpty().map { key ->
            DeviceResourceDto(
                assetId = assetIdFromUploadKey(key),
                role = roleFromUploadKey(key).wire,
                filename = key,
            )
        }

    /**
     * The event-wide union (`GET /events/<id>/files`): every contributing device's **complete** assets
     * (every manifest resource `key` present in that device's byte partition), each tagged with its
     * `deviceId`. Returns `null` when the event is unregistered (the marker gate — a 404-equivalent,
     * distinct from a registered-but-empty event which returns an empty list).
     */
    fun union(eventId: String): List<UnionAssetDto>? {
        if (eventId !in events) return null
        val out = mutableListOf<UnionAssetDto>()
        // BOTH states contribute: a member who has left keeps the photos it already shared in the union
        // until the event itself is deleted.
        for ((keyPair, membership) in memberships) {
            val (mEventId, deviceId) = keyPair
            if (mEventId != eventId) continue
            val manifest = membership.manifest
            val present = byteStore[deviceId].orEmpty()
            for (asset in manifest.assets) {
                if (asset.resources.isEmpty()) continue
                if (!asset.resources.all { it.key in present }) continue // complete-only
                out.add(
                    UnionAssetDto(
                        deviceId = deviceId,
                        assetId = asset.assetId,
                        creationDate = asset.creationDate,
                        resources = asset.resources.map { r ->
                            UnionResourceDto(
                                role = r.role.wire,
                                contentType = r.contentType,
                                key = r.key,
                                filename = r.filename,
                                url = syntheticUrl(deviceId, r.key),
                            )
                        },
                    ),
                )
            }
        }
        return out
    }

    companion object {
        /**
         * The synthetic in-memory download handle — resolved store-direct by the fake download seams,
         * and a synthetic stand-in for the backend's presigned S3 link. **It must be `https`**: the real
         * `QueuedPhotoDownloadJobs` guards every url with `isFetchableUrl` and skips anything that is not
         * `http`/`https` — handing a background `URLSession` a hostless or non-HTTP url raises an
         * uncatchable Obj-C exception, so the guard is load-bearing, not cosmetic.
         *
         * This was `world://…` while the world faked `PhotoDownloadJobs` and no guard ever ran, so the
         * harness proved downloads worked over a scheme production would have refused. Composing the real
         * jobs surfaced it immediately (capability `harness-world-model`).
         */
        fun syntheticUrl(deviceId: String, filename: String): String =
            "https://world.store/$deviceId/$filename"
    }
}

/** Build a foreign device's manifest asset for [BackendStore.putManifest]. */
fun foreignManifest(deviceId: String, assets: List<DeviceManifestAsset>): DeviceManifest =
    DeviceManifest(deviceId = deviceId, assets = assets)
