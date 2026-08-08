package app.snapsync.membership

import app.snapsync.ports.LeaveNotifier
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * The HTTP [LeaveNotifier]: notifies the backend a device is leaving an event
 * (`DELETE /events/<eventId>/devices/<deviceId>`, capability `event-leave-endpoint`), over an injected
 * Ktor [HttpClient] (Darwin on iOS, supplied by the composition root; `MockEngine` in tests). The
 * backend renames the device's manifest to its departed `.left.json` sibling and, when the last active
 * member leaves, reaps the event and garbage-collects its now-unreferenced bytes.
 *
 * It is **best-effort**: it returns a failed [Result] (never throws) so the caller's local teardown
 * proceeds regardless — a dropped notify simply leaves the backend membership in place (the accepted
 * abandon-leak), it never blocks or rolls back leaving locally. Invoked by both the explicit Leave
 * action and the switch path (provisioning a different event while joined; see `event-link`).
 *
 * **[deviceId] is bound here, as a thunk, and that is the whole reason this class holds it.** The port
 * says "this device is leaving"; *which* device is a per-process constant, not a per-call choice (see
 * [LeaveNotifier]). It is a thunk rather than a value because on iOS resolving the identity reads the
 * Keychain, which is unavailable before first unlock — binding it eagerly would drag that read into
 * composition and abort a locked background launch. It is read once per call, at the moment the request
 * is built, exactly as the composition's former `{ eventId -> leaveNotifier.leave(eventId, deviceId) }`
 * closure did.
 *
 * DELETEs `<host>/events/<eventId>/devices/<deviceId>` (HTTPS, default ATS) and maps any non-2xx /
 * transport error to a failed [Result]. Both ids are UUIDs, so no path encoding is required.
 */
class HttpLeaveNotifier(
    private val client: HttpClient,
    host: String,
    private val deviceId: () -> String,
) : LeaveNotifier {

    private val base = host.trimEnd('/')

    override suspend fun notifyLeaving(eventId: String): Result<Unit> = runCatching {
        val id = deviceId()
        val response: HttpResponse = client.delete("$base/events/$eventId/devices/$id")
        check(response.status.isSuccess()) {
            "leave $eventId/$id: HTTP ${response.status.value}"
        }
    }
}
