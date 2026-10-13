package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import app.snapsync.model.ManifestResource
import app.snapsync.model.PushEndpoint
import app.snapsync.model.ResourceRole
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.AppTooOldError
import app.snapsync.wire.models.AppTooOldErrorError
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.Challenge
import app.snapsync.wire.models.ClosedError
import app.snapsync.wire.models.ClosedErrorError
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.Event
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.EventDetails
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.MemberCounts
import app.snapsync.wire.models.MintRequest
import app.snapsync.wire.models.Received
import app.snapsync.wire.models.RenewRequest
import app.snapsync.wire.models.Role
import app.snapsync.wire.models.StoredResource
import app.snapsync.wire.models.Token
import app.snapsync.wire.models.UnionAsset
import app.snapsync.wire.models.UnionResource
import app.snapsync.wire.runtime.WireJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The backend's HTTP face — the device API of `api/openapi.yaml` answered off a [BackendState], at the
 * [HttpTransport] port, so the app's real gateway, routes and replies run over it exactly as over the real `api/`
 * (the Backend contract holds both to the same clauses).
 *
 * It answers in the description's vocabulary — statuses, texts and JSON bodies — and decides nothing on the app's
 * behalf: what a `401` or a `409` means is the services' business above the gateway.
 *
 * - **The version gate** is off until [BackendState.minAppVersion] is set; then a request whose `x-snapsync-app-version`
 *   is older, absent or unparseable is answered `426` naming the minimum — on every route.
 * - **The token gate:** a gated operation refuses a token this backend did not mint as `401 unattested`, and one
 *   minted for another device than its path names as `403 not this device`; the two legacy optional reads
 *   (`getEvent`, `eventFiles`) check only a token they are sent. A request with NO `Authorization` header is served
 *   as the local dev server serves it ([BackendState.fallbackBearer], on by default — `api/src/dev/serve.ts`'s
 *   fallback bearer, which the rig and the simulator stand on); with the lever off it is `401 unattested`, as
 *   deployed.
 * - **Events** get UUID ids it mints, with the real window rules: a blank name, or one longer than
 *   [Limits.EVENT_NAME_MAX_LENGTH] trimmed, is refused; an end before the start or more than 30 days after it is
 *   refused, an absent end is `start + 30 days`, an open-until date is the end or at most 30 days after the later
 *   of the end and now, and an event lives 30 days from `max(createdAt, openUntil)`. Nothing closes.
 * - **Attestation** mints only for a proof the in-memory integrity produced (`attestation:<keyId>:<challenge>`), over
 *   a challenge this backend issued — one it did not issue is stale, as the real issuers answer it — and never
 *   renews.
 * - **Bytes** land through the byte routes — as an OS transfer reaches them ([BackendState.receive], which the
 *   transfer mocks call), or as a request here, which answers the same.
 * - **Faults** an operator injects ([BackendOperator.fail]) answer before the route runs, as a broken network or a
 *   failing deploy would.
 */
