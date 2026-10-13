package app.snapsync.data.backend

import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.trust.CredentialVerdict
import app.snapsync.domain.trust.RetryVerdict
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.Reply

/**
 * The backend as every need-shaped service reaches it: [BackendExchange] with the device's [credential] attached to
 * every gated request, and the backend's verdict on it answered — only a rejected credential is invalidated, and only
 * that one:
 *
 * - **The token** is read from [credential] per request, never held: a renewal in the background is picked up by the
 *   next one. One that cannot be read sends nothing: the request is answered as no answer, which every caller retries.
 * - **A `401`** to a request that carried a token is the backend REJECTING that token ([CredentialVerdict]). The
 *   credential drops it if it is still the one held, and recovers if this process can.
 * - **Retry once** ([RetryVerdict]), with a token other than the one rejected — safe for every route, because the
 *   backend's gate answers before any route runs. A second rejection is the request's answer.
 *
 * Only a [GatedRequest] can carry the token — the generated routes type each operation by its `security` — so a token
 * never reaches the issuers, whose own `401`s are verdicts on what was sent, not on a credential.
 */
class BackendGateway(private val exchange: BackendExchange, private val credential: Credential) {

    /** One gated request, carrying what the process holds. */
    suspend fun <R : Reply> send(request: GatedRequest<R>): R = when (val read = credential.token()) {
        is TokenRead.Held -> carrying(request, read.token)
        TokenRead.None -> exchange.exchange(request.wire, request.decide, request.unreachable, token = null)
        is TokenRead.Unavailable -> request.unreachable("the credential could not be read: ${read.detail}")
    }

    /**
     * One gated request that must not go out without a token — a create, a join, the push registration: a process
     * holding none obtains one first ([Credential.missing]), since the request would only be refused.
     */
    suspend fun <R : Reply> sendObtainingFirst(request: GatedRequest<R>): R = when (val read = credential.token()) {
        is TokenRead.Held -> carrying(request, read.token)
        TokenRead.None -> exchange.exchange(
            request.wire,
            request.decide,
            request.unreachable,
            credential.missing().held(),
        )
        is TokenRead.Unavailable -> request.unreachable("the credential could not be read: ${read.detail}")
    }

    /** The open half, for a service that needs both. */
    val open: BackendExchange get() = exchange

    private suspend fun <R : Reply> carrying(request: GatedRequest<R>, sent: BearerToken): R {
        val first = exchange.exchange(request.wire, request.decide, request.unreachable, sent)
        return when (CredentialVerdict.of(BackendAnswers.of(first))) {
            CredentialVerdict.Rejected -> recovered(request, sent, first)
            CredentialVerdict.Answered -> first
        }
    }

    private suspend fun <R : Reply> recovered(request: GatedRequest<R>, sent: BearerToken, first: R): R =
        when (val verdict = RetryVerdict.of(sent, credential.rejected(sent).held())) {
            is RetryVerdict.Retry -> exchange.exchange(request.wire, request.decide, request.unreachable, verdict.token)
            RetryVerdict.Stands -> first
        }
}

/**
 * Where [BackendGateway] gets the token it sends, and what it tells when the backend rejects one. The app's attests
 * (`AttestingCredential`); the upload extension's cannot, so it only drops a rejected token (`DropOnlyCredential`).
 */
interface Credential {
    /** The token to send now. May be expired: the backend decides. */
    fun token(): TokenRead

    /**
     * The backend rejected [sent] on a gated route: drop it if it is still the one held, recover if this process can,
     * and answer what is held now. Never throws.
     */
    suspend fun rejected(sent: BearerToken): TokenRead

    /** This process holds no token and the request must not go out without one: obtain one if this process can. */
    suspend fun missing(): TokenRead
}

/** What reading the held token found. */
sealed class TokenRead {
    class Held(val token: BearerToken) : TokenRead()

    data object None : TokenRead()

    /** The store of record could not be read — never mistaken for no token. */
    data class Unavailable(val detail: String) : TokenRead()

    /** The token held, or `null` for none and for one that could not be read. */
    fun held(): BearerToken? = when (this) {
        is Held -> token
        None, is Unavailable -> null
    }
}
