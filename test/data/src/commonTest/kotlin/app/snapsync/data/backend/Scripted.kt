package app.snapsync.data.backend

import app.snapsync.data.trust.TokenText
import app.snapsync.domain.trust.BearerToken
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport

/** The base every scripted exchange addresses — the device API's, one version prefix included. */
internal const val BASE = "https://backend.test/api/v2"

/** The version every scripted exchange declares. */
internal const val VERSION = "1.4"

/**
 * An [HttpTransport] whose every request is answered by [answer], recording each as `"<METHOD> <path> <token>"` — the
 * path relative to [BASE], the token the bearer it carried (`null` for none).
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

internal fun ok(status: Int = 200): HttpResult = HttpResult.Response(status, emptyMap(), "")

internal fun text(status: Int, body: String): HttpResult =
    HttpResult.Response(status, mapOf("Content-Type" to "text/plain; charset=UTF-8"), body)

internal fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()): HttpResult =
    HttpResult.Response(status, mapOf("Content-Type" to "application/json") + headers, body)

/** The backend's verdict on a token: `401 unattested`. */
internal val UNAUTHORIZED: HttpResult = text(401, "unattested")

/** The backend refusing this build, naming [minimum]. */
internal fun appTooOld(minimum: String): HttpResult = json(
    426,
    """{"error":"app too old","minAppVersion":"$minimum"}""",
)

internal class Target(override val base: String = BASE, override val declaredVersion: String = VERSION) : BackendTarget

/** The exchange over [transport], declaring [VERSION] at [BASE], reporting to [versions]. */
internal fun exchangeOver(transport: HttpTransport, versions: VersionObserver = VersionIgnored): BackendExchange =
    BackendExchange(transport, Target(), versions)

/** A [Credential] holding [current], which a rejection replaces with [recovered] (or keeps, for `null`). */
internal class ScriptedCredential(
    var current: String?,
    private val recovered: String? = null,
    /** What [missing] obtains — `null`, the extension's answer, by default. */
    private val obtained: String? = null,
    /** Whether the store of record can be read at all. */
    private val readable: Boolean = true,
) : Credential {
    val rejections = mutableListOf<String>()
    var obtains = 0

    override suspend fun missing(): TokenRead {
        obtains++
        if (obtained != null) current = obtained
        return read(obtained)
    }

    override fun token(): TokenRead = if (readable) read(current) else TokenRead.Unavailable("keychain locked")

    override suspend fun rejected(sent: BearerToken): TokenRead {
        rejections += sent.secret
        if (recovered != null) current = recovered
        return read(recovered)
    }

    private fun read(text: String?): TokenRead = text?.let { TokenRead.Held(TokenText.parse(it)) } ?: TokenRead.None
}
