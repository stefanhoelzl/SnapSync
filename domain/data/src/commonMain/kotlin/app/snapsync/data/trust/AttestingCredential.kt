package app.snapsync.data.trust

import app.snapsync.data.backend.Credential
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.time.Timestamp
import app.snapsync.domain.trust.AttestationHealthVerdict
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.trust.MintAnswerVerdict
import app.snapsync.domain.trust.RenewalAnswerVerdict
import app.snapsync.domain.trust.RenewalVerdict
import app.snapsync.domain.trust.TokenIssuance
import app.snapsync.domain.trust.UsabilityVerdict
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.Clock
import app.snapsync.ports.WriteOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's credential: it obtains the bearer token every gated request carries, keeps it alive, and recovers when the
 * backend rejects it. Only the app process constructs one — the upload extension cannot attest (App Attest is
 * unavailable there, measured), so it holds a [DropOnlyCredential].
 *
 * A refresh starts from the store of record ([CachedCredentialStore.reread]: the extension shares the item and may have
 * cleared it) and spends a renewal only when one is due ([RenewalVerdict]): an assertion by the key it attested with —
 * cheap, no vendor round-trip — and only when that yields no token, a full attestation with a new key
 * ([RenewalAnswerVerdict]). Refreshes are SERIALIZED: a rejected token `401`s every request in flight, and each asking
 * for a refresh of its own would aim a thundering herd at the vendor's throttled path.
 *
 * Its verdicts publish into [status]; nothing here throws — what cannot be read, written or proved is a refresh that
 * obtained nothing, which the next wake retries.
 */
class AttestingCredential @AppProcessOnly constructor(
    private val store: CachedCredentialStore,
    private val issuer: AttestationIssuer,
    private val identity: DeviceIdentity,
    private val clock: Clock,
    private val status: AttestationStatus,
) : Credential {
    private val refreshing = Mutex()
    private val log = Log("AttestingCredential")
    private val obtainer = TokenObtainer(store, issuer, identity, status)

    /** What every request reads: the in-memory copy, re-read from the store at every refresh and rejection. */
    override fun token(): TokenRead = store.token()

    /**
     * The backend rejected [sent]: drop it if it is still held — compare-and-clear, under the refresh lock, so an
     * in-flight refresh's new token is never what a late rejection erases — then refresh now, and answer what is held.
     * The refresh runs whether or not THIS rejection cleared the token: a burst of calls is refused together, only the
     * first clears, and every one retries with what the refresh obtained.
     */
    override suspend fun rejected(sent: BearerToken): TokenRead {
        refreshing.withLock { dropped(store.clearTokenIf(sent)) }
        refresh()
        return store.token()
    }

    /** No token held and a request that must not go out without one: refresh once, and answer what it obtained. */
    override suspend fun missing(): TokenRead {
        refresh()
        return store.token()
    }

    /**
     * Refresh the token if it is due, and publish what that says. Called at every wake (the wake's own use case).
     * The clear on entry is load-bearing: it sits OUTSIDE the lock, so a caller queued behind a slow refresh clears
     * the stale verdict when it arrives, not when it finally acquires the lock.
     */
    suspend fun refresh(): RefreshRead {
        val ticket = status.attempting()
        val attempt = refreshing.withLock { refreshLocked() }
        val usable = UsabilityVerdict.of(store.token().held(), now())
        status.settled(ticket, AttestationHealthVerdict.of(attempt.obtained(), usable), attempt.refusal())
        return attempt
    }

    private suspend fun refreshLocked(): RefreshRead {
        store.reread()
        return when (RenewalVerdict.of(store.token().held(), now())) {
            RenewalVerdict.Fresh -> RefreshRead.Fresh
            RenewalVerdict.Due -> obtainer.obtained()
        }
    }

    private suspend fun dropped(cleared: ClearRead) = when (cleared) {
        ClearRead.Cleared -> log.w("the backend rejected our token — dropped it, so a new one is obtained now")
        ClearRead.NotHeld -> log.i("a rejection of a token no longer held — already replaced or dropped")
        is ClearRead.Unavailable -> log.w(
            "the rejected token could not be compared and cleared",
            "detail" of cleared.detail,
        )
    }

    private fun now(): Timestamp = Timestamp.of(clock.now())
}

