package app.snapsync.feature.push

import app.snapsync.ports.PushHttpClient

import co.touchlab.kermit.Logger

/**
 * Fires the backend event-notify (capability `upload-completion-notify` / `event-notify-endpoint`):
 * a bodyless `POST <host>/events/<eventId>/notify` that asks the backend to fan a silent push out to the
 * event's member devices. String-building only — no crypto, no token (the event id is the capability) —
 * over the injected [client] (the shared Ktor/Darwin client at the composition root), mirroring
 * [PushRegistration]. **Best-effort**: a non-2xx or transport failure is absorbed (logged) and **not**
 * retried — recipients' foreground discovery is the standing backstop, so a dropped notify only delays,
 * never loses. Callers wrap the invocation in their own timeout when firing it inside a bounded cycle.
 */
class EventNotifier(
    private val client: PushHttpClient,
    host: String,
    private val log: Logger = Logger.withTag("EventNotifier"),
) {
    private val base = host.trimEnd('/')

    /** `POST /events/[eventId]/notify` now. Absorbs any failure (never throws to the caller). */
    suspend fun notify(eventId: String) {
        client.post("$base/events/$eventId/notify")
            .onSuccess { log.i { "notified event $eventId" } }
            .onFailure { log.w(it) { "event notify failed for $eventId (best-effort, no retry)" } }
    }
}
