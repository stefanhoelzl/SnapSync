package app.snapsync.control

import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.HostLevers
import app.snapsync.levers.LeverService
import app.snapsync.levers.RigState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The typed client of the control protocol, for either host — the JVM host or the app on a device or simulator —
 * at [baseUrl] (for example `http://127.0.0.1:18099`).
 *
 * It is written against the wire types the rig itself encodes ([RigState] embeds the real `UiState`), so there is
 * no second rendering of the state here that could disagree with the screen.
 */
class RigClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient(CIO) {
        // No request timeout by default: a host's read may take as long as the device takes to answer it, and a
        // transport timeout would be indistinguishable from a host that never answered.
        install(HttpTimeout)
        // The CIO engine carries its OWN request timeout, 15 s by default, which `HttpTimeout` left unset does not
        // lift (measured: a 4-photo seed on a freshly booted simulator died at 15 s as a request timeout). 0 disables
        // it, so every bound is the caller's.
        engine { requestTimeout = 0 }
    },
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    /** The typed services' kRPC session (`docs/testing.md`, "Typed services") — opened by the first call, on its own client. */
    private val rpcHttp = lazy { HttpClient(CIO) { installKrpc() } }
    private val rpc = lazy {
        rpcHttp.value.rpc("ws${base.removePrefix("http")}$RPC_PATH") { rpcConfig { serialization { json() } } }
    }

    /**
     * A typed service — `client.levers(BackendLevers).offline(true)` — through the client kotlinx-rpc generates from its
     * interface, over the host's kRPC route. A method answers its value, or throws: `LeverRefused` when this host
     * refuses the service, with its reason; otherwise the exception the implementation threw — rebuilt as its own class
     * from a JVM or Android host, as kRPC's stand-in carrying its message from an iOS host.
     */
    fun <T : Any> levers(service: LeverService<T>): T = rpc.value.withService(service.type)

    /** `GET /health`. */
    suspend fun health(): String = http.get("$base/health").bodyAsText()

    /** `GET /device` — what this host honours and refuses of the shared vocabulary. */
    suspend fun device(): DeviceAdvertisement =
        json.decodeFromString(DeviceAdvertisement.serializer(), http.get("$base/device").bodyAsText())

    /** The app's state — the real reduced UI state, and the read-models beside it ([HostLevers.state]). */
    suspend fun state(): RigState = levers(HostLevers).state()

    /**
     * Poll [state] until [until] holds, or fail after [timeout] naming the last state seen. A user command and a
     * fire-and-forget entry point are answered before their work finishes — exactly as a tap is — so a caller
     * waits on a condition, never on a sleep.
     */
    suspend fun awaitState(timeout: Duration = 10.seconds, until: (RigState) -> Boolean): RigState {
        var last: RigState? = null
        val reached = withTimeoutOrNull(timeout) {
            var state = state().also { last = it }
            while (!until(state)) {
                delay(POLL)
                state = state().also { last = it }
            }
            state
        }
        return reached ?: error("the state did not reach the condition within $timeout; last seen: $last")
    }

    override fun close() {
        if (rpc.isInitialized()) rpc.value.close()
        if (rpcHttp.isInitialized()) rpcHttp.value.close()
        http.close()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        val POLL = 50.milliseconds
    }
}

/** The rig's kRPC route (`RigServer`). */
private const val RPC_PATH = "/rpc"
