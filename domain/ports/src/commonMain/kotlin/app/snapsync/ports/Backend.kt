package app.snapsync.ports

import app.snapsync.model.PushEndpoint
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger

/**
 * The SnapSync backend — one external system, one thin port (`docs/architecture.md`, "Ports are the I/O boundary
 * named for the need"). One method per route the app calls, each answering a typed [Reply].
 *
 * **It decides nothing.** A `401`, a `404` and a `426` come back as [Reply.Refused] with the status the backend
 * sent; what they mean — a rejected credential to recover from, an event that is gone, a build too old to serve —
 * is decided by the services above it (`AuthenticatedBackend` and the need-shaped services in `:domain:services`).
 * That is what makes a mock of it honest: it answers what a backend would answer, and holds no policy a test could
 * accidentally bypass.
 *
 * **The token is passed per call**, and only to the routes that verify one: whether a method takes a `token` IS
 * whether its route verifies it — every gated route, and the union read, which is public but checks a token it is
 * sent so its log can name the reader (decision record `changes/incremental-union`, D5). The ungated rest — the three
 * `/attest/…` issuers and the event's details — take none. A `null` token still sends the request: a gated route
 * answers it `401`, the union serves it anonymously, and either is the service's to handle.
 *
 * Implementations never throw (cancellation aside): a transport failure is [Reply.Unreachable], a success whose
 * body does not decode is [Reply.Malformed].
 *
 * Its promises are the port contract `BackendContract` (`docs/testing.md`), run against the real `api/` and the
 * in-memory mock.
 */
interface Backend : Port {

    /** `GET /attest/challenge` — a server-issued nonce. */
    suspend fun challenge(): Reply<String>

    /** `POST /attest/token` — a device token for a fresh attestation. */
    suspend fun mintToken(req: MintRequest): Reply<String>

    /** `POST /attest/renew` — a device token for an assertion by an attested key. */
    suspend fun renewToken(req: RenewRequest): Reply<String>

    /** `POST /events` — mint an event. */
    suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated>

    /** `GET /events/<eventId>` — an event's details; public, authorized by the id alone. */
    suspend fun getEvent(eventId: String): Reply<EventMeta>

    /** `PATCH /events/<eventId>` — rename an event. */
    suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed>

    /** `PUT /events/<eventId>/devices/<deviceId>` — create or reactivate a membership. No body. */
    suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit>

    /** `PUT /events/<eventId>/devices/<deviceId>/manifest` — replace what a member shares. */
    suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit>

    /**
     * `DELETE /events/<eventId>/devices/<deviceId>?received=<received>` — end a membership. [received] is the
     * device's word that it holds every photo of the others; the backend judges the rest of "left having
     * everything" itself.
     */
    suspend fun leaveEvent(token: String?, eventId: String, deviceId: String, received: Boolean): Reply<Unit>

    /**
     * `GET /events/<eventId>/files` — the event-wide union of complete assets; public, authorized by the id alone. From
     * [cursor] only what was gained after it, all of it for `null`; [trigger] is why the app reads. Each resource's
     * `url` is its stable download address, which redirects to the bytes.
     */
    suspend fun eventFiles(token: String?, eventId: String, cursor: Long?, trigger: UnionTrigger): Reply<UnionPage>

    /** `GET /files/devices/<deviceId>` — what a device has stored. */
    suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>>

    /** `PUT /devices/<deviceId>` — publish a device's push registration. */
    suspend fun putDeviceConfig(token: String?, deviceId: String, push: PushEndpoint): Reply<Unit>
}
