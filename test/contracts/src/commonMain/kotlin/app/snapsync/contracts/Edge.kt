package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.ports.HttpTransport
import app.snapsync.services.backend.BackendExchange
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.Credential
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.SHARED_OUTCOMES
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.ManifestAsset
import app.snapsync.wire.models.ManifestResource
import app.snapsync.wire.models.Role
import app.snapsync.wire.runtime.WireResponse
import kotlin.reflect.KClass
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ---- The Backend contract's shared vocabulary (`docs/testing.md`) ------------------------------------
//
// The contract is about the backend, seen through the gateway the app ships (`docs/architecture.md`, "The backend
// boundary"): the real [BackendGateway] — routes, replies, the version header, the token — over a transport to the real
// `api/` (`Live`) or to the backend mock's HTTP face (`Fake`). A binding enters each clause's state through
// [EdgeSetup], the backend's PUBLIC surface, never by writing a stand-in's store directly. A setup step one backend
// cannot follow is a state that backend does not reach, and it says so with `Unreachable`.

/** A version every edge serves: the setup declares it, and so does a client under contract by default. */
const val SERVED_APP_VERSION = "99.0"

/** A version below any minimum an edge has ever set, so declaring it is a build the edge refuses. */
const val REFUSED_APP_VERSION = "0.0"

/** A bearer no edge issued. An edge that verifies tokens answers it `401`. */
const val FOREIGN_TOKEN = "foreign.0.not-a-signature-this-edge-issued"

/** The window every seeded event declares — fixed, so a clause can state the dates the edge must echo. */
const val SEEDED_STARTS_AT = "2030-01-01T00:00:00Z"
const val SEEDED_ENDS_AT = "2030-01-08T00:00:00Z"

/** The range of an event whose range has already ENDED — a back-dated event. */
const val SEEDED_ENDED_STARTS_AT = "2026-01-01T00:00:00Z"
const val SEEDED_ENDED_ENDS_AT = "2026-01-05T00:00:00Z"

/**
 * What the app declares about itself in a clause's state: the build version (the version header every request
 * carries) and the token the gateway attaches to the gated routes — `null` for none, which the dev edge's fallback
 * bearer serves (`api/src/dev/serve.ts`).
 */
class ClientIdentity(val appVersion: String, val token: String?) {
    companion object {
        val SERVED = ClientIdentity(SERVED_APP_VERSION, token = null)
    }
}

/** An event [EdgeSetup] created, as the edge answered the create. */
class CreatedEvent(val eventId: String, val name: String, val createdAt: String)

/**
 * One clause's entered state: the addresses the clause needs (minted by the edge, so they cannot be derived
 * from the clause id), and the identity the client under contract must declare in this state.
 */
class Seeded(
    val eventId: String,
    val deviceId: String,
    val event: CreatedEvent? = null,
    val identity: ClientIdentity = ClientIdentity.SERVED,
    val asset: SeededAsset? = null,
)

/** A backend clause's subject: the client, the addresses and identity its state was entered with, and the setup. */
class EdgeSubject<P>(
    val port: P,
    val seeded: Seeded,
    /**
     * The backend's public surface, for a clause whose promise is observable only on another route — a publish's
     * effect on the union. Null for a binding with no backend behind it.
     */
    private val edge: BackendSetup? = null,
    /** A key the binding attests with; required of a binding that reaches [BackendState.ATTESTABLE]. */
    val attester: SoftwareAttester? = null,
    /** The client under contract as another [ClientIdentity] declares itself — a token a clause obtained, say. */
    private val reidentify: ((ClientIdentity) -> P)? = null,
) {
    /** The client under contract, declaring [identity] instead; a binding that cannot build one fails naming that. */
    fun declaring(
        identity: ClientIdentity,
    ): P = requireNotNull(reidentify) { "this binding builds no other client" }(identity)

    /** The backend's public surface; a clause asking for it on a binding with none fails naming that. */
    val setup: BackendSetup get() = requireNotNull(edge) { "this binding has no backend to read through" }
}

