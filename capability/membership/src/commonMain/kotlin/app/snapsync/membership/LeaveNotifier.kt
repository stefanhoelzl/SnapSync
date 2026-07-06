package app.snapsync.membership

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * The seam that notifies the backend a device is leaving an event
 * (`DELETE /events/<eventId>/devices/<deviceId>`, capability `event-leave-endpoint`). The backend
 * renames the device's manifest to its departed `.left.json` sibling and, when the last active member
 * leaves, reaps the event and garbage-collects its now-unreferenced bytes.
 *
 * It is **best-effort**: it returns a failed [Result] (never throws) so the caller's local teardown
 * proceeds regardless — a dropped notify simply leaves the backend membership in place (the accepted
 * abandon-leak), it never blocks or rolls back leaving locally. Invoked by both the explicit Leave
 * action and the switch path (provisioning a different event while joined; see `deeplink-config`).
 */
interface LeaveNotifier {
    suspend fun leave(eventId: String, deviceId: String): Result<Unit>
}

/**
 * The [LeaveNotifier] over an injected Ktor [HttpClient] (Darwin on iOS, supplied by the composition
 * root; `MockEngine` in tests). DELETEs `<host>/events/<eventId>/devices/<deviceId>` (HTTPS, default
 * ATS) and maps any non-2xx / transport error to a failed [Result]. Both ids are UUIDs, so no path
 * encoding is required.
 */
class HttpLeaveNotifier(
    private val client: HttpClient,
    host: String,
) : LeaveNotifier {

    private val base = host.trimEnd('/')

    override suspend fun leave(eventId: String, deviceId: String): Result<Unit> = runCatching {
        val response: HttpResponse = client.delete("$base/events/$eventId/devices/$deviceId")
        check(response.status.isSuccess()) {
            "leave $eventId/$deviceId: HTTP ${response.status.value}"
        }
    }
}
