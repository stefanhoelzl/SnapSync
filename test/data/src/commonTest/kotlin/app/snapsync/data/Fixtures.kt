package app.snapsync.data

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendTarget
import app.snapsync.data.backend.TokenRead
import app.snapsync.data.backend.VersionIgnored
import app.snapsync.data.backend.VersionObserver
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.data.trust.AttestationIssuer
import app.snapsync.data.trust.AttestationStatus
import app.snapsync.data.trust.AttestingCredential
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.data.trust.ClearRead
import app.snapsync.data.trust.CredentialStore
import app.snapsync.data.trust.KeyIdRead
import app.snapsync.data.trust.SecureCredentialStore
import app.snapsync.data.trust.TokenText
import app.snapsync.domain.trust.AttestKeyId
import app.snapsync.domain.trust.BearerToken
import app.snapsync.domain.trust.TokenIssuance
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.ports.AttestationChain
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Proof
import app.snapsync.ports.ProofFormat
import app.snapsync.ports.ProveResult
import app.snapsync.ports.SecureStore
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testDeviceId
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

internal const val DEVICE = "11111111-0000-4000-8000-000000000002"
internal const val DAY_SECONDS = 24 * 60 * 60L
internal const val NOW_SECONDS = 1_800_000_000L

/** A token in the backend's format: `<deviceId>.<expiry>.<signature>` — signed, so the device reads its own expiry. */
internal fun token(expiresInDays: Long) = "$DEVICE.${NOW_SECONDS + expiresInDays * DAY_SECONDS}.sig"

internal fun bearer(text: String): BearerToken = TokenText.parse(text)

/** The text of the token [this] holds, or `null`. */
internal fun TokenRead.text(): String? = held()?.secret

/** The platform's integrity service, scripted: App Attest's proofs, a chain where one is given. */
internal class FakeKey(
    val supported: Boolean = true,
    var attestRefused: Boolean = false,
    var assertRefused: Boolean = false,
    val chain: AttestationChain? = null,
) : DeviceIntegrity {
    var generated = 0
    var attested = 0
    var asserted = 0

    override suspend fun prove(challenge: String, handle: String?): ProveResult =
        if (handle != null) assert(handle) else attest()

    private fun assert(handle: String): ProveResult {
        asserted++
        return when {
            !supported -> ProveResult.Refused.NotAvailable("no App Attest in this process")
            assertRefused -> ProveResult.Refused.UnknownKey("the Secure Enclave key is gone")
            else -> ProveResult.Ok(Proof(handle, ProofFormat.APP_ATTEST, byteArrayOf(4, 5, 6)))
        }
    }

    private fun attest(): ProveResult {
        attested++
        return when {
            !supported -> ProveResult.Refused.NotAvailable("no App Attest in this process")
            attestRefused -> ProveResult.Refused.Failed("Apple said no").also { generated++ }
            else -> ProveResult.Ok(Proof("key-${++generated}", ProofFormat.APP_ATTEST, byteArrayOf(1, 2, 3), chain))
        }
    }
}

/** The shared secure store both processes read, holding [token] and [keyId] — and the store of record over it. */
internal class Held(token: String? = null, keyId: String? = null) {
    val secure: SecureStore = inMemorySecureStore()

    /** The store of record, as the OTHER process reaches the same item. */
    val record = SecureCredentialStore(secure)

    /** This process's copy in front of it. */
    val store = CachedCredentialStore(record)

    init {
        keyId?.let { record.setKeyId(AttestKeyId(it)) }
        token?.let { record.setToken(bearer(it)) }
    }

    fun token(): String? = record.token().text()

    fun keyId(): String? = (record.keyId() as? KeyIdRead.Held)?.keyId?.value

    /** The other process drops the token from the shared item. */
    fun dropElsewhere() = record.clearTokenIf(record.token().held()!!)
}

/** A [CredentialStore] whose answers a test overrides, over [store]. */
internal open class ScriptedStore(private val store: CredentialStore) : CredentialStore by store

