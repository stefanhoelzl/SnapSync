package app.snapsync.ports

/**
 * The App Attest key seam (capability `device-attestation`) — the platform half of attestation, kept
 * behind an interface so the whole policy in [DeviceAttestation] is testable on the JVM and the simulator.
 *
 * **[isSupported] is not ceremony.** It is `false` inside the upload extension and `true` in the app —
 * measured on device (SE2, iOS 26.5.2), not assumed. That single fact shapes the capability: the
 * extension can never attest or renew, so it is strictly a *reader* of whatever token the app left in the
 * shared Keychain, and the token's lifetime has to be long enough to survive iOS starving the app's
 * background wakes.
 *
 * The seam takes the challenge as a **string** and hashes it itself: SHA-256 has no multiplatform stdlib,
 * and pushing it to the platform keeps `commonMain` free of crypto. iOS hashes with CommonCrypto.
 */
interface AttestKey {

    /** Whether App Attest works in THIS process. False in an app extension. */
    fun isSupported(): Boolean

    /** Create a fresh Secure-Enclave key, returning its `keyId`. */
    suspend fun generateKey(): String

    /**
     * Attest [keyId] against [challenge]. Talks to Apple over the network.
     *
     * Apple attests a key **once**. Re-attesting — or minting a fresh key per renewal — is the throttled
     * path, which is exactly why renewal goes through [assert] instead.
     */
    suspend fun attest(keyId: String, challenge: String): ByteArray

    /** Sign [challenge] with the attested [keyId]. Local Secure-Enclave work: no network, no throttle. */
    suspend fun assert(keyId: String, challenge: String): ByteArray
}

/** The backend half: the three ungated `/attest/…` routes. */
interface AttestClient {

    /**
     * `GET /attest/challenge` → the server-issued nonce, or null on any failure.
     *
     * Absence: null covers every cause — refusal, transport failure, a malformed body — and all of
     * them mean the same thing to the only caller: no attestation this wake, so the device stays on
     * its existing token and the next wake retries. A 401 is the visible, retryable consequence.
     */
    suspend fun challenge(): String?

    /** `POST /attest/token` → a fresh device token for a new attestation, or why there is none ([TokenOutcome]). */
    suspend fun mintToken(
        deviceId: String,
        keyId: String,
        attestation: ByteArray,
        challenge: String,
    ): TokenOutcome

    /** `POST /attest/renew` → a fresh device token from an assertion, or why there is none ([TokenOutcome]). */
    suspend fun renewToken(deviceId: String, assertion: ByteArray, challenge: String): TokenOutcome
}

/**
 * What an `/attest/token` or `/attest/renew` call came to, classified by the adapter that owns the route (capability
 * `device-attestation`, "Only a rejected credential is invalidated, and only that one"; decision record
 * `harden-seam-bug-classes`, D10).
 *
 * It used to be `String?`, and every `null` got one answer — attest afresh — whatever the cause. Each case below
 * needs a different one: a stale challenge wants one fresh challenge, not a new key; an unreachable backend wants
 * the next wake, not Apple's throttled path; and none of them is a reason to drop the token the device holds.
 */
sealed interface TokenOutcome {
    /** The backend minted [token]. */
    data class Minted(val token: String) : TokenOutcome

    /** The challenge expired before the backend verified it (v2's `409 stale challenge`). One fresh challenge may succeed. */
    data object ChallengeStale : TokenOutcome

    /** The backend holds no attestation for this device (`401 not attested`): renewing cannot work, attesting can. */
    data object NotAttested : TokenOutcome

    /** The backend verified and declined — the attestation or assertion itself was rejected, or the body was invalid. */
    data object Refused : TokenOutcome

    /** No answer: transport failure, a `5xx`, or a body that names no token. The next wake retries. */
    data object Unreachable : TokenOutcome
}

/**
 * Where the device token and its `keyId` live: a store that outlives the app install and stays
 * readable while the device is locked, addressable by **both** processes — the upload extension must
 * read the token, and the OS invokes it when the device is idle, which usually means locked.
 *
 * Binding note: on iOS both are [SecureStore] items in the **shared Keychain access group**, the same
 * one holding the device id.
 */
interface AttestStore {

    /**
     * The current token, or null if none was ever stored. MAY be expired — the reader decides.
     *
     * Absence: null means the token is **absent** and nothing else. An unreadable store is NOT
     * collapsed here — [readExisting] throws [SecureStoreUnavailable] instead, and [SecureStoreRead]
     * keeps `Absent` and `Unavailable` apart on purpose ("never mistaken for absence"). That
     * separation is what lets a caller treat null as "not attested yet" and mint.
     */
    fun token(): String?

    fun setToken(token: String)

    /**
     * The attested `keyId`, or null if this install has never attested.
     *
     * Absence: as [token] — absent only; unreadable throws. The distinction matters more here than
     * anywhere: minting on a forged "absent" would burn a fresh Secure-Enclave attestation.
     */
    fun keyId(): String?

    fun setKeyId(keyId: String)

    /**
     * Drop the stored token (the `keyId` is KEPT — the Secure-Enclave key is still good, so the next
     * refresh can renew with a cheap assertion instead of a full re-attestation).
     *
     * Called when the backend REJECTS the token, which is a different thing from the token being expired
     * and must not be confused with it: a rejected token can still be nowhere near its expiry — after the
     * signing key is rotated, or after the leave cascade collects this device's attestation record. Without
     * this, `isStale()` would keep reporting a rejected-but-unexpired token as perfectly fine, the app would
     * never renew, and the device would 401 forever behind a screen that said "Syncing".
     */
    fun clearToken()

    /**
     * Drop the stored token only if it is still [expected] — compare-and-clear (capability `device-attestation`,
     * "Only a rejected credential is invalidated, and only that one"). Returns whether it cleared.
     *
     * A rejection names the token the refused request CARRIED. Several requests carrying T1 can be refused after a
     * renewal has already stored T2, in this process or in the other one (both read the one shared item); a plain
     * [clearToken] from any of them would erase T2 and send the device round the recovery loop again (B3). Here the
     * late rejections find T2, clear nothing, and answer `false`.
     *
     * Not atomic ACROSS processes: an extension rejection landing between the app's read and delete can still
     * interleave. That window is microseconds wide, and its outcome is one extra refresh, never a lost photo
     * (decision record `harden-seam-bug-classes`, D10). Within a process, the caller serialises it against writes.
     */
    fun clearTokenIf(expected: String): Boolean = if (token() == expected) {
        clearToken()
        true
    } else {
        false
    }
}
