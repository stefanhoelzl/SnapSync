package app.snapsync.services.backend

import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.Reply
import app.snapsync.model.UnionAsset
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
    suspend fun leaveEvent(eventId: String, deviceId: String): Reply<Unit>
    suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>>
    suspend fun deviceFiles(deviceId: String): Reply<List<DeviceFile>>
    suspend fun putDeviceConfig(deviceId: String, push: ApnsPushToken): Reply<Unit>
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
 * The two event reads the backend authorizes by the event id alone carry no token, so a rejection cannot arise
 * there; only the version verdicts apply.
 */
class CredentialedBackend(
    private val backend: Backend,
    private val credential: Credential,
    private val versionGate: AppVersionGate?,
    private val log: Logger = Logger.withTag("AuthenticatedBackend"),
) : AuthenticatedBackend {

    override suspend fun createEvent(req: CreateEventRequest) = gated { backend.createEvent(it, req) }

    override suspend fun getEvent(eventId: String) = observed(backend.getEvent(eventId))

    override suspend fun renameEvent(eventId: String, name: String) = gated { backend.renameEvent(it, eventId, name) }

    override suspend fun joinEvent(eventId: String, deviceId: String) = gated { backend.joinEvent(it, eventId, deviceId) }

    override suspend fun publishManifest(eventId: String, deviceId: String, manifest: DeviceManifest) =
        gated { backend.publishManifest(it, eventId, deviceId, manifest) }

    override suspend fun leaveEvent(eventId: String, deviceId: String) = gated { backend.leaveEvent(it, eventId, deviceId) }

    override suspend fun eventFiles(eventId: String) = observed(backend.eventFiles(eventId))

    override suspend fun deviceFiles(deviceId: String) = gated { backend.deviceFiles(it, deviceId) }

    override suspend fun putDeviceConfig(deviceId: String, push: ApnsPushToken) =
        gated { backend.putDeviceConfig(it, deviceId, push) }

    private suspend fun <T> gated(call: suspend (token: String?) -> Reply<T>): Reply<T> {
        val sent = credential.token()
        val first = observed(call(sent))
        if (sent == null || first !is Reply.Refused || first.status != UNAUTHORIZED) return first
        log.w { "the backend rejected the token this call carried — recovering" }
        val retry = credential.rejected(sent)?.takeIf { it != sent } ?: return first
        log.i { "retrying once with the recovered token" }
        return observed(call(retry))
    }

    private fun <T> observed(reply: Reply<T>): Reply<T> = reply.also { versionGate?.observe(it) }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
