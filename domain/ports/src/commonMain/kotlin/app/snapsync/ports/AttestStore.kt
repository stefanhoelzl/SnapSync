package app.snapsync.ports

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
     * Drop the stored token only if it is still [expected] — compare-and-clear (capability `privacy-security`,
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
