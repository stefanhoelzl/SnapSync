package app.snapsync.ports

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

/**
 * What the device's integrity service produced over a challenge (the `DeviceIntegrity` port): the [handle] of the
 * key that produced it — newly created for a first proof, the one passed in for a renewal — the [format] the
 * platform's proofs have, and the [bytes] the backend verifies. [chain] summarises the certificates a fresh proof
 * presents, where the platform exposes them (Android); `null` for a renewal, for App Attest and for the fakes.
 */
class Proof(val handle: String, val format: ProofFormat, val bytes: ByteArray, val chain: AttestationChain? = null)

/**
 * The certificates a fresh proof presented, summarised for the operator (what a report the app offered for a refused
 * phone carries) — enough to see which root a chain ends at: names kept verbatim (a remotely provisioned certificate is
 * named after its serial, and that is not redacted), but no certificate itself, and not the leaf, which is the app's
 * own key.
 *
 * [certificates] are those ABOVE the leaf, in the chain's order (the root last); [rootKeySha256] is the lowercase hex
 * SHA-256 of the root's encoded public key — what the backend pins a root by.
 */
data class AttestationChain(val certificates: List<CertificateFacts>, val rootKeySha256: String)

/** One certificate, as [AttestationChain] summarises it: names as RFC 2253, validity as ISO instants, key e.g. `EC 256`. */
data class CertificateFacts(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val key: String,
)

/**
 * Which kind of proof a platform produces — the verifier the backend's mint dispatches to (`proof.format` on the wire).
 * A renewal names none: the backend verifies it by what the device's attestation proved.
 */
enum class ProofFormat {
    /** App Attest: an attestation object when fresh, an assertion when renewing. */
    APP_ATTEST,

    /** Android Keystore key attestation: the key's certificate chain when fresh, an ECDSA signature when renewing. */
    ANDROID_KEY,
}