/** The backend's three `/attest/…` routes, scripted in trust's own vocabulary; every other route is unused. */
internal class FakeClient(
    var challenge: String? = "chal",
    var mint: String? = token(30),
    var renew: String? = token(30),
) : HttpTransport {
    var mintCalls = 0
    var renewCalls = 0
    var challengeCalls = 0

    /** Answers served before [mint]/[renew] apply, one per call — for the cases a bare token or refusal cannot say. */
    val mintAnswers = ArrayDeque<TokenIssuance>()
    val renewAnswers = ArrayDeque<TokenIssuance>()

    /** An answer that replaces the scripted one on its route — what trust's vocabulary cannot say. */
    var challengeAnswer: HttpResult? = null
    var renewAnswer: HttpResult? = null

    /** When set, the challenge suspends on it, so a test can observe a refresh while it is in flight. */
    var challengeGate: CompletableDeferred<Unit>? = null

    override suspend fun send(request: HttpRequest): HttpResult {
        val path = request.url.removePrefix(BASE)
        return when (path) {
            Routes.Challenge.TEMPLATE -> {
                challengeGate?.await()
                challengeCalls++
                challengeAnswer ?: challenge?.let { json(200, """{"challenge":"$it"}""") }
                    ?: HttpResult.Unreachable("no challenge")
            }
            Routes.MintToken.TEMPLATE -> {
                mintCalls++
                reply(mintAnswers.removeFirstOrNull() ?: minted(mint), renewal = false)
            }
            Routes.RenewToken.TEMPLATE -> {
                renewCalls++
                renewAnswer ?: reply(renewAnswers.removeFirstOrNull() ?: minted(renew), renewal = true)
            }
            else -> error("attestation reaches only the /attest/… routes, not $path")
        }
    }

    /** The exchange the credential reaches the issuers through, reporting to [versions]. */
    fun exchange(versions: VersionObserver = VersionIgnored) = BackendExchange(this, Target, versions)

    private fun minted(text: String?): TokenIssuance = text?.let { TokenIssuance.Minted(bearer(it)) } ?: REFUSED

    /** What the backend answers for [outcome] — the statuses and bodies the issuer translates. */
    private fun reply(outcome: TokenIssuance, renewal: Boolean): HttpResult = when (outcome) {
        is TokenIssuance.Minted -> json(201, """{"token":"${outcome.token.secret}"}""")
        TokenIssuance.ChallengeStale -> text(409, "stale challenge")
        TokenIssuance.NotAttested -> text(401, "not attested")
        is TokenIssuance.Refused -> if (renewal) {
            text(401, "assertion rejected")
        } else {
            text(401, "attestation rejected: ${outcome.reason.wireName}" + (outcome.detail?.let { " ($it)" } ?: ""))
        }
        TokenIssuance.ProofFailed -> error("a local proof failure is the device's, never the backend's answer")
        is TokenIssuance.NoAnswer -> HttpResult.Unreachable("offline")
    }

    private fun json(status: Int, body: String) = HttpResult.Response(
        status,
        mapOf("Content-Type" to "application/json"),
        body,
    )

    private fun text(status: Int, body: String) = HttpResult.Response(
        status,
        mapOf("Content-Type" to "text/plain"),
        body,
    )

    private object Target : BackendTarget {
        override val base = BASE
        override val declaredVersion = "1.0"
    }

    companion object {
        const val BASE = "https://backend.test/api/v2"

        /** The backend's verdict when a test scripts no token: the default reason, as v2 names it. */
        val REFUSED = TokenIssuance.Refused(app.snapsync.domain.trust.DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null)
    }
}

/** The app's credential under test over [key], [client] and [store], with the fixture device's identity and its own status. */
@OptIn(AppProcessOnly::class)
internal class Subject(
    val key: FakeKey = FakeKey(),
    val client: FakeClient = FakeClient(),
    val held: Held = Held(),
    store: CredentialStore = held.store,
    versions: VersionObserver = VersionIgnored,
    identity: DeviceIdentity = identityOf(DEVICE),
    integrity: DeviceIntegrity = key,
) {
    val status = AttestationStatus()
    val credential = AttestingCredential(
        store = store as? CachedCredentialStore ?: CachedCredentialStore(store),
        issuer = AttestationIssuer(integrity, client.exchange(versions)),
        identity = identity,
        clock = fixedClock(Instant.fromEpochSeconds(NOW_SECONDS)),
        status = status,
    )
}

/** The app's device identity resolving to the fixture device [id] stands for: minted over an empty secure store. */
@OptIn(AppProcessOnly::class)
internal fun identityOf(id: String) =
    MintingDeviceIdentity(inMemorySecureStore(), PlatformDeviceId { testDeviceId(id).value })

/** A device identity behind a locked secure store. */
@OptIn(AppProcessOnly::class)
internal fun unreadableIdentity() =
    MintingDeviceIdentity(inMemorySecureStore(unavailable = true), PlatformDeviceId { null })

/** A store whose compare-and-clear cannot be made — the shared item unreadable at that moment. */
internal fun uncomparable(store: CredentialStore) = object : ScriptedStore(store) {
    override fun clearTokenIf(expected: BearerToken): ClearRead = ClearRead.Unavailable("keychain locked")
}

/** A store whose token cannot be read. */
internal fun unreadable(store: CredentialStore) = object : ScriptedStore(store) {
    override fun token(): TokenRead = TokenRead.Unavailable("keychain locked")
}

/** A store that refuses to keep a key id, or a token. */
internal fun refusingKeyId(store: CredentialStore) = object : ScriptedStore(store) {
    override fun setKeyId(keyId: AttestKeyId): WriteOutcome = WriteOutcome.Failed("keychain locked")
}

internal fun refusingToken(store: CredentialStore) = object : ScriptedStore(store) {
    override fun setToken(token: BearerToken): WriteOutcome = WriteOutcome.Failed("keychain locked")
}

/** A store whose key id cannot be read. */
internal fun unreadableKeyId(store: CredentialStore) = object : ScriptedStore(store) {
    override fun keyId(): KeyIdRead = KeyIdRead.Unavailable("keychain locked")
}

/** An Android device's Keystore, whose fresh proof is a chain that is not whole DER. */
internal class BrokenChainKey : DeviceIntegrity {
    override suspend fun prove(challenge: String, handle: String?): ProveResult =
        ProveResult.Ok(Proof("android-key", ProofFormat.ANDROID_KEY, byteArrayOf(0x30)))
}
