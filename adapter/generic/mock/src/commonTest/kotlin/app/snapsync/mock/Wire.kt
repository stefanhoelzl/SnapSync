package app.snapsync.mock

import app.snapsync.ports.HttpTransport
import app.snapsync.services.backend.BackendExchange
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.Credential
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Reply

/**
 * The device API over [transport] as a test drives it — the production exchange at the mock's base, declaring
 * [version] and carrying [token] (none, by default, which the mock's fallback bearer serves).
 */
internal class Wire(transport: HttpTransport, version: String = "99.0", private val token: String? = null) {
    private val exchange = BackendExchange(transport, { BASE }, { version }, versionGate = null)
    private val gateway = BackendGateway(
        exchange,
        object : Credential {
            override fun token(): String? = token
            override suspend fun rejected(sent: String): String? = null
            override suspend fun missing(): String? = null
        },
    )

    suspend fun <R : Reply> send(request: GatedRequest<R>): R = gateway.send(request)

    suspend fun <R : Reply> send(request: OpenRequest<R>): R = exchange.send(request)

    companion object {
        const val BASE = "https://in-memory.backend/api/v2"
    }
}
