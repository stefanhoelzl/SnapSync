package app.snapsync.push

import app.snapsync.model.ApnsPushToken
import app.snapsync.ports.PushTokenPublisher

import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PushTokenDto(val kind: String, val token: String, val env: String)

@Serializable
private data class DeviceConfigDto(val pushToken: PushTokenDto)

private val json = Json { encodeDefaults = true }

/** The `devices/<id>` config body for [token] — always `kind: "apns"` in this app. */
internal fun deviceConfigJson(token: ApnsPushToken): String =
    json.encodeToString(DeviceConfigDto.serializer(), DeviceConfigDto(PushTokenDto("apns", token.token, token.env)))

/**
 * [PushTokenPublisher] over an injected Ktor [HttpClient] (the shared Darwin client on iOS): `PUT
 * <host>/devices/<deviceId>` with `{ pushToken: { kind: "apns", token, env } }`. String/JSON-building only —
 * no crypto, and **no event id** (the token is device-scoped, event-independent).
 *
 * [deviceId] is a **supplier**, as every other backend client takes it (capability `device-identity`): a
 * value resolved at construction could not be retried on a host whose secure store could not yet serve it.
 */
class HttpPushTokenPublisher(
    private val client: HttpClient,
    host: String,
    private val deviceId: () -> String,
) : PushTokenPublisher {

    private val base = host.trimEnd('/')

    override suspend fun publish(token: ApnsPushToken): Result<Unit> = runCatching {
        val url = "$base/devices/${deviceId()}"
        val res = client.put(url) {
            contentType(ContentType.Application.Json)
            setBody(deviceConfigJson(token))
        }
        check(res.status.isSuccess()) { "config PUT $url: HTTP ${res.status.value} ${res.bodyAsText()}" }
    }
}