/**
 * The backend as the app reaches it — the production [BackendGateway] over [transport] at [base], declaring
 * [identity] — sending what a clause asks and recording each answer on the clause's [log] as the grid writes it
 * ([backendCell]). The credential is the identity's token, never renewed: a rejected one is the request's answer.
 */
class BackendClient(transport: HttpTransport, base: String, identity: ClientIdentity, private val log: CallLog) {
    private val exchange = BackendExchange(transport, { base }, { identity.appVersion }, versionGate = null)
    private val gateway = BackendGateway(exchange, HeldToken(identity.token))

    /** One gated request, answered in its route's reply. */
    suspend fun <R : Reply> send(request: GatedRequest<R>): R =
        gateway.send(request).also { log.record(backendCell(request.operationId, it::class)) }

    /** One open request — the `/attest/…` issuers — answered in its route's reply. */
    suspend fun <R : Reply> send(request: OpenRequest<R>): R =
        exchange.send(request).also { log.record(backendCell(request.operationId, it::class)) }

    private class HeldToken(private val token: String?) : Credential {
        override fun token(): String? = token
        override suspend fun rejected(sent: String): String? = null
        override suspend fun missing(): String? = null
    }
}

/**
 * The grid cell an [outcome] of [operationId] is (`docs/testing.md`, "The port grid"): `Backend.<operationId> →
 * <Outcome>` for the operation's own, `Backend.* → <Outcome>` for one several operations answer with ONE type.
 */
fun backendCell(operationId: String, outcome: KClass<out Reply>): String {
    val name = simpleNameOf(outcome)
    return if (name in SHARED_OUTCOMES) "Backend.* → $name" else "Backend.$operationId → $name"
}

/**
 * A key the binding attests with as an Android device's KeyMint does in software — what the local rig's `any` policy
 * accepts, and what proves the route and its verifier, never hardware trust.
 */
interface SoftwareAttester {
    /** The key's attestation chain over [challenge], leaf first, each certificate DER — what an Android device mints with. */
    fun attest(challenge: String): List<ByteArray>

    /** The key's ECDSA P-256 signature over [challenge]'s UTF-8 bytes, DER — what a renewal sends. */
    fun sign(challenge: String): ByteArray
}

/** How a binding enters backend states: through the backend's public surface, whatever that backend is. */
interface BackendSetup {
    fun freshId(): String

    /** Create an event — one whose range has already [ended] when asked. */
    suspend fun createEvent(name: String, ended: Boolean = false): CreatedEvent
    suspend fun join(eventId: String, deviceId: String)

    /** Publish [deviceId]'s manifest — declaring its share settled when [final]. */
    suspend fun publish(eventId: String, deviceId: String, assets: List<SeededAsset>, final: Boolean = false)

    /** The asset ids the event's union serves — a read of the public surface, as a member makes it. */
    suspend fun unionAssetIds(eventId: String): Set<AssetId>

    /** One resource's bytes landing in [eventId], as the app's uploader addresses them. */
    suspend fun upload(eventId: String, deviceId: String, asset: SeededAsset, role: ResourceRole)
}

/**
 * [BackendSetup] over the backend's public HTTP surface — the real `api/`'s or the backend mock's, the one [transport]
 * reaches at [base]. Setup is not under contract: it sends no token (the dev edge's fallback bearer serves it, as the
 * mock's does), and every step checks its own answer so a setup failure names the step, not the clause.
 */
class EdgeSetup(private val transport: HttpTransport, base: String) : BackendSetup {
    private val base = base.trimEnd('/')

    @OptIn(ExperimentalUuidApi::class)
    override fun freshId(): String = Uuid.random().toString()

    override suspend fun createEvent(name: String, ended: Boolean): CreatedEvent {
        val body = EventCreate(
            name = name,
            startsAt = if (ended) SEEDED_ENDED_STARTS_AT else SEEDED_STARTS_AT,
            endsAt = if (ended) SEEDED_ENDED_ENDS_AT else SEEDED_ENDS_AT,
        )
        val reply = send(Routes.CreateEvent.request(body))
        val created = (reply as? CreateEventReply.Created ?: refused("create event", reply)).event
        return CreatedEvent(created.eventId, created.name, created.createdAt)
    }

