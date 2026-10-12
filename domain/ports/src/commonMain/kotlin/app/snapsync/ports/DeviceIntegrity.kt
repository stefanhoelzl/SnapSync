package app.snapsync.ports

import app.snapsync.model.Proof

/**
 * The platform's device-integrity service — App Attest on iOS, Keystore key attestation on Android. The port only
 * PROVES: what a proof is for, when to renew, and where the resulting token and key handle are kept are the
 * attestation service's (`DeviceAttestation`, over `AttestState`).
 *
 * Only the APP process holds one (`AppDevicePorts`). The upload extension has none — App Attest is unavailable there,
 * measured on device (SE2, iOS 26.5.2) — so it only reads the token the app stored.
 *
 * The challenge crosses as a **string**; the implementation hashes it itself, which keeps `commonMain` free of
 * crypto (iOS hashes with CommonCrypto).
 *
 * Its promises are the port contract `DeviceIntegrityContract`, recorded on a device (`docs/testing.md`).
 */
interface DeviceIntegrity : Port {

    /**
     * A proof over [challenge]. With no [handle], a **fresh** key is created and attested — the platform's
     * throttled path, talking to the vendor over the network — and the proof names the new key's handle. With a
     * [handle], that key signs [challenge]: local work, no network, no throttle.
     *
     * Never throws, never hangs, never invents a proof: every way the platform fails to produce one is a
     * [ProveResult.Refused] — a process without the service answers [ProveResult.Refused.NotAvailable], locally.
     */
    suspend fun prove(challenge: String, handle: String? = null): ProveResult
}

/** What [DeviceIntegrity.prove] answered. */
sealed interface ProveResult {

    /** The platform produced [proof]. */
    data class Ok(val proof: Proof) : ProveResult

    /** No proof. A caller that needs only "no proof" matches this; the leaves name why, for the log. */
    sealed interface Refused : ProveResult {

        /** The platform's own error, in the adapter's words. Names a key handle at most — never key material. */
        val reason: String

        /** This process has no integrity service — App Attest is unsupported (a simulator). Answered locally. */
        data class NotAvailable(override val reason: String) : Refused

        /** The handle names no key the platform holds. */
        data class UnknownKey(override val reason: String) : Refused

        /** Any other refusal the platform answered with. */
        data class Failed(override val reason: String) : Refused
    }
}
