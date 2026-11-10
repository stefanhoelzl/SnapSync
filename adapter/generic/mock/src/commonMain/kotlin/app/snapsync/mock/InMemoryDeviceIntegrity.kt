package app.snapsync.mock

import app.snapsync.domain.report.Platform
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Proof
import app.snapsync.ports.ProofFormat
import app.snapsync.ports.ProveResult
import kotlin.concurrent.Volatile

/**
 * The honest in-memory [DeviceIntegrity] — the device half of App Attest on an iPhone, or of Keystore key attestation
 * on an Android phone ([platform]), without the hardware.
 *
 * [available] is initial state rather than a constant because it is the one fact that differs between the hosts this
 * double stands in for: `true` in a device's app, **`false` on any simulator**, where App Attest does not exist at
 * all. A composition given `available = false` is refused every proof as [ProveResult.Refused.NotAvailable] — which
 * is exactly what a simulator's App Attest answers.
 *
 * Its proofs have a recognisable shape, which is what the in-memory backends verify in place of the vendor's
 * certificate chain, so they mint only for a proof this double really produced. A fresh key's proof carries
 * `attestation:<handle>:<challenge>` ([MockKeyAttestation.payload]) as its platform carries an attestation: on an
 * iPhone it IS the bytes, [ProofFormat.APP_ATTEST], with no chain — App Attest exposes none; on Android it is the
 * leaf of a certificate chain in concatenated DER ([MockKeyAttestation.chain]), [ProofFormat.ANDROID_KEY], whose
 * certificates above the leaf the proof summarises ([Proof.chain]). A renewal is `assertion:<handle>:<challenge>` on
 * both, with no chain.
 *
 * No call counters: a fake's public surface is its port contract plus a constructor taking initial state
 * (gate: `FakeHonestyTest`). A test that needs to observe calls wraps this, or asserts the outcome.
 */
internal class InMemoryDeviceIntegrity(
    private val available: Boolean = true,
    /** The keys the device's Secure Enclave holds — shared by every process face of one device. */
    private val keys: EnclaveKeys = EnclaveKeys(),
    /** The phone whose integrity service this is — what its proofs are. */
    private val platform: Platform = Platform.IOS,
    /** Handles of keys the enclave holds but will not sign with. */
    private val unusable: Set<String> = emptySet(),
    /** The vendor that vouches for a fresh key — not reachable from a device that is offline. */
    private val vendor: AttestationServer = AttestationServer(),
) : DeviceIntegrity {

    /** The platform's service, its call that attests a fresh key, its call that signs, and the format of its proofs. */
    private val service = when (platform) {
        Platform.IOS -> "App Attest"
        Platform.ANDROID -> "Keystore"
    }
    private val attest = if (platform == Platform.IOS) "$service attestKey" else "$service generateKey"
    private val sign = if (platform == Platform.IOS) "$service generateAssertion" else "$service sign"
    private val format = when (platform) {
        Platform.IOS -> ProofFormat.APP_ATTEST
        Platform.ANDROID -> ProofFormat.ANDROID_KEY
    }

    // Refusals are values, as the real service's are (`DeviceIntegrityContract`): an unavailable process has no
    // service to prove WITH, and no key but one this service created can sign.
    override suspend fun prove(challenge: String, handle: String?): ProveResult = when {
        !available -> ProveResult.Refused.NotAvailable("$service: unsupported in this process")
        handle != null && handle in unusable ->
            ProveResult.Refused.Failed("$sign: the secure hardware will not use $handle")
        handle == null && !vendor.reachable -> ProveResult.Refused.Failed("$attest: the server is unavailable")
        handle == null -> keys.create().let { key ->
            ProveResult.Ok(
                fresh(key, MockKeyAttestation.payload(key, challenge)),
            )
        }
        !keys.holds(handle) -> ProveResult.Refused.UnknownKey("$sign: no such key $handle")
        else -> ProveResult.Ok(Proof(handle, format, "assertion:$handle:$challenge".encodeToByteArray()))
    }

    private fun fresh(key: String, payload: String): Proof = when (platform) {
        Platform.IOS -> Proof(key, format, payload.encodeToByteArray())
        Platform.ANDROID -> Proof(key, format, MockKeyAttestation.chain(payload), MockKeyAttestation.summary)
    }
}

/**
 * The keys a device's Secure Enclave holds: distinct per creation, and there for every process of the device — each
 * on its own thread, so every read and write is [locked].
 */
/** The vendor's attestation server — Apple's App Attest service, Google's — as every process of one device reaches it. */
internal class AttestationServer(@Volatile var reachable: Boolean = true)

internal class EnclaveKeys {
    private val lock = mockLock()
    private var generated = 0
    private val held = mutableSetOf<String>()

    fun <T> locked(block: () -> T): T = lock.locked(block)

    fun create(): String = locked { "in-memory-key-${++generated}".also { held += it } }

    fun holds(handle: String): Boolean = locked { handle in held }

    /** The hardware is another phone's: it holds none of the keys this one made. */
    fun replaced() = locked { held.clear() }

    /** How many keys were ever generated, and the ones held — what the device keeps. */
    fun snapshot(): Pair<Int, List<String>> = locked { generated to held.toList() }

    fun restore(generated: Int, held: List<String>) = locked {
        this.generated = generated
        this.held.addAll(held)
    }
}
