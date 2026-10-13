package app.snapsync.membership

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
import app.snapsync.http.KtorHttpTransport
import kotlin.test.Test

/**
 * The app's own iOS HTTP client ([darwinHttpClient]: Ktor's Darwin engine over `NSURLSession`) under
 * [KtorHttpTransport], against [HttpTransportContract] in the simulator's test executable — the engine every iOS
 * backend call runs on, which no other host can run. It exchanges with the transfer fixture the test task starts on
 * the host (`snapsync.transfer-fixture`): the simulator shares the host's loopback, which App Transport Security exempts.
 */
class DarwinHttpTransportContractTest {

    private val transport = object : Binding<HttpTransportState, TransportUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = KtorHttpTransport::class
        override val reaches = setOf(HttpTransportState.PEER, HttpTransportState.NO_PEER)

        override fun create(state: HttpTransportState, clauseId: String, log: CallLog): Entered<TransportUnderTest> {
            val base = when (state) {
                HttpTransportState.PEER -> FIXTURE
                HttpTransportState.NO_PEER -> NOTHING_LISTENING
            }
            val client = darwinHttpClient()
            return Entered.Ready(TransportUnderTest(KtorHttpTransport(client).recorded(log), base)) { client.close() }
        }
    }

    @Test
    fun the_app_s_http_client_honours_the_http_transport_contract() = verify(HttpTransportContract, transport)

    private companion object {
        /** Where the host's fixture answers (`TransferFixtureService.LOOPBACK_ADDRESS` in build-logic). */
        const val FIXTURE = "http://127.0.0.1:8123"

        /** Port 9 (discard), which nothing on a CI runner or a Mac serves: a refused connection. */
        const val NOTHING_LISTENING = "http://127.0.0.1:9"
    }
}
