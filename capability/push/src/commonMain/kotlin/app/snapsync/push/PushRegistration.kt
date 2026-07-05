package app.snapsync.push

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An APNs device token and the APNs environment it belongs to — the `pushToken` persisted in
 * `devices/<deviceId>/config.json`. `env` is `"sandbox"` (dev/sideloaded builds) or `"production"`
 * (TestFlight/App Store).
 */
data class ApnsPushToken(val token: String, val env: String)

/**
 * The current-APNs-token source (capability `push-registration`). The token is **OS-push-delivered**,
 * not pulled: the iOS app-shell wiring calls [deliver] from the AppDelegate's
 * `didRegisterForRemoteNotificationsWithDeviceToken`; tests call [deliver] directly (it is its own
 * settable fake — one implementation suffices). [env] is the build's APNs environment, injected at
 * **compile time** (from `Config.xcconfig`'s `APNS_ENV`), never detected at runtime. Delivering a new
 * token models a rotation, which [PushRegistration.run] re-registers.
 */
class PushTokenSource(val env: String) {
    private val _token = MutableStateFlow<String?>(null)

    /** The latest OS-delivered device token (hex), or `null` before the OS delivers one. */
    val token: StateFlow<String?> = _token.asStateFlow()

    /** Deliver an OS-provided device token (initial acquisition or a rotation). */
    fun deliver(hexToken: String) {
        _token.value = hexToken
    }
}

/**
 * The minimal HTTP seam the push capability uses: `PUT` a JSON body (registration) and a bodyless
 * `POST` (the event notify, capability `event-notify-endpoint`). The real implementation
 * ([KtorPushHttpClient]) wraps the shared Ktor/Darwin client injected by the composition root; tests
 * inject a fake. Errors are returned as a failed [Result], never thrown.
 */
interface PushHttpClient {
    suspend fun put(url: String, jsonBody: String): Result<Unit>

    /** `POST` [url] with no request body (the notify endpoint takes no payload and no token). */
    suspend fun post(url: String): Result<Unit>
}

/** [PushHttpClient] over an injected Ktor [HttpClient] (the shared Darwin client on iOS). */
class KtorPushHttpClient(private val client: HttpClient) : PushHttpClient {
    override suspend fun put(url: String, jsonBody: String): Result<Unit> = runCatching {
        val res = client.put(url) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        check(res.status.isSuccess()) { "config PUT $url: HTTP ${res.status.value} ${res.bodyAsText()}" }
    }

    override suspend fun post(url: String): Result<Unit> = runCatching {
        val res = client.post(url)
        check(res.status.isSuccess()) { "notify POST $url: HTTP ${res.status.value} ${res.bodyAsText()}" }
    }
}

@Serializable
private data class PushTokenDto(val kind: String, val token: String, val env: String)

@Serializable
private data class DeviceConfigDto(val pushToken: PushTokenDto)

private val json = Json { encodeDefaults = true }

/** The `devices/<id>/config.json` body for [token] — always `kind: "apns"` in this app. */
internal fun deviceConfigJson(token: ApnsPushToken): String =
    json.encodeToString(DeviceConfigDto.serializer(), DeviceConfigDto(PushTokenDto("apns", token.token, token.env)))

/**
 * Registers the device's APNs token with the backend (capability `push-registration`). On each token —
 * launch delivery and every rotation — it `PUT`s `<host>/devices/<deviceId>/config` with
 * `{ pushToken: { kind: "apns", token, env } }` via the injected [client]. String/JSON-building only —
 * no crypto, and **no event id** (the token is device-scoped, event-independent). A failed write is
 * absorbed (logged) and retried on the next token, so registration never blocks join/upload/download.
 * Idempotent: re-registering the same token overwrites an identical config (last-write-wins at the
 * endpoint), so repeated launches with an unchanged token are harmless.
 */
class PushRegistration(
    private val client: PushHttpClient,
    host: String,
    private val deviceId: String,
    private val log: Logger = Logger.withTag("PushRegistration"),
) {
    private val url = "${host.trimEnd('/')}/devices/$deviceId/config"

    /** `PUT` the config for [token] now. Absorbs any failure (never throws to the caller). */
    suspend fun register(token: ApnsPushToken) {
        client.put(url, deviceConfigJson(token))
            .onSuccess { log.i { "push token registered" } }
            .onFailure { log.w(it) { "push registration failed (will retry on next token)" } }
    }

    /**
     * Collect [source] and [register] on every delivered/rotated token. Suspends for the caller scope's
     * lifetime (launched once from the composition root).
     */
    suspend fun run(source: PushTokenSource) {
        source.token.filterNotNull().collect { register(ApnsPushToken(it, source.env)) }
    }
}
