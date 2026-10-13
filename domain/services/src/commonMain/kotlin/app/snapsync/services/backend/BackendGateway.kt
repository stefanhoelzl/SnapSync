package app.snapsync.services.backend

import app.snapsync.ports.BuildInfo
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.services.version.AppVersionGate
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.runtime.WireRequest
import app.snapsync.wire.runtime.WireResponse
import co.touchlab.kermit.Logger

/**
 * The backend's protocol (`docs/architecture.md`, "The backend boundary"), over the plain [HttpTransport]: every
 * request carries the calling build's declared version — the OS transfers too,
 * through [versionHeaders] — and goes to the build's backend; every answer becomes its route's generated reply, and a
 * refused build ([app.snapsync.wire.api.AppTooOld]) or a served one reaches [versionGate].
 *
 * It sends [OpenRequest]s — the `/attest/…` issuers, which mint the very token a gated request needs — and carries no
 * credential. [BackendGateway] is it with one: the app's attestation service reaches the issuers through this, and
 * is the gateway's credential, so the two are separate objects rather than one built in a cycle.
 */
class BackendExchange(
    private val transport: HttpTransport,
    /** The device API's base — the build's backend, one version prefix included — read per request. */
    private val base: () -> String,
    /** The marketing version the build declares, read per request; blank declares none. */
    private val version: () -> String,
    private val versionGate: AppVersionGate?,
) {
    /** Over the process's [build] facts — its backend and its version. */
    constructor(transport: HttpTransport, build: BuildInfo, versionGate: AppVersionGate?) :
        this(transport, { build.uploadHost }, { build.appVersion }, versionGate)

    /** One open request, answered in its own reply type. Never throws (cancellation aside). */
    suspend fun <R : Reply> send(request: OpenRequest<R>): R =
        exchange(request.wire, request.decide, request.unreachable, token = null)

    /**
     * The version every request declares, as headers — for the OS-performed transfers too, which carry it besides their
     * own. None for a build that declares no version (a test's), which the backend answers as too old.
     */
    fun versionHeaders(): Map<String, String> =
        version().takeIf { it.isNotBlank() }?.let { mapOf(Limits.VERSION_HEADER to it) }.orEmpty()

    /** The absolute address of [path] — a route's path — on the build's backend. */
    fun url(path: String): String = base().trimEnd('/') + path

    internal suspend fun <R : Reply> exchange(
        wire: WireRequest,
        decide: (WireResponse) -> R,
        unreachable: (String) -> R,
        token: String?,
    ): R {
        val headers = wire.headers + versionHeaders() + listOfNotNull(token?.let { AUTHORIZATION to "Bearer $it" })
        val reply = when (val result = transport.send(HttpRequest(wire.method, url(wire.path), headers, wire.body))) {
            is HttpResult.Response -> decide(WireResponse(result.status, result.headers, result.body))
            is HttpResult.Unreachable -> unreachable(result.reason)
        }
        versionGate?.observe(reply)
        return reply
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * The backend as every need-shaped service reaches it: [BackendExchange] with the device's [credential] attached to
 * every gated request, and the backend's verdict on it answered — only a rejected credential is invalidated, and
 * only that one:
 *
 * - **The token** is read from [credential] per request, never held: a renewal in the background is picked up by
 *   the next one.
 * - **[Unauthorized]**, to a request that carried a token, means the backend REJECTED that token — not that it
 *   expired, which the expiry check already sees. The rejection names the token the request carried, so the
 *   credential drops it only if it is still the one held, and recovers if this process can.
 * - **Retry once.** When the credential answers a token different from the one rejected, the request is sent once
 *   more with it — safe for every route, because the backend's gate answers before any route runs. A second
 *   rejection is the request's answer.
 *
 * Only a [GatedRequest] can carry the token — the generated routes type each operation by its `security` — so a
 * token never reaches the issuers, whose own `401`s are verdicts on what was sent, not on a credential.
 */
class BackendGateway(
    private val exchange: BackendExchange,
    private val credential: Credential,
    private val log: Logger = Logger.withTag("BackendGateway"),
) {
    /**
     * One gated request, answered in its own reply type. With [obtainFirst] — a request a user is waiting on, a create
     * or a join — a process holding no token asks the credential for one BEFORE sending, since the request would only be
     * refused; a background request sends what it holds, so a phone the service refuses is not re-attested per call.
     */
    suspend fun <R : Reply> send(request: GatedRequest<R>, obtainFirst: Boolean = false): R {
        val sent = credential.token() ?: if (obtainFirst) credential.missing() else null
        val first = exchange.exchange(request.wire, request.decide, request.unreachable, sent)
        if (sent == null || first != Unauthorized) return first
        log.w { "the backend rejected the token ${request.operationId} carried — recovering" }
        val retry = credential.rejected(sent)?.takeIf { it != sent } ?: return first
        log.i { "retrying ${request.operationId} once with the recovered token" }
        return exchange.exchange(request.wire, request.decide, request.unreachable, retry)
    }

    /** The open half, for a service that needs both. */
    val open: BackendExchange get() = exchange
}

/**
 * Where [BackendGateway] gets the token it sends, and what it tells when the backend rejects one.
 *
 * The app's is the attestation service, which drops the rejected token and obtains a new one; the upload
 * extension's cannot attest, so it only drops it (`ExtensionCredential`).
 */
interface Credential {

    /** The token to send now, or `null` when there is none. May be expired: the backend decides. */
    fun token(): String?

    /**
     * The backend rejected [sent] on a gated route. Drop it if it is still the one held, recover if this process
     * can, and answer the token to retry with — or `null` when there is nothing better to send.
     *
     * Never throws: a recovery that fails answers `null`, and the rejection stands as the request's answer.
     */
    suspend fun rejected(sent: String): String?

    /**
     * This process holds no token and a USER is waiting on the request: obtain one now if this process can, and
     * answer it — or `null`. What makes a tap on a phone that never attested try to verify it again, so the front
     * screen tells a refused phone before it tries. The extension, which cannot
     * attest, answers `null`. Never throws.
     */
    suspend fun missing(): String?
}
