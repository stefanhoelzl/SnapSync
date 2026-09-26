package app.snapsync.fake

import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionResource
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.uploadKey
import app.snapsync.ports.Backend
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The honest in-memory [Backend] — the backend's routes answered off in-memory state, the mock the `Backend` port
 * contract holds to the real `api/` (`BackendContract`).
 *
 * It answers in the backend's own vocabulary — statuses and bodies — and decides nothing on the app's behalf: a
 * rejected credential is a `401`, a full event a `409`, a gone event a `404`, a refused build a `426` naming the
 * minimum. What those mean is the services' business above the port, which is exactly what makes a test over this
 * double exercise the real decisions.
 *
 * - **Events** get UUID ids it mints, as the real backend does, with its window rules: a blank name is refused, an
 *   end before the start or more than 30 days after it is refused, an absent end is `start + 30 days`, and an event
 *   lives 30 days from `max(createdAt, startsAt)`.
 * - **Credentials:** a call carrying no token is served — the local rig's enrolment fallback, the only way a host
 *   without App Attest is ever served — as is one carrying a token this backend minted; any other token is `401`.
 * - **Attestation** mints only for a proof the in-memory integrity produced (`attestation:<keyId>:<challenge>`),
 *   over a challenge this backend issued, and never renews: it holds no enrolment, which is the faithful default
 *   (a restore, or a record the sweep collected) and sends a device down a full attestation.
 * - **Bytes** are not a backend route here — the OS's uploader writes them — so the store they land in is initial
 *   state: [storedFiles] is a cell the caller holds, as the gallery fakes take theirs, and a binding "uploads" by
 *   writing it. The per-device listing and the union read it.
 *
 * [createdAt] is the moment every event it mints is stamped with — initial state, not a clock: a port holding another
 * port would be one external system reaching through another (`docs/architecture.md`, "Ports never call ports").
 *
 * [minimumAppVersion] set is a backend that refuses THIS build: every route answers `426` naming it. The version a
 * build declares is the HTTP adapter's wire concern, so here the refusal is the backend's state, not a comparison.
 */