@OptIn(ExperimentalUuidApi::class)
internal class BackendRouter(private val state: BackendState) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResult {
        val call = Call.of(request) ?: return text(NOT_FOUND, "not found")
        state.locked { state.takeFault(call.route.id) }?.let { return it.answer() }
        state.locked { refusal(call) }?.let { return it }
        val hold = call.route.hold
        if (hold != null) {
            state.locked { if (hold == BackendCall.EVENT) state.countEventRead(call.param("eventId")) }
            state.awaitRelease(hold)
        }
        return state.locked { answer(call) }
    }

    /** What a request is answered before its route runs, if anything: the version gate, then the token's. */
    private fun refusal(call: Call): HttpResult? {
        if (call.route.versioned || call.header(Limits.VERSION_HEADER) != null) {
            state.minimumRefusing(call.header(Limits.VERSION_HEADER))?.let { minimum ->
                return json(
                    UPGRADE_REQUIRED,
                    AppTooOldError.serializer(),
                    AppTooOldError(AppTooOldErrorError.APP_TOO_OLD, minimum),
                )
            }
        }
        if (call.route.online && state.offline) return offline()
        val token = call.token
        return when (call.route.access) {
            Access.OPEN -> null
            Access.OPTIONAL -> if (token == null) null else credentialRefusal(token)
            Access.GATED -> when {
                state.refuseAttestation != null -> unattested()
                token == null -> if (state.fallbackBearer) null else unattested()
                else -> credentialRefusal(token)
            }
        } ?: call.paramOrNull("deviceId")
            ?.takeIf { call.route.access == Access.GATED && token != null && it != call.tokenDevice }
            ?.let { text(FORBIDDEN, "not this device") }
    }

    private fun credentialRefusal(token: String): HttpResult? = when {
        state.refuseNextCredential -> {
            state.refuseNextCredential = false
            unattested()
        }
        token !in state.minted -> unattested()
        else -> null
    }

    private fun answer(call: Call): HttpResult = when (call.route.id) {
        Routes.Challenge.ID -> json(OK, Challenge.serializer(), Challenge(state.issueChallenge()))
        Routes.MintToken.ID -> mint(call)
        Routes.RenewToken.ID -> renew(call)
        Routes.CreateEvent.ID -> create(call)
        Routes.GetEvent.ID -> details(call.param("eventId"))
        Routes.ChangeEvent.ID -> change(call)
        Routes.JoinEvent.ID -> join(call)
        Routes.LeaveEvent.ID -> leave(call)
        Routes.PublishManifest.ID -> publish(call)
        Routes.ReportReceived.ID -> received(call)
        Routes.EventFiles.ID -> union(call)
        Routes.DeviceFiles.ID -> listing(call)
        Routes.PutDevice.ID -> putDevice(call)
        Routes.UploadResource.ID, Routes.UploadResourceEventless.ID -> bytes(call)
        Routes.UploadState.ID -> uploadState(call)
        else -> text(NOT_FOUND, "not found")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun mint(call: Call): HttpResult {
        val req = call.decode(MintRequest.serializer()) ?: return text(BAD_REQUEST, "invalid body")
        state.refuseAttestation?.let { refusal ->
            val detail = state.refuseAttestationDetail?.let { " ($it)" } ?: ""
            return text(UNAUTHORIZED, "attestation rejected: ${refusal.wireName}$detail")
        }
        if (req.challenge !in state.challenges) return text(CONFLICT, "stale challenge")
        val proof = req.proof as? AppleAppAttestProof
        val genuine = proof != null &&
            runCatchingCancellable { Base64.decode(proof.attestation) }.getOrNull()
                ?.contentEquals("attestation:${proof.keyId}:${req.challenge}".encodeToByteArray()) == true
        if (!genuine) return text(UNAUTHORIZED, "attestation rejected: ${DeviceRefusal.DEVICE_UNVERIFIABLE.wireName}")
        state.attestedKeys[req.deviceId] = proof.keyId
        return json(CREATED, Token.serializer(), Token(state.mint(req.deviceId, req.challenge)))
    }

    // A renewal is an assertion by the key the device attested with, over a challenge this backend issued.
    @OptIn(ExperimentalEncodingApi::class)
    private fun renew(call: Call): HttpResult {
        val req = call.decode(RenewRequest.serializer()) ?: return text(BAD_REQUEST, "invalid body")
        if (req.challenge !in state.challenges) return text(CONFLICT, "stale challenge")
        // A service that refuses this device's attestation holds none for it: the device is sent to attest afresh.
        if (state.refuseAttestation != null) return text(UNAUTHORIZED, "not attested")
        val key = state.attestedKeys[req.deviceId] ?: return text(UNAUTHORIZED, "not attested")
        val asserted = runCatchingCancellable { Base64.decode(req.assertion) }.getOrNull()
            ?.contentEquals("assertion:$key:${req.challenge}".encodeToByteArray()) == true
        if (!asserted) return text(UNAUTHORIZED, "assertion rejected")
        return json(CREATED, Token.serializer(), Token(state.mint(req.deviceId, req.challenge)))
    }

    private fun create(call: Call): HttpResult {
        val req = call.decode(EventCreate.serializer()) ?: return text(BAD_REQUEST, "invalid body")
        val name = req.name.trim()
        val startsAt = instantOrNull(req.startsAt)
        val endsAt = req.endsAt?.let(::instantOrNull) ?: startsAt?.plus(WINDOW_DAYS.days)
        val openUntil = openUntilOf(req.openUntil, endsAt)
        return when {
            name.isEmpty() || name.length > Limits.EVENT_NAME_MAX_LENGTH -> text(BAD_REQUEST, "invalid name")
            startsAt == null -> text(BAD_REQUEST, "invalid startsAt")
            endsAt == null || endsAt < startsAt || endsAt > startsAt + WINDOW_DAYS.days ->
                text(BAD_REQUEST, "invalid endsAt")
            openUntil == null -> text(BAD_REQUEST, "invalid openUntil")
            req.keyId?.let { !KEY_ID.matches(it) } == true -> text(BAD_REQUEST, "invalid keyId")
            else -> {
                val eventId = state.nextEventId?.also { state.nextEventId = null } ?: Uuid.random().toString()
                val event = BackendState.Event(name, state.createdAt, startsAt, endsAt, openUntil, keyId = req.keyId)
                state.events[eventId] = event
                json(CREATED, Event.serializer(), event.onWire(eventId))
            }
        }
    }

    /**
     * The open-until date [raw] asks for over a range ending [endsAt], or `null` for one the backend refuses: absent is
     * the end; present, at least the end and at most the window after the later of the end and now — plus the hour a
     * creating phone's clock may run ahead, as the real api allows.
     */
    private fun openUntilOf(raw: String?, endsAt: Instant?): Instant? {
        if (endsAt == null) return null
        val openUntil = raw?.let { instantOrNull(it) ?: return null } ?: return endsAt
        return openUntil.takeIf { it >= endsAt && it <= maxOf(endsAt, state.now() + OPEN_UNTIL_CLOCK_SLACK) + WINDOW_DAYS.days }
    }

    private fun details(eventId: String): HttpResult {
        val event = state.events[eventId] ?: return eventNotFound()
        val members = state.members(eventId)
        val e = event.onWire(eventId)
        val details = EventDetails(
            eventId = e.eventId,
            name = e.name,
            createdAt = e.createdAt,
            startsAt = e.startsAt,
            endsAt = e.endsAt,
            openUntil = e.openUntil,
            capacity = e.capacity,
            deletesAt = e.deletesAt,
            closedAt = e.closedAt,
            completedAt = e.completedAt,
            keyId = e.keyId,
            members = MemberCounts(members.active, members.settled, members.waitingFor),
        )
        return json(OK, EventDetails.serializer(), details)
    }

    private fun change(call: Call): HttpResult {
        val name = call.decode(EventChange.serializer())?.name?.trim()
        if (name.isNullOrEmpty() || name.length > Limits.EVENT_NAME_MAX_LENGTH) return text(BAD_REQUEST, "invalid name")
        val eventId = call.param("eventId")
        val event = state.events[eventId] ?: return eventNotFound()
        // A completed event — or one closed before events stopped closing — changes no more.
        if (event.closed || event.completed) return closed()
        event.name = name
        return json(OK, Event.serializer(), event.onWire(eventId))
    }

    private fun join(call: Call): HttpResult = when (state.join(call.param("eventId"), call.param("deviceId"))) {
        BackendState.JoinOutcome.NO_SUCH_EVENT -> eventNotFound()
        BackendState.JoinOutcome.CLOSED -> closed()
        BackendState.JoinOutcome.ENROLLED -> empty(OK)
    }

    private fun leave(call: Call): HttpResult {
        val eventId = call.param("eventId")
        if (eventId !in state.events) return eventNotFound()
        state.leave(eventId, call.param("deviceId"))
        return empty(OK)
    }

    private fun publish(call: Call): HttpResult {
        val manifest = call.decode(Manifest.serializer())?.inApp(call.param("deviceId"))
            ?: return text(BAD_REQUEST, "invalid manifest")
        return when (state.publish(call.param("eventId"), call.param("deviceId"), manifest)) {
            BackendState.PublishOutcome.NO_SUCH_EVENT -> eventNotFound()
            BackendState.PublishOutcome.NOT_A_MEMBER -> text(CONFLICT, "not a member")
            BackendState.PublishOutcome.CLOSED ->
                json(CONFLICT, ClosedError.serializer(), ClosedError(ClosedErrorError.CLOSED))
            BackendState.PublishOutcome.COMPLETED -> closed()
            BackendState.PublishOutcome.APPLIED, BackendState.PublishOutcome.OLDER -> empty(OK)
        }
    }

    private fun received(call: Call): HttpResult {
        val position = call.decode(Received.serializer())?.position?.takeIf { it >= 0 }
            ?: return text(BAD_REQUEST, "invalid position")
        val eventId = call.param("eventId")
        if (eventId !in state.events) return eventNotFound()
        return if (state.received(
                eventId,
                call.param("deviceId"),
                position,
            )
        ) {
            empty(OK)
        } else {
            text(CONFLICT, "not a member")
        }
    }

    // Public, but a token it is sent names the reader in the read log (decision record `changes/incremental-union`, D5).
    private fun union(call: Call): HttpResult {
        val eventId = call.param("eventId")
        val cursor = call.query["cursor"]?.let { it.toLongOrNull() ?: return text(BAD_REQUEST, "invalid cursor") }
        if (state.failUnion) return offline()
        state.unionReads[eventId] = (state.unionReads[eventId] ?: 0) + 1
        val trigger = call.header(Routes.EventFiles.HEADER_SNAPSYNC_TRIGGER)
        val (union, position) = state.unionPage(eventId, cursor, call.tokenDevice, trigger) ?: return eventNotFound()
        val urls = call.query["urls"] != "false"
        val items = union.map { (deviceId, asset) ->
            UnionAsset(
                deviceId = deviceId,
                assetId = asset.assetId.value,
                creationDate = asset.creationDate,
                resources = asset.resources.map {
                    val role = it.role.onWire()
                    val url = call.base + Routes.DownloadResource.path(eventId, deviceId, asset.assetId.value, role.value)
                    UnionResource(role, it.contentType, it.key, it.filename, url.takeIf { urls })
                },
            )
        }
        return json(
            OK,
            ListSerializer(UnionAsset.serializer()),
            items,
            mapOf(CURSOR to position.toString(), DECLARED to state.declared(eventId).toString()),
        )
    }

    private fun listing(call: Call): HttpResult {
        if (state.offline || state.failDeviceListing) return offline()
        val files = state.storedFiles[call.param("eventId") to call.param("deviceId")].orEmpty()
        val items = files.map { StoredResource(it.assetId.value, it.role.onWire(), it.filename) }
        return json(OK, ListSerializer(StoredResource.serializer()), items)
    }

    private fun putDevice(call: Call): HttpResult {
        val config = call.decode(DeviceConfig.serializer()) ?: return text(BAD_REQUEST, "invalid body")
        val deviceId = call.param("deviceId")
        config.pushToken?.let { state.deviceConfigs[deviceId] = PushEndpoint(it.kind, it.token, it.env) }
        state.deviceConfigWrites[deviceId] = (state.deviceConfigWrites[deviceId] ?: 0) + 1
        return empty(CREATED)
    }

    /**
     * A byte upload, answered as [BackendState.receive] answers the OS transfer that carries one — but a body that ends
     * short of the range it declared keeps what arrived, as the real byte route does (`docs/architecture.md`,
     * "Resumable uploads").
     */
    private fun bytes(call: Call): HttpResult {
        val sha256 = call.header(Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256)
        val total = call.header(Routes.UploadResource.HEADER_CONTENT_RANGE)?.substringAfterLast('/')?.toLongOrNull()
        val received = call.bodyLength
        if (sha256 != null && total != null && received < total && call.route.id == Routes.UploadResource.ID) {
            if (!state.mayUpload(call.param("eventId"), call.param("deviceId"))) return text(FORBIDDEN, "not a member")
            state.partial[call.resourceKey] = sha256 to received
            return text(BAD_REQUEST, "body shorter than declared")
        }
        return when (val status = state.receive(call.url, call.headers)) {
            CREATED -> empty(CREATED)
            BAD_REQUEST -> text(BAD_REQUEST, "invalid upload")
            FORBIDDEN -> text(FORBIDDEN, "not a member")
            CONFLICT -> text(CONFLICT, "no event")
            else -> text(status, "offline")
        }
    }

    /** Where an upload of these bytes stands: landed, partly stored, or nothing stored for them. */
    private fun uploadState(call: Call): HttpResult {
        if (!state.mayUpload(call.param("eventId"), call.param("deviceId"))) return text(FORBIDDEN, "not a member")
        val sha256 = call.header(Routes.UploadState.HEADER_X_SNAPSYNC_SHA256)
        val key = call.resourceKey
        val partial = state.partial[key]
        return when {
            sha256 != null && state.landedSha256[key] == sha256 -> empty(NO_CONTENT)
            sha256 != null && partial?.first == sha256 ->
                HttpResult.Response(OK, mapOf(UPLOAD_OFFSET to "${partial.second}"), "")
            else -> text(NOT_FOUND, "nothing stored")
        }
    }

    private fun BackendState.Event.onWire(eventId: String): Event = Event(
        eventId = eventId,
        name = name,
        createdAt = createdAt.toString(),
        startsAt = startsAt.toString(),
        endsAt = endsAt.toString(),
        openUntil = openUntil.toString(),
        capacity = BackendMock.CAPACITY_PLACEHOLDER,
        // The lifetime is 30 days from the later of the creation and the open-until date.
        deletesAt = (maxOf(createdAt, openUntil) + WINDOW_DAYS.days).toString(),
        // For builds that predate open-until dates, derived as the real route derives it. The mock stamps no
        // instants; the real route serves a moment. Presence is what a reader acts on.
        closedAt = if (state.closedForOldBuilds(eventId)) openUntil.toString() else null,
        completedAt = if (completed) state.createdAt.toString() else null,
        keyId = keyId,
    )

    private fun eventNotFound(): HttpResult = text(NOT_FOUND, "event not found")

    private fun closed(): HttpResult = json(GONE, ClosedError.serializer(), ClosedError(ClosedErrorError.CLOSED))

    private fun unattested(): HttpResult = text(UNAUTHORIZED, "unattested")

    private fun offline(): HttpResult = text(BAD_GATEWAY, "offline")

    /** A whole-second UTC instant, the only shape the description admits — or `null`. */
    private fun instantOrNull(raw: String): Instant? =
        raw.takeIf { WHOLE_SECOND.matches(it) }?.let { runCatchingCancellable { Instant.parse(it) }.getOrNull() }

    /** How an operation is reached — which of its gates run, which hold an operator can put on it. */
    private enum class Access { OPEN, OPTIONAL, GATED }

    private class Route(
        val id: String,
        val method: String,
        template: String,
        val access: Access,
        /** Answered `502` while the backend is [BackendState.offline], before the token is looked at. */
        val online: Boolean = false,
        val hold: BackendCall? = null,
        /** Whether a request without the version header is refused while the gate is armed. */
        val versioned: Boolean = true,
    ) {
        private val names = Regex("""\{(\w+)\}""").findAll(template).map { it.groupValues[1] }.toList()
        private val pattern = Regex("^" + template.replace(Regex("""\{\w+\}"""), "([^/]+)") + "$")

        fun match(method: String, path: String): Map<String, String>? {
            if (method != this.method) return null
            val found = pattern.matchEntire(path) ?: return null
            return names.zip(found.groupValues.drop(1).map(::percentDecoded)).toMap()
        }
    }

    private class Call(
        val route: Route,
        /** The request's absolute address, query included. */
        val url: String,
        val base: String,
        private val params: Map<String, String>,
        val query: Map<String, String>,
        /** The request's headers, as sent. */
        val headers: Map<String, String>,
        private val body: String?,
    ) {
        private val named = headers.mapKeys { it.key.lowercase() }

        fun header(name: String): String? = named[name.lowercase()]

        fun param(name: String): String = params.getValue(name)

        fun paramOrNull(name: String): String? = params[name]

        /** The bearer token the request carries, or `null`. */
        val token: String? = header("Authorization")?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

        /** The device a minted token was minted for — it rides inside the token, as on the real backend. */
        val tokenDevice: String? get() = token?.substringBefore('.')

        /** How many bytes the request's body carries. */
        /** The resource a byte route names: event, device, asset and role. */
        val resourceKey: String get() = "${param("eventId")}/${param("deviceId")}/${param("assetId")}/${param("role")}"

        val bodyLength: Long get() = body?.encodeToByteArray()?.size?.toLong() ?: 0

        fun <T> decode(strategy: DeserializationStrategy<T>): T? =
            body?.let { runCatchingCancellable { WireJson.decodeFromString(strategy, it) }.getOrNull() }

        companion object {
            fun of(request: HttpRequest): Call? {
                val rest = request.url.substringAfter("://")
                val afterHost = rest.indexOf('/').takeIf { it >= 0 }?.let(rest::substring) ?: return null
                val pathAndQuery = afterHost.substringBefore('#')
                val full = pathAndQuery.substringBefore('?')
                val prefix = VERSION_PREFIX.find(full)?.value.orEmpty()
                val path = full.removePrefix(prefix)
                val query = pathAndQuery.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
                    .associate { percentDecoded(it.substringBefore('=')) to percentDecoded(it.substringAfter('=', "")) }
                val base = request.url.substringBefore(afterHost) + prefix
                return ROUTES.firstNotNullOfOrNull { route ->
                    route.match(
                        request.method,
                        path,
                    )?.let { Call(route, request.url, base, it, query, request.headers, request.body) }
                }
            }
        }
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NO_CONTENT = 204
        const val UPLOAD_OFFSET = "Upload-Offset"
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val UPGRADE_REQUIRED = 426
        const val BAD_GATEWAY = 502
        const val WINDOW_DAYS = 30

        /** How far a creating phone's clock may run ahead when it asks to stay open the whole window. */
        val OPEN_UNTIL_CLOCK_SLACK = 1.hours
        const val CURSOR = "SnapSync-Cursor"
        const val DECLARED = "SnapSync-Declared"

        /** The shape of an encrypted event's key id, as the real `POST /events` validates it. */
        val KEY_ID = Regex("^[0-9a-f]{16}$")

        /** The description's `Timestamp`: an instant, whole-second UTC. */
        val WHOLE_SECOND = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

        /** The device API's mount — `/api/v2` — which the routes are written without. */
        val VERSION_PREFIX = Regex("^/api/v[0-9]+")

        val ROUTES = listOf(
            Route(Routes.Challenge.ID, Routes.Challenge.METHOD, Routes.Challenge.TEMPLATE, Access.OPEN),
            Route(Routes.MintToken.ID, Routes.MintToken.METHOD, Routes.MintToken.TEMPLATE, Access.OPEN),
            Route(Routes.RenewToken.ID, Routes.RenewToken.METHOD, Routes.RenewToken.TEMPLATE, Access.OPEN),
            Route(
                Routes.CreateEvent.ID,
                Routes.CreateEvent.METHOD,
                Routes.CreateEvent.TEMPLATE,
                Access.GATED,
                hold = BackendCall.CREATE,
            ),
            Route(
                Routes.GetEvent.ID,
                Routes.GetEvent.METHOD,
                Routes.GetEvent.TEMPLATE,
                Access.OPTIONAL,
                online = true,
                hold = BackendCall.EVENT,
            ),
            Route(
                Routes.ChangeEvent.ID,
                Routes.ChangeEvent.METHOD,
                Routes.ChangeEvent.TEMPLATE,
                Access.GATED,
                online = true,
            ),
            Route(
                Routes.JoinEvent.ID,
                Routes.JoinEvent.METHOD,
                Routes.JoinEvent.TEMPLATE,
                Access.GATED,
                online = true,
                hold = BackendCall.JOIN,
            ),
            Route(
                Routes.LeaveEvent.ID,
                Routes.LeaveEvent.METHOD,
                Routes.LeaveEvent.TEMPLATE,
                Access.GATED,
                online = true,
                hold = BackendCall.LEAVE,
            ),
            Route(
                Routes.PublishManifest.ID,
                Routes.PublishManifest.METHOD,
                Routes.PublishManifest.TEMPLATE,
                Access.GATED,
                online = true,
            ),
            Route(
                Routes.ReportReceived.ID,
                Routes.ReportReceived.METHOD,
                Routes.ReportReceived.TEMPLATE,
                Access.GATED,
                online = true,
            ),
            Route(
                Routes.EventFiles.ID,
                Routes.EventFiles.METHOD,
                Routes.EventFiles.TEMPLATE,
                Access.OPTIONAL,
                online = true,
            ),
            Route(Routes.DeviceFiles.ID, Routes.DeviceFiles.METHOD, Routes.DeviceFiles.TEMPLATE, Access.GATED),
            Route(Routes.PutDevice.ID, Routes.PutDevice.METHOD, Routes.PutDevice.TEMPLATE, Access.GATED),
            Route(Routes.UploadResource.ID, Routes.UploadResource.METHOD, Routes.UploadResource.TEMPLATE, Access.GATED),
            Route(Routes.UploadState.ID, Routes.UploadState.METHOD, Routes.UploadState.TEMPLATE, Access.GATED),
            Route(
                Routes.UploadResourceEventless.ID,
                Routes.UploadResourceEventless.METHOD,
                Routes.UploadResourceEventless.TEMPLATE,
                Access.GATED,
            ),
        )
    }
}

