package app.snapsync.ports

import app.snapsync.model.Proof

/**
 * The platform's device-integrity service (capability `privacy-security`) — on iOS, App Attest. The port only
 * PROVES: what a proof is for, when to renew, and where the resulting token and key handle are kept are the
 * attestation service's (`DeviceAttestation`, over `AttestState`).
 *
 * **[isAvailable] is not ceremony.** It is `false` inside the upload extension and `true` in the app — measured on
 * device (SE2, iOS 26.5.2), not assumed. That single fact shapes the capability: the extension can never attest or
 * renew, so it is strictly a *reader* of whatever token the app left in the shared store.
 *
 * The challenge crosses as a **string**; the implementation hashes it itself, which keeps `commonMain` free of
 * crypto (iOS hashes with CommonCrypto).
 *
 * Its promises are the port contract `DeviceIntegrityContract`, recorded on a device (`docs/testing.md`).
 */
interface DeviceIntegrity {

    /** Whether this process can produce a proof at all. False in an app extension. */
    fun isAvailable(): Boolean

    /**
     * A proof over [challenge]. With no [handle], a **fresh** key is created and attested — the platform's
     * throttled path, talking to the vendor over the network — and the proof names the new key's handle. With a
     * [handle], that key signs [challenge]: local work, no network, no throttle.
     *
     * Throws when no proof can be produced — an unavailable service, an unknown handle, a refused attestation. A
     * refusal is an exception, never a hang and never an invented proof.
     */
    suspend fun prove(challenge: String, handle: String? = null): Proof
}
