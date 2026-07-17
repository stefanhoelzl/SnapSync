package app.snapsync.world

import app.snapsync.ports.Enrollment
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * A common [Enrollment] living in `:test:world` (design decision D6): PUTs the device
 * manifest JSON to `<host>/events/<eventId>/devices/<deviceId>` over the injected mini-edge [client] —
 * a common-source mirror of the shipped iosMain `IosEnrollment`.
 *
 * **Ktor-home rationale.** A common manifest uploader needs a module that is BOTH a home for the
 * `Enrollment` seam AND ktor-bearing — but no production module qualifies: `:domain:gallery`
 * owns the seam yet is deliberately ktor-free, `:capability:upload` is deliberately ktor-free, and the
 * ktor-bearing capabilities (`rejoin`/`download`/`event-creation-ui`) do not own the seam. So the only
 * non-polluting common home for a real ktor manifest PUT is this test-infra module; production's
 * `IosEnrollment` and the `device-manifest` seam home stay untouched.
 */
class HttpEnrollment(
    private val client: HttpClient,
    host: String,
) : Enrollment {

    private val base = host.trimEnd('/')

    override suspend fun put(eventId: String, deviceId: String, json: String): Boolean = runCatching {
        client.put("$base/events/$eventId/devices/$deviceId") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }.status.isSuccess()
    }.getOrDefault(false)
}