/** A fault an operator injects into the backend's answers ([BackendOperator.fail]). */
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
    ;

    internal fun answer(): HttpResult = when (this) {
        SERVER_ERROR -> HttpResult.Response(502, TEXT, "upstream error")
        MAINTENANCE -> HttpResult.Response(503, TEXT + ("Retry-After" to "60"), "maintenance")
        UNREACHABLE -> HttpResult.Unreachable("injected: the backend is unreachable")
        MALFORMED -> HttpResult.Response(200, JSON, "<html>not the device API</html>")
        ENDED_FOR_GOOD -> HttpResult.Response(410, JSON, CLOSED_BODY)
        CLOSED -> HttpResult.Response(409, JSON, CLOSED_BODY)
    }
}

private val TEXT = mapOf("Content-Type" to "text/plain; charset=UTF-8")
private val JSON = mapOf("Content-Type" to "application/json")

private const val CLOSED_BODY = "{\"error\":\"closed\"}"

private fun text(status: Int, body: String): HttpResult = HttpResult.Response(status, TEXT, body)

private fun empty(status: Int): HttpResult = HttpResult.Response(status, emptyMap(), "")

private fun <T> json(
    status: Int,
    serializer: SerializationStrategy<T>,
    value: T,
    headers: Map<String, String> = emptyMap(),
): HttpResult = HttpResult.Response(status, JSON + headers, WireJson.encodeToString(serializer, value))

/** A role, as the description names it. */
internal fun ResourceRole.onWire(): Role = Role.fromValue(wire) ?: error("no described role for $this")

/** A published manifest, as the backend keeps it — under the device the path names. */
private fun Manifest.inApp(deviceId: String): DeviceManifest? = runCatchingCancellable {
    DeviceManifest(
        deviceId = deviceId,
        assets = assets.map { asset ->
            DeviceManifestAsset(
                AssetId(asset.assetId),
                asset.creationDate,
                asset.resources.map { r ->
                    val role = ResourceRole.entries.first { it.wire == r.role.value }
                    ManifestResource(role, r.contentType, r.key, r.filename)
                },
            )
        },
        version = version,
        final = `final`,
    )
}.getOrNull()

/** A manifest resource's upload key — the name the byte route stores it under. */
internal fun storedKey(file: DeviceFile): String = app.snapsync.model.uploadKey(file.assetId, file.role, file.filename)
