package app.snapsync.android.backend

import androidx.test.platform.app.InstrumentationRegistry
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
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test

/**
 * The app's own HTTP client ([androidHttpClient]: OkHttp, its timeout) under [KtorHttpTransport], against
 * [HttpTransportContract] on the emulator — Android's network stack, which the JVM binding of the same engine cannot
 * show. It exchanges with the transfer fixture `./gradlew androidPlatformTest` serves on the host (`10.0.2.2`).
 */
class AndroidHttpTransportContractTest {

    private val transport = object : Binding<HttpTransportState, TransportUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = KtorHttpTransport::class
        override val reaches = setOf(HttpTransportState.PEER, HttpTransportState.NO_PEER)

        override fun create(state: HttpTransportState, clauseId: String, log: CallLog): Entered<TransportUnderTest> {
            val base = when (state) {
                HttpTransportState.PEER -> fixture()
                HttpTransportState.NO_PEER -> closedLoopbackAddress()
            }
            val client = androidHttpClient()
            return Entered.Ready(TransportUnderTest(KtorHttpTransport(client).recorded(log), base)) { client.close() }
        }
    }

    private fun fixture(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("fixture")) {
        "no transfer fixture: run ./gradlew androidPlatformTest, which serves one and passes its address"
    }.trimEnd('/')

    /** A port on the emulator's own loopback that nothing listens on: bound for a moment, then released. */
    private fun closedLoopbackAddress(): String {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun the_app_s_http_client_honours_the_http_transport_contract() = verify(HttpTransportContract, transport)
}
