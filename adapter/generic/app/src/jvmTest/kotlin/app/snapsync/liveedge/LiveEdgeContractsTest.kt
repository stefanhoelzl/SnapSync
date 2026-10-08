package app.snapsync.liveedge

import app.snapsync.contracts.BackendContract
import app.snapsync.contracts.BackendState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.EdgeSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SERVED_APP_VERSION
import app.snapsync.contracts.Seeded
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.http.HttpBackend
import app.snapsync.ports.Backend
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
 * The `Backend` port contract against the REAL backend (`docs/testing.md`): the production [HttpBackend] over a
 * socket to `api/`, served locally by [LiveEdge]. This is the binding that makes every backend clause covered; the
 * backend mock's in `:adapter:generic:mock` is the `Fake` held to it.
 *
 * JVM only, and the coverage that forgoes is stated here (`docs/testing.md`, "Every test runs on every target its
 * module declares"): a Kotlin/Native test executable under `simctl` cannot launch the backend as a process. Nothing
 * is lost by it: [HttpBackend] is `commonMain` code, identical on every target, and its Kotlin/Native compilation is
 * covered by this module's `commonTest`. What differs on a device is the Ktor ENGINE (Darwin), which no binding here
 * — or anywhere yet — puts in front of a real backend.
 *
 * Every state is reachable here: the dev edge enrols the device a token-less call names (`api/src/dev/fallback.ts`),
 * because on a host without App Attest nothing else ever could, and it verifies every token it is shown.
 */
@OptIn(ExperimentalUuidApi::class)
class LiveEdgeContractsTest {

    private val backend = object : Binding<BackendState, EdgeSubject<Backend>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            BackendState.SERVING,
            BackendState.EVENT_EXISTS,
            BackendState.EVENT_FULL,
            BackendState.NO_SUCH_EVENT,
            BackendState.MEMBER,
            BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
            BackendState.UNION_COMPLETE_ASSET,
            BackendState.UNION_INCOMPLETE_ASSET,
            BackendState.DEVICE_UPLOADED,
            BackendState.VERSION_REFUSED,
            BackendState.FOREIGN_TOKEN,
            BackendState.ENDED_MEMBER,
            BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
            BackendState.NO_BACKEND,
            BackendState.ATTESTABLE,
        )

        override fun create(state: BackendState, clauseId: String, log: CallLog): Entered<EdgeSubject<Backend>> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "the real backend answers what it sends; a fixture is apart",
                )
            }
            val entered = LiveEdge.enter<Backend>(
                { BackendContract.seed(state, clauseId, it) },
            ) { client, base, seeded ->
                val address = if (state == BackendState.NO_BACKEND) closedLoopbackAddress() else base
                HttpBackend(client, address, seeded.identity.appVersion)
            }
            if (state != BackendState.ATTESTABLE || entered !is Entered.Ready) return entered
            // The local rig trusts any attestation: a software chain, as an emulator presents, is what it accepts.
            val subject = entered.subject
            return Entered.Ready(
                EdgeSubject(subject.port, subject.seeded, subject.setup, SoftwareKeyAttestation()),
                entered.dispose,
            )
        }
    }

    /**
     * HttpBackend against a wire fixture (`docs/testing.md`, "Hosts"): a loopback server that answers every route with
     * success and bytes no backend version this build speaks would send. It stands in for a backend of another
     * version, and counts as live for [BackendState.UNREADABLE_SUCCESS] alone, because the clause judges only the
     * client's decoding — the real engine and socket, reading bytes the clause chose.
     */
    private val wireFixture = object : Binding<BackendState, EdgeSubject<Backend>> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(BackendState.UNREADABLE_SUCCESS)

        override fun create(state: BackendState, clauseId: String): Entered<EdgeSubject<Backend>> {
            if (state !in reaches) return Entered.Unreachable("a wire fixture is no backend: it answers one thing")
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    val body = "<html>a page from somewhere else</html>".encodeToByteArray()
                    exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
            val client = HttpClient(CIO)
            val port = HttpBackend(client, "http://127.0.0.1:${server.address.port}", SERVED_APP_VERSION)
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
    fun `HttpBackend reads no value out of bytes it cannot decode`() = verify(BackendContract, wireFixture)

    /** A loopback port nothing listens on: bound to learn a free one, then closed, so a connect is refused. */
    private fun closedLoopbackAddress(): String =
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { "http://127.0.0.1:${it.localPort}" }

    private companion object {
        const val HTTP_OK = 200
    }
}
