package app.snapsync.mock

import app.snapsync.contracts.attest.DeviceTokens
import app.snapsync.contracts.attest.InMemoryKeyAttestation
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.HttpTransport
import app.snapsync.services.backend.BackendExchange
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.Credential
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Reply

/**
 * The device API over [transport] as a test drives it — the production exchange at the mock's base, declaring
 * [version]. Each gated request carries the token of the device its path names (an event is created by a device of
 * the test's own), minted as a device mints one; a device the mock refuses to attest sends none.
 */
internal class Wire(transport: HttpTransport, version: String = "99.0") {
    private val exchange = BackendExchange(transport, { BASE }, { version }, versionGate = null)
    private val tokens = DeviceTokens(transport, BASE, { InMemoryKeyAttestation() }, { version })

    suspend fun <R : Reply> send(request: GatedRequest<R>): R {
        val device = DEVICE_IN_PATH.find(request.wire.path)?.groupValues?.get(1) ?: CREATOR
        val token = runCatchingCancellable { tokens.tokenFor(device) }.getOrNull()
        return BackendGateway(exchange, Held(token)).send(request)
    }

    suspend fun <R : Reply> send(request: OpenRequest<R>): R = exchange.send(request)

    private class Held(private val token: String?) : Credential {
        override fun token(): String? = token
        override suspend fun rejected(sent: String): String? = null
        override suspend fun missing(): String? = null
    }

    companion object {
        const val BASE = "https://in-memory.backend/api/v2"
        private const val CREATOR = "c0ffee00-0000-4000-8000-000000000001"
        private val DEVICE_IN_PATH = Regex("/devices/([^/?]+)")
    }
}
