package app.snapsync.client

import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.HostLevers
import app.snapsync.levers.LeverService
import app.snapsync.levers.RigState
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WorldLevers
import app.snapsync.ui.state.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.withService
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The typed client of the control protocol, for either host — the JVM host or the app on a device or simulator —
 * at [baseUrl] (for example `http://127.0.0.1:18099`).
 *
 * It is written against the wire types the rig itself encodes ([RigState] embeds the real `UiState`), so there is
 * no second rendering of the state here that could disagree with the screen. Its calls go over one [RpcConnection],
 * replaced when it drops (which calls are sent again, and which never are, is said there).
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

    /** The typed services' kRPC connection (`docs/testing.md`, "Typed services") — opened by the first call. */
    private val connection = lazy { RpcConnection(base) }

    /**
     * A typed service — `client.levers(BackendLevers).offline(true)` — through the client kotlinx-rpc generates from its
     * interface, over the host's kRPC route. A method answers its value, or throws: `LeverRefused` when this host
     * refuses the service, with its reason; otherwise the exception the implementation threw — rebuilt as its own class
     * from a JVM or Android host, as kRPC's stand-in carrying its message from an iOS host. Each call goes out on the
     * connection live when it is made, so a service held across a dropped connection still answers.
     */
    fun <T : Any> levers(service: LeverService<T>): T = connection.value.service(service.type)

    /** `GET /health`. */
    suspend fun health(): String = http.get("$base/health").bodyAsText()

    /** `GET /device` — what this host honours and refuses of the shared vocabulary. */
    suspend fun device(): DeviceAdvertisement =
        json.decodeFromString(DeviceAdvertisement.serializer(), http.get("$base/device").bodyAsText())

    /** The app's state — the real reduced UI state, and the read-models beside it ([HostLevers.state]). */
    suspend fun state(): RigState = levers(HostLevers).state()

    /**
     * Waits on the host's events ([WorldLevers.events]) for the first that [match] answers a value for, and answers it —
     * or fails once [within] passed, naming [what] it waited for, the last screen it saw and why its connection last
     * dropped. A dropped connection is opened again, and the stream followed again, until then.
     *
     * Where the stream starts is the host's: a device host serves from where its latest act began, so a wait that
     * follows its act misses nothing of it. The JVM host serves from its start unless it was started to serve as a
     * device does (`JvmRigHost.start(serving = …)`); from its start, the first match may be from before the act — so
     * there a test waits through `behaviour { … }`, or on a host started that way.
     */
    suspend fun <T : Any> awaitEvent(within: Duration, what: String, match: (WorldEvent) -> T?): T {
        var screen: UiState? = null
        var drop: String? = null
        var found: T? = null
        withTimeoutOrNull(within) {
            while (found == null) {
                val ended = connection.value.follow(
                    open = { it.withService(WorldLevers::class).events() },
                    dropped = { drop = it },
                ) { event ->
                    if (event is WorldEvent.UiChanged) screen = event.ui
                    found = match(event)
                    found != null
                }
                // The host ended the stream before the event came: follow it again.
                if (!ended) delay(FOLLOW_AGAIN_AFTER)
            }
        }
        return found ?: throw AssertionError(
            "$what: not within $within. The last screen seen: ${screen ?: "none"}" +
                drop?.let { ". The connection last dropped: $it" }.orEmpty(),
        )
    }

    /**
     * The screen shown now if it [holds]; otherwise the first that does ([WorldEvent.UiChanged]), as [awaitEvent] waits.
     * The screen is read first because what an act causes may come before a later act of the same caller — a form's
     * last tap after the one that opened the next screen — and a device host serves its stream only from the latest
     * act on. Read then followed, nothing falls between: the stream starts before the read, at that latest act.
     */
    suspend fun awaitScreen(within: Duration, what: String, holds: (UiState) -> Boolean): UiState =
        state().ui.takeIf(holds)
            ?: awaitEvent(within, what) { event -> (event as? WorldEvent.UiChanged)?.ui?.takeIf(holds) }

    override fun close() {
        if (connection.isInitialized()) connection.value.close()
        http.close()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        val FOLLOW_AGAIN_AFTER = 500.milliseconds
    }
}
