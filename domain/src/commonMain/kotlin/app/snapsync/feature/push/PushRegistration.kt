package app.snapsync.feature.push

import app.snapsync.ports.PushHttpClient
import app.snapsync.ports.PushTokenSource

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An APNs device token and the APNs environment it belongs to — the `pushToken` persisted in
 * `devices/<deviceId>.json`. `env` is `"sandbox"` (dev/sideloaded builds) or `"production"`
 * (TestFlight/App Store).
 */
data class ApnsPushToken(val token: String, val env: String)

@Serializable
private data class PushTokenDto(val kind: String, val token: String, val env: String)

@Serializable
private data class DeviceConfigDto(val pushToken: PushTokenDto)

private val json = Json { encodeDefaults = true }

/** The `devices/<id>.json` config body for [token] — always `kind: "apns"` in this app. */
internal fun deviceConfigJson(token: ApnsPushToken): String =
    json.encodeToString(DeviceConfigDto.serializer(), DeviceConfigDto(PushTokenDto("apns", token.token, token.env)))

/**
 * Registers the device's APNs token with the backend (capability `push-registration`). On each token —
 * launch delivery and every rotation — it `PUT`s `<host>/devices/<deviceId>` with
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
    private val url = "${host.trimEnd('/')}/devices/$deviceId"

    /** `PUT` the config for [token] now. Absorbs any failure (never throws to the caller). */
    suspend fun register(token: ApnsPushToken) {
        client.put(url, deviceConfigJson(token))
            .onSuccess { log.i { "push token registered" } }
            .onFailure { log.w(it) { "push registration failed (will retry on next token)" } }
    }

    /**
     * Register on every delivered/rotated token — and again whenever [credentialChanged] fires. Suspends
     * for the caller scope's lifetime (launched once from the composition root).
     *
     * **[credentialChanged] is not an optimization; without it a failed registration is permanent.** The
     * OS delivers an APNs token *once* and does not re-deliver it, so a `PUT` refused because the device
     * had no valid attestation token yet (a fresh install races attestation) would never be retried — the
     * device would sit permanently unregistered, receiving no silent pushes, no download wakes, and none
     * of the wake-driven token renewals. Re-registering when a new credential arrives closes exactly that
     * hole. The `PUT` is idempotent (last-write-wins), so a redundant one costs nothing.
     */
    suspend fun run(source: PushTokenSource, credentialChanged: Flow<Unit> = emptyFlow()) {
        merge(
            source.token.filterNotNull().map { },
            credentialChanged,
        ).collect {
            val token = source.token.value ?: return@collect
            register(ApnsPushToken(token, source.env))
        }
    }
}
