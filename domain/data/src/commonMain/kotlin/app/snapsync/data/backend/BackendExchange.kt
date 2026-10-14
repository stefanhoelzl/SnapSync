package app.snapsync.data.backend

import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.BackendAnswer
import app.snapsync.domain.trust.BearerToken
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.Maintenance
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.OpenRequest
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.api.Success
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.runtime.WireRequest
import app.snapsync.wire.runtime.WireResponse

/**
 * The backend's protocol (`docs/architecture.md`, "The backend boundary"), over the plain [HttpTransport]: every
 * request carries the declared version — the OS transfers too, through [versionHeaders] — and goes to the [target]'s
 * backend; every answer becomes its route's generated reply, and what it says about this build reaches [versions].
 *
 * It sends [OpenRequest]s — the `/attest/…` issuers, which mint the very token a gated request needs — and carries no
 * credential of its own. [BackendGateway] is it with one: the app's attesting credential reaches the issuers through
 * this, and is the gateway's credential, so the two are separate objects rather than one built in a cycle.
 */
class BackendExchange(
    private val transport: HttpTransport,
    private val target: BackendTarget,
    private val versions: VersionObserver,
) {
    /** One open request, answered in its own reply type. Never throws (cancellation aside). */
    suspend fun <R : Reply> send(request: OpenRequest<R>): R =
        exchange(request.wire, request.decide, request.unreachable, token = null)

    /**
     * The version every request declares, as headers — for the OS-performed transfers too, which carry it besides their
     * own. None for a build that declares no version (a test's), which the backend answers as too old.
     */
    fun versionHeaders(): Map<String, String> =
        target.declaredVersion.takeIf { it.isNotBlank() }?.let { mapOf(Limits.VERSION_HEADER to it) }.orEmpty()

    /** The absolute address of [path] — a route's path — on the target's backend. */
    fun url(path: String): String = target.base.trimEnd('/') + path

    internal suspend fun <R : Reply> exchange(
        wire: WireRequest,
        decide: (WireResponse) -> R,
        unreachable: (String) -> R,
        token: BearerToken?,
    ): R {
        val headers = wire.headers + versionHeaders() +
            listOfNotNull(token?.let { AUTHORIZATION to "Bearer ${it.secret}" })
        val reply = when (val result = transport.send(HttpRequest(wire.method, url(wire.path), headers, wire.body))) {
            is HttpResult.Response -> decide(WireResponse(result.status, result.headers, result.body))
            is HttpResult.Unreachable -> unreachable(result.reason)
        }
        versions.observe(BackendAnswers.of(reply))
        return reply
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * How [reply] tells SnapSync's service failed, or `null` for an answer from a working service — the one reading every
 * need-shaped service gives a failure, so each of the three is told in its own words.
 */
fun faultOf(reply: Reply): ServiceFault? = ServiceFault.of(BackendAnswers.of(reply))

/** The one reading of a route's reply as the credential and the version gate need it: its category. */
internal object BackendAnswers {
    fun of(reply: Reply): BackendAnswer = when (reply) {
        Unauthorized -> BackendAnswer.Rejected
        is AppTooOld -> BackendAnswer.TooOld(reply.appTooOldError.minAppVersion)
        is ClientError -> BackendAnswer.Refused
        is Success -> BackendAnswer.Served
        is Maintenance -> BackendAnswer.Updating
        is ServerIssue -> BackendAnswer.ServerIssue
        is NoAnswer -> BackendAnswer.NoAnswer
    }
}

/** What every backend answer is shown to: the app's version gate, or nobody (a process with no screen to tell). */
interface VersionObserver {
    suspend fun observe(answer: BackendAnswer)
}

/** Every answer shown to each of [observers], in the order given. */
class Observers(private vararg val observers: VersionObserver) : VersionObserver {
    override suspend fun observe(answer: BackendAnswer) = observers.forEach { it.observe(answer) }
}

/** No version gate: the upload extension has no screen to show a refusal on. */
object VersionIgnored : VersionObserver {
    override suspend fun observe(answer: BackendAnswer) = Unit
}