@OptIn(ExperimentalUuidApi::class)
internal class InMemoryBackend(
    private val storedFiles: MutableMap<String, MutableSet<DeviceFile>>,
    private val capacity: Int,
    private val minimumAppVersion: String?,
    private val createdAt: Instant,
) : Backend {

    private class Event(var name: String, val createdAt: Instant, val startsAt: Instant, val endsAt: Instant)

    private class Membership(var departed: Boolean = false, var manifest: DeviceManifest? = null)

    private val events = mutableMapOf<String, Event>()
    private val memberships = mutableMapOf<Pair<String, String>, Membership>()
    private val deviceConfigs = mutableMapOf<String, ApnsPushToken>()
    private val challenges = mutableSetOf<String>()
    private val minted = mutableSetOf<String>()

    override suspend fun challenge(): Reply<String> = served {
        Reply.Ok("in-memory-challenge-${challenges.size + 1}".also { challenges += it })
    }

    override suspend fun mintToken(req: MintRequest): Reply<String> = served {
        val genuine = req.challenge in challenges &&
            req.attestation.contentEquals("attestation:${req.keyId}:${req.challenge}".encodeToByteArray())
        if (genuine) Reply.Ok(mint(req.deviceId, req.challenge)) else Reply.Refused(UNAUTHORIZED, "attestation rejected")
    }

    override suspend fun renewToken(req: RenewRequest): Reply<String> = served {
        Reply.Refused(UNAUTHORIZED, "not attested")
    }

    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = gated(token) {
        val name = req.name.trim()
        val startsAt = parse(req.startsAt)
        val endsAt = req.endsAt?.let(::parse) ?: startsAt?.plus(WINDOW_DAYS.days)
        when {
            name.isEmpty() -> Reply.Refused(BAD_REQUEST, "invalid name")
            startsAt == null -> Reply.Refused(BAD_REQUEST, "invalid startsAt")
            endsAt == null || endsAt < startsAt || endsAt > startsAt + WINDOW_DAYS.days ->
                Reply.Refused(BAD_REQUEST, "invalid endsAt")
            else -> {
                val eventId = Uuid.random().toString()
                events[eventId] = Event(name, createdAt, startsAt, endsAt)
                Reply.Ok(EventCreated(eventId, name))
            }
        }
    }

    override suspend fun getEvent(eventId: String): Reply<EventMeta> = served {
        val event = events[eventId] ?: return@served notFound()
        Reply.Ok(
            EventMeta(
                eventId = eventId,
                name = event.name,
                createdAt = event.createdAt.toString(),
                startsAt = event.startsAt.toString(),
                endsAt = event.endsAt.toString(),
                deletesAt = (maxOf(event.createdAt, event.startsAt) + WINDOW_DAYS.days).toString(),
            ),
        )
    }

    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> = gated(token) {
        val event = events[eventId] ?: return@gated notFound()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@gated Reply.Refused(BAD_REQUEST, "invalid name")
        event.name = trimmed
        Reply.Ok(EventRenamed(trimmed))
    }

    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = gated(token) {
        if (eventId !in events) return@gated notFound()
        val key = eventId to deviceId
        val existing = memberships[key]
        when {
            existing != null -> existing.departed = false
            memberships.count { it.key.first == eventId && !it.value.departed } >= capacity ->
                return@gated Reply.Refused(CONFLICT, "event full")
            else -> memberships[key] = Membership()
        }
        Reply.Ok(Unit)
    }

    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: DeviceManifest,
    ): Reply<Unit> = gated(token) {
        if (eventId !in events) return@gated notFound()
        val membership = memberships[eventId to deviceId]?.takeUnless { it.departed }
            ?: return@gated Reply.Refused(CONFLICT, "not a member")
        val held = membership.manifest?.version
        val incoming = manifest.version
        // An older snapshot landing last is answered, and changes nothing: one at least as new is already there.
        if (held == null || incoming == null || incoming >= held) membership.manifest = manifest
        Reply.Ok(Unit)
    }

    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = gated(token) {
        if (eventId !in events) return@gated notFound()
        memberships[eventId to deviceId]?.departed = true
        Reply.Ok(Unit)
    }

    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = served {
        if (eventId !in events) return@served notFound()
        Reply.Ok(
            memberships.filter { it.key.first == eventId && !it.value.departed }.flatMap { (key, membership) ->
                val deviceId = key.second
                val stored = storedFiles[deviceId].orEmpty().map { uploadKey(it.assetId, it.role, it.filename) }.toSet()
                membership.manifest?.assets.orEmpty()
                    // Never half a photo: an asset is served once every resource it declares has landed.
                    .filter { asset -> asset.resources.all { it.key in stored } }
                    .map { asset ->
                        UnionAsset(
                            deviceId = deviceId,
                            assetId = asset.assetId,
                            creationDate = asset.creationDate,
                            resources = asset.resources.map {
                                UnionResource(it.key, "https://in-memory.store/$deviceId/${it.key}", it.role.wire, it.contentType, it.filename)
                            },
                        )
                    }
            },
        )
    }

    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> = gated(token) {
        Reply.Ok(storedFiles[deviceId].orEmpty().toList())
    }

    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> =
        gated(token) {
            deviceConfigs[deviceId] = push
            Reply.Ok(Unit)
        }

    /** A well-formed token, distinct per mint: its signature is the challenge it was minted over. */
    private fun mint(deviceId: String, challenge: String): String =
        "$deviceId.$TOKEN_EXPIRES_AT_EPOCH_SECONDS.$challenge".also { minted += it }

    private inline fun <T> served(answer: () -> Reply<T>): Reply<T> =
        minimumAppVersion?.let { Reply.Refused(UPGRADE_REQUIRED, """{"error":"app too old","minAppVersion":"$it"}""") }
            ?: answer()

    private inline fun <T> gated(token: String?, answer: () -> Reply<T>): Reply<T> = served {
        if (token != null && token !in minted) Reply.Refused(UNAUTHORIZED, "invalid token") else answer()
    }

    private fun <T> notFound(): Reply<T> = Reply.Refused(NOT_FOUND, "not found")

    private fun parse(raw: String): Instant? = runCatchingCancellable { Instant.parse(raw) }.getOrNull()

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UPGRADE_REQUIRED = 426
        const val WINDOW_DAYS = 30

        /** Epoch seconds a minted token expires at: 90 days, which outlives any test's pinned clock. */
        const val TOKEN_EXPIRES_AT_EPOCH_SECONDS: Long = 90L * 24 * 60 * 60
    }
}
