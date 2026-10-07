package app.snapsync.services.backend

import app.snapsync.model.PushEndpoint
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.Reply
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.Backend
import app.snapsync.services.version.AppVersionGate
import co.touchlab.kermit.Logger

/**
 * The backend as every need-shaped service uses it: the [Backend] port's routes with the credential attached and
 * the backend's verdicts answered (capability `privacy-security`, "Only a rejected credential is invalidated, and
 * only that one"; capability `app-update-required`).
 *
 * Its own interface, with no token parameter, so a service above it cannot send a credential of its own choosing
 * and cannot skip the verdicts. The three `/attest/…` routes are not here: they issue the credential, and the
 * attestation service reaches them on the raw port — which is also what keeps recovery from re-entering itself.
 */
interface AuthenticatedBackend {
    suspend fun createEvent(req: CreateEventRequest): Reply<EventCreated>
    suspend fun getEvent(eventId: String): Reply<EventMeta>
    suspend fun renameEvent(eventId: String, name: String): Reply<EventRenamed>
    suspend fun joinEvent(eventId: String, deviceId: String): Reply<Unit>
    suspend fun publishManifest(eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit>
    suspend fun leaveEvent(eventId: String, deviceId: String, received: Boolean): Reply<Unit>
    suspend fun eventFiles(eventId: String, cursor: Long?, trigger: UnionTrigger): Reply<UnionPage>
    suspend fun deviceFiles(eventId: String, deviceId: String): Reply<List<DeviceFile>>
    suspend fun putDeviceConfig(deviceId: String, push: PushEndpoint): Reply<Unit>
}

/**
 * Where [CredentialedBackend] gets the token it sends, and what it tells when the backend rejects one.
 *
 * The app's is the attestation service, which drops the rejected token and obtains a new one; the upload
 * extension's cannot attest, so it only drops it (`ExtensionCredential`).
 */
interface Credential {

    /** The token to send now, or `null` when there is none. May be expired: the backend decides. */
    fun token(): String?

    /**
     * The backend rejected [sent] on a gated route. Drop it if it is still the one held, recover if this process
     * can, and answer the token to retry with — or `null` when there is nothing better to send.
     *
     * Never throws: a recovery that fails answers `null`, and the rejection stands as the call's answer.
     */
    suspend fun rejected(sent: String): String?

    /**
     * This process holds no token and a USER is waiting on the call: obtain one now if this process can, and answer
     * it — or `null`. What makes a tap on a phone that never attested try to verify it again (capability `create-event`,
     * "The front screen tells a refused phone before it tries"). Only the extension, which cannot attest, keeps the
     * default. Never throws.
     */
    suspend fun missing(): String? = null
}

/**
 * [AuthenticatedBackend] over the [Backend] port — where the decisions that used to sit in the HTTP client's
 * interceptor live now, each once:
 *
 * - **The token** is read from [credential] per call, never held: a renewal in the background is picked up by the
 *   next call.
 * - **`401` from a gated route**, to a call that carried a token, means the backend REJECTED that token — not that
 *   it expired, which the expiry check already sees. The rejection names the token the call carried, so the
 *   credential can drop it only if it is still the one held. A call that carried no token learns nothing about any
 *   credential from its `401`.
 * - **Retry once.** When the credential answers a token different from the one rejected, the call is sent once
 *   more with it. A `401` is answered by the backend's gate before any route runs, so the first attempt changed
 *   nothing and the retry is safe for every route. It used to fail the call and recover in the background, so the
 *   call's caller learned "failed" for a request the next attempt would serve; now the same call is served. Only
 *   once: a second rejection is the call's answer.
 * - **`426`** on any route is the backend refusing this build, reported to [versionGate] with the minimum it names;
 *   **any success** clears that refusal. The upload extension composes this with no gate: it has no screen to show
 *   a refusal on.
 *
 * The two event reads — the event's details and the union — are public on the backend, authorized by the event id
 * alone, but carry the credential all the same: the backend verifies a token it is sent (the union so its log can name
 * the reader, decision record `changes/incremental-union` D5; the details since
 * `changes/separate-event-page-from-device-api` D7, ahead of the backend requiring it), so their `401` is a verdict on
 * that token and is recovered from like any gated route's.
 */
class CredentialedBackend(
    private val backend: Backend,
    private val credential: Credential,
    private val versionGate: AppVersionGate?,
    private val log: Logger = Logger.withTag("AuthenticatedBackend"),
) : AuthenticatedBackend {

    override suspend fun createEvent(req: CreateEventRequest) = gated(obtainFirst = true) { backend.createEvent(it, req) }

    override suspend fun getEvent(eventId: String) = gated { backend.getEvent(it, eventId) }

    override suspend fun renameEvent(eventId: String, name: String) = gated { backend.renameEvent(it, eventId, name) }

    override suspend fun joinEvent(eventId: String, deviceId: String) =
        gated(obtainFirst = true) { backend.joinEvent(it, eventId, deviceId) }

    override suspend fun publishManifest(eventId: String, deviceId: String, manifest: DeviceManifest) =
        gated { backend.publishManifest(it, eventId, deviceId, manifest) }

    override suspend fun leaveEvent(eventId: String, deviceId: String, received: Boolean) =
        gated { backend.leaveEvent(it, eventId, deviceId, received) }

    override suspend fun eventFiles(eventId: String, cursor: Long?, trigger: UnionTrigger) =
        gated { backend.eventFiles(it, eventId, cursor, trigger) }

    override suspend fun deviceFiles(eventId: String, deviceId: String) =
        gated { backend.deviceFiles(it, eventId, deviceId) }

    override suspend fun putDeviceConfig(deviceId: String, push: PushEndpoint) =
        gated { backend.putDeviceConfig(it, deviceId, push) }

    /**
     * [obtainFirst] is for the calls a user taps — create and join: with no token held, the credential is asked for one
     * BEFORE sending, since the call would only `401`. Every other route is a background one and sends what it holds,
     * so a phone the service refuses is not re-attested once per background call.
     */
    private suspend fun <T> gated(obtainFirst: Boolean = false, call: suspend (token: String?) -> Reply<T>): Reply<T> {
        val sent = credential.token() ?: if (obtainFirst) credential.missing() else null
        val first = observed(call(sent))
        if (sent == null || first !is Reply.Refused || first.status != HttpStatus.UNAUTHORIZED) return first
        log.w { "the backend rejected the token this call carried — recovering" }
        val retry = credential.rejected(sent)?.takeIf { it != sent } ?: return first
        log.i { "retrying once with the recovered token" }
        return observed(call(retry))
    }

    private fun <T> observed(reply: Reply<T>): Reply<T> = reply.also { versionGate?.observe(it) }
}
