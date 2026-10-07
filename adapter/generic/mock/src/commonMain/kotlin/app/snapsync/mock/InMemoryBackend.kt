package app.snapsync.mock

import app.snapsync.model.DeviceRefusal
import app.snapsync.model.PushEndpoint
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
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionResource
import app.snapsync.model.UnionTrigger
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.uploadKey
import app.snapsync.ports.Backend
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The honest in-memory [Backend] — the backend's routes answered off a [BackendState], the mock the `Backend` port
 * contract holds to the real `api/` (`BackendContract`). One instance is one process's face of the backend: [declared]
 * is the version that process's build declares on every call, as the HTTP adapter declares it on the wire.
 *
 * It answers in the backend's own vocabulary — statuses and bodies — and decides nothing on the app's behalf: a
 * rejected credential is a `401`, a full event a `409`, a gone event a `404`, a refused build a `426` naming the
 * minimum. What those mean is the services' business above the port, which is exactly what makes a test over this
 * double exercise the real decisions.
 *
 * - **Events** get UUID ids it mints, as the real backend does, with its window rules: a blank name, or one longer than
 *   100 characters, is refused, an
 *   end before the start or more than 30 days after it is refused, an absent end is `start + 30 days`, and an event
 *   lives 30 days from `max(createdAt, startsAt)`. A legacy event — registered before start dates existed — has its
 *   start synthesized from its creation time on read, as the real backend does.
 * - **Membership** is one record per device with an active/departed state. A join creates or reactivates it and
 *   clears its stored manifest version; capacity counts every device ever enrolled, active or departed — leaving
 *   frees no slot. A leave marks the record departed: the member's photos stay in the union, and the leave that takes
 *   away the last member still unsettled closes the event, as a publish that settles it does.
 * - **The manifest** replaces the member's asset set, active or departed, and reactivates nobody; a strictly older
 *   version is answered as a success and changes nothing.
 * - **Credentials:** a call carrying no token is served — the local rig's enrolment fallback — as is one carrying a
 *   token this backend minted; any other token is `401`.
 * - **Attestation** mints only for a proof the in-memory integrity produced (`attestation:<keyId>:<challenge>`),
 *   over a challenge this backend issued, and never renews.
 * - **Bytes** are not a backend-port route — the OS's uploader writes them ([BackendState.receive]).
 * - **The version gate** is off until [BackendState.minAppVersion] is set; then a build declaring an older version,
 *   or none, is answered `426` on every route.
 */
