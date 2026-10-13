package app.snapsync.services.backend

import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.SecureStore
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf
import app.snapsync.services.version.AppVersionGate

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

/** The exchange over [transport], declaring [VERSION] at [BASE], reporting to [gate]. */
internal fun exchangeOver(transport: HttpTransport, gate: AppVersionGate? = null): BackendExchange =
    BackendExchange(transport, { BASE }, { VERSION }, gate)

/** A [Credential] holding [current], which a rejection replaces with [recovered] (or keeps, for `null`). */
internal class ScriptedCredential(
    var current: String?,
    private val recovered: String? = null,
    /** What [missing] obtains — `null`, the extension's answer, by default. */
    private val obtained: String? = null,
) : Credential {
    val rejections = mutableListOf<String>()
    var reads = 0
    var obtains = 0

    override suspend fun missing(): String? {
        obtains++
        if (obtained != null) current = obtained
        return obtained
    }

    override fun token(): String? = current.also { reads++ }

    override suspend fun rejected(sent: String): String? {
        rejections += sent
        if (recovered != null) current = recovered
        return recovered
    }
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
