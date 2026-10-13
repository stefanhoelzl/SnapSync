package app.snapsync.http

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.HttpTransportContract
import app.snapsync.contracts.HttpTransportState
import app.snapsync.contracts.TransportUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test

/**
 * [KtorHttpTransport] against [HttpTransportContract] on the JVM, over both engines it runs on here: CIO, the JVM's,
 * and OkHttp — Android's, a JVM library, so its failure handling is held in `./gradlew build` and not only on the
 * emulator. Both exchange with the loopback transfer fixture the test task starts (`snapsync.transfer-fixture`).
 */
class HttpTransportContractTest {

    private val fixture = requireNotNull(System.getProperty("snapsync.transferFixture")) {
        "no transfer fixture: the jvmTest task starts one (`snapsync.transfer-fixture`)"
    }

    private val cio = object : Binding<HttpTransportState, TransportUnderTest> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val adapter = KtorHttpTransport::class
        override val reaches = setOf(HttpTransportState.PEER, HttpTransportState.NO_PEER)

        override fun create(state: HttpTransportState, clauseId: String, log: CallLog) =
            entered(HttpClient(CIO), state, log)
    }

    private val okHttp = object : Binding<HttpTransportState, TransportUnderTest> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val adapter = KtorHttpTransport::class
        override val reaches = setOf(HttpTransportState.PEER, HttpTransportState.NO_PEER)

        override fun create(state: HttpTransportState, clauseId: String, log: CallLog) =
            entered(HttpClient(OkHttp), state, log)
    }

    private fun entered(client: HttpClient, state: HttpTransportState, log: CallLog): Entered<TransportUnderTest> {
        val base = when (state) {
            HttpTransportState.PEER -> fixture
            HttpTransportState.NO_PEER -> closedLoopbackAddress()
        }
        return Entered.Ready(TransportUnderTest(KtorHttpTransport(client).recorded(log), base)) { client.close() }
    }

    /** A loopback port nothing listens on: bound for a moment to learn a free one, then released. */
    private fun closedLoopbackAddress(): String {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun `CIO honours the HttpTransport contract`() = verify(HttpTransportContract, cio)

    @Test
    fun `OkHttp honours the HttpTransport contract`() = verify(HttpTransportContract, okHttp)
}