@OptIn(ExperimentalUuidApi::class)
internal class InMemoryBackend(
    private val state: BackendState,
    private val declared: DeclaredVersion,
) : Backend {

    override suspend fun challenge(): Reply<String> = served {
        Reply.Ok(state.issueChallenge())
    }

    override suspend fun mintToken(req: MintRequest): Reply<String> = served {
        val genuine = req.challenge in state.challenges &&
            req.attestation.contentEquals("attestation:${req.keyId}:${req.challenge}".encodeToByteArray())
        when {
            state.refuseAttestation != null ->
                Reply.Refused(
                    UNAUTHORIZED,
                    "attestation rejected: ${state.refuseAttestation?.wireName}" +
                        (state.refuseAttestationDetail?.let { " ($it)" } ?: ""),
                )
            genuine -> Reply.Ok(state.mint(req.deviceId, req.challenge))
            else -> Reply.Refused(UNAUTHORIZED, "attestation rejected")
        }
    }

    override suspend fun renewToken(req: RenewRequest): Reply<String> = served {
        Reply.Refused(UNAUTHORIZED, "not attested")
    }

    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> =
        held(BackendCall.CREATE, token, gated = true) {
            val name = req.name.trim()
            val startsAt = parse(req.startsAt)
            val endsAt = req.endsAt?.let(::parse) ?: startsAt?.plus(WINDOW_DAYS.days)
            when {
                name.isEmpty() || name.length > MAX_NAME_LENGTH -> Reply.Refused(BAD_REQUEST, "invalid name")
                startsAt == null -> Reply.Refused(BAD_REQUEST, "invalid startsAt")
                endsAt == null || endsAt < startsAt || endsAt > startsAt + WINDOW_DAYS.days ->
                    Reply.Refused(BAD_REQUEST, "invalid endsAt")
                req.keyId?.let { !KEY_ID.matches(it) } == true -> Reply.Refused(BAD_REQUEST, "invalid keyId")
                else -> {
                    val eventId = state.nextEventId?.also { state.nextEventId = null } ?: Uuid.random().toString()
                    state.events[eventId] = BackendState.Event(name, state.createdAt, startsAt, endsAt, keyId = req.keyId)
                    Reply.Ok(EventCreated(eventId, name))
                }
            }
        }

    // The read is counted when it reaches the backend, before an operator's hold answers it.
    override suspend fun getEvent(eventId: String): Reply<EventMeta> = held(
        BackendCall.EVENT,
        token = null,
        online = true,
        arrived = { state.eventReads[eventId] = (state.eventReads[eventId] ?: 0) + 1 },
    ) {
        val event = state.events[eventId] ?: return@held notFound()
        val startsAt = event.startsAt ?: event.createdAt
        Reply.Ok(
            EventMeta(
                eventId = eventId,
                name = event.name,
                createdAt = event.createdAt.toString(),
                startsAt = startsAt.toString(),
                endsAt = (event.endsAt ?: (startsAt + WINDOW_DAYS.days)).toString(),
                deletesAt = (maxOf(event.createdAt, startsAt) + WINDOW_DAYS.days).toString(),
                // The mock stamps no instants; the real route serves the moment. Presence is what a reader acts on.
                closedAt = if (event.closed) state.createdAt.toString() else null,
                completedAt = if (event.completed) state.createdAt.toString() else null,
                members = state.members(eventId),
                keyId = event.keyId,
            ),
        )
    }

    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> = gated(token) {
        if (state.offline) return@gated offline()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return@gated Reply.Refused(BAD_REQUEST, "invalid name")
        val event = state.events[eventId] ?: return@gated notFound()
        if (event.closed) return@gated closed()
        event.name = trimmed
        Reply.Ok(EventRenamed(trimmed))
    }

    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        held(BackendCall.JOIN, token, gated = true) {
            if (state.offline) return@held offline()
            when (state.join(eventId, deviceId)) {
                BackendState.JoinOutcome.NO_SUCH_EVENT -> notFound()
                BackendState.JoinOutcome.FULL -> Reply.Refused(CONFLICT, "event full")
                BackendState.JoinOutcome.CLOSED -> closed()
                BackendState.JoinOutcome.ENROLLED -> Reply.Ok(Unit)
            }
        }

    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: DeviceManifest,
    ): Reply<Unit> = gated(token) {
        if (state.offline) return@gated offline()
        when (state.publish(eventId, deviceId, manifest)) {
            BackendState.PublishOutcome.NO_SUCH_EVENT -> notFound()
            BackendState.PublishOutcome.NOT_A_MEMBER -> Reply.Refused(CONFLICT, "not a member")
            BackendState.PublishOutcome.CLOSED -> Reply.Refused(CONFLICT, CLOSED_BODY)
            BackendState.PublishOutcome.COMPLETED -> closed()
            BackendState.PublishOutcome.APPLIED, BackendState.PublishOutcome.OLDER -> Reply.Ok(Unit)
        }
    }

    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String, received: Boolean): Reply<Unit> =
        held(BackendCall.LEAVE, token, gated = true) {
            if (state.offline) return@held offline()
            if (eventId !in state.events) return@held notFound()
            state.leave(eventId, deviceId)
            Reply.Ok(Unit)
        }

    // Public, but a token it is sent is verified — a rejected one is `401`, as on the real route — and names the reader
    // in the log (decision record `changes/incremental-union`, D5). Each resource's url is its download handle.
    override suspend fun eventFiles(token: String?, eventId: String, cursor: Long?, trigger: UnionTrigger): Reply<UnionPage> =
        state.locked {
            refusal<UnionPage>(token, gated = true, online = true)?.let { return@locked it }
            state.unionReads[eventId] = (state.unionReads[eventId] ?: 0) + 1
            val reader = token?.substringBefore('.')
            val (union, position) = state.unionPage(eventId, cursor, reader, trigger.wire) ?: return@locked notFound()
            Reply.Ok(
                UnionPage(
                    union.map { (deviceId, asset) ->
                        UnionAsset(
                            deviceId = deviceId,
                            assetId = asset.assetId,
                            creationDate = asset.creationDate,
                            resources = asset.resources.map {
                                UnionResource(it.key, BackendState.syntheticUrl(deviceId, it.key), it.role.wire, it.contentType, it.filename)
                            },
                        )
                    },
                    position,
                ),
            )
        }

    override suspend fun deviceFiles(token: String?, eventId: String, deviceId: String): Reply<List<DeviceFile>> =
        gated(token) {
            if (state.offline || state.failDeviceListing) return@gated offline()
            Reply.Ok(state.storedFiles[eventId to deviceId].orEmpty().toList())
        }

    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: PushEndpoint): Reply<Unit> =
        gated(token) {
            state.deviceConfigs[deviceId] = push
            state.deviceConfigWrites[deviceId] = (state.deviceConfigWrites[deviceId] ?: 0) + 1
            Reply.Ok(Unit)
        }

    // Every request is answered under the backend's lock, whole — the transaction a real backend runs it in.
    private inline fun <T> served(crossinline answer: () -> Reply<T>): Reply<T> =
        state.locked { refusal(token = null) ?: answer() }

    private inline fun <T> online(crossinline answer: () -> Reply<T>): Reply<T> =
        state.locked { refusal(token = null, online = true) ?: answer() }

    private inline fun <T> gated(token: String?, crossinline answer: () -> Reply<T>): Reply<T> =
        state.locked { refusal(token, gated = true) ?: answer() }

    /**
     * A route an operator can [hold][BackendCall]: refused as the route is, then — once [arrived] is recorded — the
     * hold waited on, then answered. Two locked steps with the wait between them, unlocked: the operator's release
     * takes the lock.
     */
    private suspend inline fun <T> held(
        call: BackendCall,
        token: String?,
        gated: Boolean = false,
        online: Boolean = false,
        crossinline arrived: () -> Unit = {},
        crossinline answer: () -> Reply<T>,
    ): Reply<T> {
        state.locked { refusal<T>(token, gated, online) ?: run { arrived(); null } }?.let { return it }
        state.awaitRelease(call)
        return state.locked { answer() }
    }

    /** What the route answers before its own work, if anything: the version gate, then offline or the credential. */
    private fun <T> refusal(token: String?, gated: Boolean = false, online: Boolean = false): Reply<T>? {
        state.refusalFor(declared.value)?.let { return Reply.Refused(UPGRADE_REQUIRED, it) }
        return when {
            online && state.offline -> offline()
            !gated -> null
            state.refuseAttestation != null -> Reply.Refused(UNAUTHORIZED, "unattested")
            token != null && state.refuseNextCredential -> {
                state.refuseNextCredential = false
                Reply.Refused(UNAUTHORIZED, "credential rejected")
            }
            token != null && token !in state.minted -> Reply.Refused(UNAUTHORIZED, "invalid token")
            else -> null
        }
    }

    private fun <T> notFound(): Reply<T> = Reply.Refused(NOT_FOUND, "not found")

    private fun <T> closed(): Reply<T> = Reply.Refused(GONE, CLOSED_BODY)

    private fun <T> offline(): Reply<T> = Reply.Refused(BAD_GATEWAY, "offline")

    private fun parse(raw: String): Instant? = runCatchingCancellable { Instant.parse(raw) }.getOrNull()

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val CLOSED_BODY = "{\"error\":\"closed\"}"
        const val UPGRADE_REQUIRED = 426
        const val BAD_GATEWAY = 502
        const val WINDOW_DAYS = 30
        /** The shape of an encrypted event's key id, as the real `POST /events` validates it. */
        val KEY_ID = Regex("^[0-9a-f]{16}$")

        /** The longest event name the backend accepts, trimmed — the real backend's `MAX_EVENT_NAME_LENGTH`. */
        const val MAX_NAME_LENGTH = 100
    }
}

/** A manifest resource's upload key — the name the byte route stores it under. */
internal fun storedKey(file: DeviceFile): String = uploadKey(file.assetId, file.role, file.filename)