/**
 * How a due token is replaced: renewed with an assertion by the key the device attested with — cheap, no vendor
 * round-trip — and only when that yields no token, attested afresh with a new key ([RenewalAnswerVerdict]). The key id
 * is kept only once the backend accepted its attestation, and the token only once it could be kept.
 */
internal class TokenObtainer(
    private val store: CredentialStore,
    private val issuer: AttestationIssuer,
    private val identity: DeviceIdentity,
    private val status: AttestationStatus,
) {
    private val log = Log("AttestingCredential")

    /** The identity resolved on its own first: a store that cannot be read is no reason to attest afresh. */
    suspend fun obtained(): RefreshRead = when (val id = identity.id()) {
        is DeviceIdRead.Ok -> renewedOrMinted(id.value)
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> nothing(
            "the device identity is unreadable — keeping the token",
        )
    }

    /** Renew with an assertion when this install has attested; only a device that never did pays for an attestation. */
    private suspend fun renewedOrMinted(device: DeviceId): RefreshRead = when (val key = store.keyId()) {
        is KeyIdRead.Held -> afterRenewal(device, issuer.renew(device, key.keyId))
        KeyIdRead.None -> minted(device)
        is KeyIdRead.Unavailable -> nothing("the key id is unreadable — keeping the token")
    }

    private suspend fun afterRenewal(device: DeviceId, answer: TokenIssuance): RefreshRead =
        when (val verdict = RenewalAnswerVerdict.of(answer)) {
            is RenewalAnswerVerdict.Accept -> accepted(verdict.token, "token renewed")
            RenewalAnswerVerdict.KeepHeld -> nothing("renewal got no answer — keeping the token for the next wake")
            RenewalAnswerVerdict.AttestAfresh -> minted(device)
        }

    private suspend fun minted(device: DeviceId): RefreshRead = when (val minting = issuer.mint(device)) {
        is Minting.Proved -> afterMint(minting)
        is Minting.Unproved -> nothing("attestation did not yield a token")
    }

    private suspend fun afterMint(minting: Minting.Proved): RefreshRead =
        when (val verdict = MintAnswerVerdict.of(minting.issuance)) {
            is MintAnswerVerdict.Accept -> keyKept(minting.key, verdict.token)
            is MintAnswerVerdict.Refused -> refused(verdict, minting.key)
            MintAnswerVerdict.NoVerdict -> nothing("attestation did not yield a token")
        }

    /**
     * The key id is kept only once the backend accepted its attestation; one that could not be kept refuses the
     * token.
     */
    private suspend fun keyKept(key: ProvedKey, token: BearerToken): RefreshRead = when (store.setKeyId(key.keyId)) {
        WriteOutcome.Ok -> accepted(token, "attested and minted a fresh token")
        is WriteOutcome.Failed -> nothing("the attested key id could not be kept — not accepting the token")
    }

    private suspend fun refused(verdict: MintAnswerVerdict.Refused, key: ProvedKey): RefreshRead {
        status.refused(verdict.reason, verdict.detail, key.chain)
        log.w("the service refused this device's attestation", "reason" of verdict.reason.wireName)
        return RefreshRead.NotObtained(verdict.reason)
    }

    private suspend fun accepted(token: BearerToken, what: String): RefreshRead = when (store.setToken(token)) {
        WriteOutcome.Ok -> RefreshRead.Obtained.also {
            status.accepted()
            log.i(what)
        }
        is WriteOutcome.Failed -> nothing("the new token could not be kept")
    }

    private suspend fun nothing(why: String): RefreshRead = RefreshRead.NotObtained(refusal = null).also { log.w(why) }
}

/** What one refresh came to. */
sealed class RefreshRead {
    /** The token held needs no renewal. */
    data object Fresh : RefreshRead()

    /** A new token was obtained and kept. */
    data object Obtained : RefreshRead()

    /** No new token; [refusal] when the service refused this phone — told — and `null` when there was no verdict. */
    data class NotObtained(val refusal: DeviceRefusal?) : RefreshRead()

    /** Whether a usable token was obtained or already held fresh. */
    fun obtained(): Boolean = this !is NotObtained

    /** The service's refusal, if this refresh got one. */
    fun refusal(): DeviceRefusal? = (this as? NotObtained)?.refusal
}
