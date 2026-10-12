package app.snapsync.liveedge

import app.snapsync.contracts.BackendClient
import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClientIdentity
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Seeded
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.http.KtorHttpTransport
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The `Backend` contract against the REAL backend (`docs/testing.md`): the production gateway ([BackendClient]) over
 * [KtorHttpTransport] and a socket to `api/`, served locally by [LiveEdge]. This is the binding that makes every
 * backend clause covered; the backend mock's in `:adapter:generic:mock` is the `Fake` held to it.
 *
 * JVM only, and the coverage that forgoes is stated here (`docs/testing.md`, "Every test runs on every target its
 * module declares"): a Kotlin/Native test executable under `simctl` cannot launch the backend as a process. Nothing
 * is lost by it: the gateway and the generated wire are `commonMain` code, identical on every target. What differs on
 * a device is the Ktor ENGINE, which the `HttpTransport` contract holds on each platform.
 *
 * Every state is reachable here: the dev edge enrols the device a token-less call names (`api/src/dev/fallback.ts`),
 * because on a host without App Attest nothing else ever could, and it verifies every token it is shown.
 */
@OptIn(ExperimentalUuidApi::class)
class LiveEdgeContractsTest {

    private val backend = object : Binding<BackendState, EdgeSubject<BackendClient>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            BackendState.SERVING,
            BackendState.EVENT_EXISTS,
            BackendState.NO_SUCH_EVENT,
            BackendState.MEMBER,
            BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
            BackendState.UNION_COMPLETE_ASSET,
            BackendState.UNION_INCOMPLETE_ASSET,
            BackendState.DEVICE_UPLOADED,
            BackendState.DEVICE_UPLOAD_BROKE_OFF,
            BackendState.VERSION_REFUSED,
            BackendState.FOREIGN_TOKEN,
            BackendState.ENDED_MEMBER,
            BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
            BackendState.NO_BACKEND,
            BackendState.ATTESTABLE,
        )

        override fun create(state: BackendState, clauseId: String, log: CallLog): Entered<EdgeSubject<BackendClient>> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "the real backend answers what it sends; a fixture is apart",
                )
            }
            val address = if (state == BackendState.NO_BACKEND) closedLoopbackAddress() else null
            val entered = LiveEdge.enter(
                { BackendContract.seed(state, clauseId, it) },
            ) { transport, base, identity ->
                BackendClient(transport.recorded(log), address ?: base, identity, log)
            }
            if (state != BackendState.ATTESTABLE || entered !is Entered.Ready) return entered
            // The local rig trusts any attestation: a software chain, as an emulator presents, is what it accepts.
            val subject = entered.subject
            return Entered.Ready(
                EdgeSubject(subject.port, subject.seeded, subject.setup, SoftwareKeyAttestation(), subject::declaring),
                entered.dispose,
            )
        }
    }

    /**
     * The gateway against a wire fixture (`docs/testing.md`, "Hosts"): a loopback server that answers every route one
     * way — success in bytes no backend version this build speaks would send, a failing store, a migration window, or
     * the refusals only an event that ended for good still gives.
     * It stands in for a backend in that condition, and counts as live for those states alone, because each clause
     * judges only the client's reading — the real engine and socket, reading bytes the clause chose.
     */
    private val wireFixture = object : Binding<BackendState, EdgeSubject<BackendClient>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            BackendState.UNREADABLE_SUCCESS,
            BackendState.SERVER_FAILING,
            BackendState.IN_MAINTENANCE,
            BackendState.ENDED_FOR_GOOD,
            BackendState.CLOSED_BEFORE_OPEN_UNTIL,
        )

        override fun create(state: BackendState, clauseId: String, log: CallLog): Entered<EdgeSubject<BackendClient>> {
            if (state !in reaches) return Entered.Unreachable("a wire fixture is no backend: it answers one thing")
            val (status, headers, text) = when (state) {
                BackendState.SERVER_FAILING -> Triple(HTTP_BAD_GATEWAY, mapOf(CONTENT_TYPE to TEXT), "upstream error")
                BackendState.IN_MAINTENANCE ->
                    Triple(HTTP_UNAVAILABLE, mapOf(CONTENT_TYPE to TEXT, "Retry-After" to "60"), "maintenance")
                BackendState.ENDED_FOR_GOOD -> Triple(HTTP_GONE, mapOf(CONTENT_TYPE to JSON), CLOSED_BODY)
                BackendState.CLOSED_BEFORE_OPEN_UNTIL -> Triple(HTTP_CONFLICT, mapOf(CONTENT_TYPE to JSON), CLOSED_BODY)
                else -> Triple(HTTP_OK, emptyMap(), "<html>a page from somewhere else</html>")
            }
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    val body = text.encodeToByteArray()
                    headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
            val client = HttpClient(CIO)
            val base = "http://127.0.0.1:${server.address.port}"
            val port = BackendClient(KtorHttpTransport(client).recorded(log), base, ClientIdentity.SERVED, log)
            val seeded = Seeded(eventId = Uuid.random().toString(), deviceId = Uuid.random().toString())
            return Entered.Ready(EdgeSubject(port, seeded)) {
                client.close()
                server.stop(0)
            }
        }
    }

    @Test
    fun `the real edge satisfies the Backend contract`() = verify(BackendContract, backend)

    @Test
    fun `the gateway reads what a fixture answers - and no value out of bytes it cannot decode`() =
        verify(BackendContract, wireFixture)

    /** A loopback port nothing listens on: bound to learn a free one, then closed, so a connect is refused. */
    private fun closedLoopbackAddress(): String =
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { "http://127.0.0.1:${it.localPort}" }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_GATEWAY = 502
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_GONE = 410
        const val HTTP_CONFLICT = 409
        const val JSON = "application/json"
        const val CLOSED_BODY = "{\"error\":\"closed\"}"
        const val CONTENT_TYPE = "Content-Type"
        const val TEXT = "text/plain; charset=UTF-8"
    }
}
