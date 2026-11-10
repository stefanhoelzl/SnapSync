package app.snapsync.feature.support

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BackendTarget
import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.DeliveryContext
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.trust.TokenText
import app.snapsync.domain.trust.BearerToken
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport

/**
 * **The real backend gateway over a scripted transport** — every request answered by [answer] and recorded in
 * [requests], carrying a held token. For a feature whose data read asks the backend one thing (the join's listing).
 */
class ScriptedBackend(var answer: suspend (HttpRequest) -> HttpResult) {
    val requests = mutableListOf<HttpRequest>()

    private val transport = object : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResult {
            requests += request
            return answer(request)
        }
    }

    private val target = object : BackendTarget {
        override val base = "https://backend.test/api/v2"
        override val declaredVersion = "1.4"
    }

    private val credential = object : Credential {
        private val held = TokenRead.Held(TokenText.parse("T1"))

        override suspend fun missing(): TokenRead = held

        override fun token(): TokenRead = held

        override suspend fun rejected(sent: BearerToken): TokenRead = TokenRead.None
    }

    val gateway: BackendGateway =
        BackendGateway(BackendExchange(transport, target, VersionIgnored, DeliveryContext()), credential)

    companion object {
        /** A JSON answer. */
        fun json(status: Int, body: String): HttpResult =
            HttpResult.Response(status, mapOf("Content-Type" to "application/json"), body)
    }
}
