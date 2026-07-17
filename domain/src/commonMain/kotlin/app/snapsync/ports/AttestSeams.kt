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

    /** `GET /attest/challenge` → the server-issued nonce, or null on any failure. */
    suspend fun challenge(): String?

    /** `POST /attest/token` → a fresh device token, or null if the backend refused. */
    suspend fun mintToken(
        deviceId: String,
        keyId: String,
        attestation: ByteArray,
        challenge: String,
    ): String?

    /** `POST /attest/renew` → a fresh device token from an assertion, or null if the backend refused. */
    suspend fun renewToken(deviceId: String, assertion: ByteArray, challenge: String): String?
}

/**
 * Where the device token and its `keyId` live.
 *
 * On iOS this is the **shared Keychain access group** — the same one holding the device id — because the
 * upload extension must read the token, and it must be able to do so on a **locked** device (the OS
 * invokes the extension when the device is idle, which usually means locked).
 */
interface AttestStore {

    /** The current token, or null if none was ever stored. MAY be expired — the reader decides. */
    fun token(): String?

    fun setToken(token: String)

    /** The attested `keyId`, or null if this install has never attested. */
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
}
