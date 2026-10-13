package app.snapsync.services.backend

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BackendTarget
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.trust.TokenText
import app.snapsync.domain.trust.BearerToken
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.SecureStore
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf

/** The base every scripted exchange addresses — the device API's, one version prefix included. */
internal const val BASE = "https://backend.test/api/v2"

/** The version every scripted exchange declares. */
internal const val VERSION = "1.4"

/**
 * An [HttpTransport] whose every request is answered by [answer], recording each as `"<METHOD> <path> <token>"` — the
 * path relative to [BASE], the token the bearer it carried (`null` for none): enough to assert which token a request
 * carried and how many times it was sent. Hand-written here because a `:domain:*` build file names no module (the
 * backend mock lives beside the fakes).
 */
internal class ScriptedTransport(var answer: (HttpRequest) -> HttpResult = { ok() }) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    val calls: List<String>
        get() = requests.map { r ->
            val token = r.headers["Authorization"]?.removePrefix("Bearer ")
            "${r.method} ${r.url.removePrefix(BASE)} $token"
        }

    override suspend fun send(request: HttpRequest): HttpResult {
        requests += request
        return answer(request)
    }
}

/** An answer of [status] with no body. */
internal fun ok(status: Int = 200): HttpResult = HttpResult.Response(status, emptyMap(), "")

/** A text answer. */
internal fun text(status: Int, body: String): HttpResult =
    HttpResult.Response(status, mapOf("Content-Type" to "text/plain; charset=UTF-8"), body)

/** A JSON answer. */
internal fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()): HttpResult =
    HttpResult.Response(status, mapOf("Content-Type" to "application/json") + headers, body)

/** The backend's verdict on a token: `401 unattested`. */
internal val UNAUTHORIZED: HttpResult = text(401, "unattested")

/** The backend refusing this build, naming [minimum]. */
internal fun appTooOld(minimum: String): HttpResult = json(
    426,
    """{"error":"app too old","minAppVersion":"$minimum"}""",
)

/** The exchange over [transport], declaring [VERSION] at [BASE]. */
internal fun exchangeOver(transport: HttpTransport): BackendExchange =
    BackendExchange(transport, ScriptedTarget, VersionIgnored)

private object ScriptedTarget : BackendTarget {
    override val base = BASE
    override val declaredVersion = VERSION
}

/** A [Credential] holding [current] — or none — and never recovering. */
internal class ScriptedCredential(private val current: String?) : Credential {
    override fun token(): TokenRead = current?.let { TokenRead.Held(TokenText.parse(it)) } ?: TokenRead.None

    override suspend fun rejected(sent: BearerToken): TokenRead = TokenRead.None

    override suspend fun missing(): TokenRead = TokenRead.None
}

/** Services over a gateway whose transport answers every request with [reply], for the need-shaped mappings. */
internal fun servicesAnswering(reply: HttpResult, store: SecureStore = MapSecureStore()): BackendServices =
    servicesOver(ScriptedTransport { reply }, store)

/** Services over a gateway on [transport], holding no token. */
internal fun servicesOver(transport: HttpTransport, store: SecureStore = MapSecureStore()): BackendServices =
    BackendServices(
        BackendGateway(exchangeOver(transport), ScriptedCredential(null)),
        identityOf("D", store),
        zone = { null },
    )