    override suspend fun join(eventId: String, deviceId: String) {
        val reply = send(Routes.JoinEvent.request(eventId, deviceId))
        if (reply != JoinEventReply.Joined) refused("join $deviceId", reply)
    }

    override suspend fun publish(eventId: String, deviceId: String, assets: List<SeededAsset>, final: Boolean) {
        val manifest = manifestOf(deviceId, assets, version = 0, final = final)
        val reply = send(Routes.PublishManifest.request(eventId, deviceId, manifest))
        if (reply != PublishManifestReply.Published) refused("publish manifest", reply)
    }

    /** The asset ids the event's union serves — a read of the backend's public surface, as a member makes it. */
    override suspend fun unionAssetIds(eventId: String): Set<AssetId> {
        val reply = send(Routes.EventFiles.request(eventId))
        val page = reply as? EventFilesReply.Page ?: refused("read the union", reply)
        return page.items.mapTo(mutableSetOf()) { AssetId(it.assetId) }
    }

    /** Uploads one resource's bytes, as the app's uploader addresses them. */
    override suspend fun upload(eventId: String, deviceId: String, asset: SeededAsset, role: ResourceRole) {
        val path = Routes.UploadResource.path(eventId, deviceId, asset.assetId.value, role.wire, asset.filename)
        val headers = mapOf(Limits.VERSION_HEADER to SERVED_APP_VERSION, "Content-Type" to "image/jpeg")
        val result = transport.send(HttpRequest(Routes.UploadResource.METHOD, base + path, headers, JPEG))
        check(result is HttpResult.Response && result.status in SUCCESS) {
            "setup step 'upload ${asset.assetId}/${role.wire}' was refused by the backend: ${result.describe()}"
        }
    }

    private suspend fun <R : Reply> send(request: GatedRequest<R>): R {
        val wire = request.wire
        val headers = wire.headers + (Limits.VERSION_HEADER to SERVED_APP_VERSION)
        return when (val result = transport.send(HttpRequest(wire.method, base + wire.path, headers, wire.body))) {
            is HttpResult.Response -> request.decide(WireResponse(result.status, result.headers, result.body))
            is HttpResult.Unreachable -> request.unreachable(result.reason)
        }
    }

    private fun refused(step: String, reply: Reply): Nothing =
        error("setup step '$step' was refused by the backend: $reply")

    private fun HttpResult.describe(): String = when (this) {
        is HttpResult.Response -> "HTTP $status $body"
        is HttpResult.Unreachable -> "no answer ($reason)"
    }

    private companion object {
        val SUCCESS = 200..299

        /** A body for a route that stores whatever it is sent: setup never reads the bytes back. */
        const val JPEG = "seeded bytes"
    }
}

/** An asset a binding seeds: one capture declaring [roles], each uploaded under [filename]. */
class SeededAsset(val assetId: AssetId, val roles: List<ResourceRole>, val filename: String = "IMG_0001.JPG") {
    val creationDate = "2030-01-02T12:00:00Z"

    fun key(role: ResourceRole) = uploadKey(assetId, role, filename)

    /** This asset as a manifest declares it. */
    fun onWire() = ManifestAsset(
        assetId = assetId.value,
        creationDate = creationDate,
        resources = roles.map { ManifestResource(it.onWire(), "image/jpeg", key(it), filename) },
    )
}

/** A manifest declaring [assets] for [deviceId], projected under [version], settled when [final]. */
fun manifestOf(deviceId: String, assets: List<SeededAsset>, version: Long = 0, final: Boolean = false) =
    Manifest(deviceId = deviceId, assets = assets.map { it.onWire() }, version = version, `final` = final)

/** A role, as the description names it. */
fun ResourceRole.onWire(): Role = Role.fromValue(wire) ?: error("no described role for $this")
