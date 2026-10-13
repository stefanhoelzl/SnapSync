package app.snapsync.contracts.attest

import app.snapsync.contracts.Attester
import app.snapsync.contracts.SERVED_APP_VERSION
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.ChallengeReply
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.MintTokenReply
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.MintRequest
import app.snapsync.wire.runtime.WireResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bearer token of each device a test host plays, obtained the way a device obtains one — a challenge, a proof over
 * it from that device's own key ([attester]), and the backend's mint — so a played device is enrolled exactly as a
 * real one is, and no backend has to serve a request that carries no token. One mint per device, kept.
 *
 * [version] is the build version the requests declare (a backend refuses an outdated one before it attests).
 */
class DeviceTokens(
    private val transport: HttpTransport,
    base: String,
    private val attester: () -> Attester,
    private val version: () -> String = { SERVED_APP_VERSION },
) {
    private val base = base.trimEnd('/')
    private val lock = Mutex()
    private val held = mutableMapOf<String, String>()

    /** [deviceId]'s token: the one already minted, or a fresh mint. Fails naming the step the backend refused. */
    suspend fun tokenFor(deviceId: String): String = lock.withLock {
        held[deviceId] ?: mint(deviceId).also { held[deviceId] = it }
    }

    private suspend fun mint(deviceId: String): String {
        val challenge = when (val reply = send(Routes.Challenge.request())) {
            is ChallengeReply.Issued -> reply.challenge.challenge
            else -> error("no challenge for $deviceId: $reply")
        }
        val proof = attester().proof(challenge)
        return when (val reply = send(Routes.MintToken.request(MintRequest(deviceId, challenge, proof)))) {
            is MintTokenReply.Minted -> reply.token.token
            else -> error("no token for $deviceId: $reply")
        }
    }

    private suspend fun <R : Reply> send(request: OpenRequest<R>): R {
        val wire = request.wire
        val headers = wire.headers + (Limits.VERSION_HEADER to version())
        return when (val result = transport.send(HttpRequest(wire.method, base + wire.path, headers, wire.body))) {
            is HttpResult.Response -> request.decide(WireResponse(result.status, result.headers, result.body))
            is HttpResult.Unreachable -> request.unreachable(result.reason)
        }
    }
}
