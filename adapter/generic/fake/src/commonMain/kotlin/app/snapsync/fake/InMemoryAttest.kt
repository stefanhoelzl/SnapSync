package app.snapsync.fake

import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey

/**
 * The honest in-memory [AttestKey] — App Attest's device half, without a Secure Enclave.
 *
 * [supported] is initial state rather than a constant because it is the one fact that differs between the
 * processes and hosts this double stands in for: `true` in the app, **`false` in the upload extension**
 * (measured on device), and **`false` on any simulator**, where App Attest does not exist at all. A
 * composition given `supported = false` never attests and never renews — which is exactly what the
 * extension and the simulator do, and why `DeviceAttestation` has a branch for it.
 *
 * No call counters: a fake's public surface is its port contract plus a constructor taking initial state
 * (gate: `FakeHonestyTest`). A test that needs to observe calls wraps this, or asserts the outcome.
 */
class InMemoryAttestKey(
    private val supported: Boolean = true,
) : AttestKey {

    /** Distinct per call, like the real Secure Enclave — a fresh key each time one is generated. */
    private var generated = 0

    override fun isSupported(): Boolean = supported

    override suspend fun generateKey(): String = "in-memory-key-${++generated}"

    override suspend fun attest(keyId: String, challenge: String): ByteArray =
        "attestation:$keyId:$challenge".encodeToByteArray()

    override suspend fun assert(keyId: String, challenge: String): ByteArray =
        "assertion:$keyId:$challenge".encodeToByteArray()
}

/**
 * The honest in-memory [AttestClient] — the backend's three ungated `/attest/…` routes.
 *
 * The token it mints is **well-formed**, `<deviceId>.<expiry>.<signature>`, because that shape is not
 * cosmetic: the device reads its own expiry straight out of the token (it is signed, not encrypted), and
 * `DeviceAttestation.isStale` / `isUnusable` are decisions taken on that parse. A double returning an
 * opaque string would make every token unreadable and therefore permanently unusable, which is a state no
 * real backend produces.
 *
 * [renews] defaults to **false**, and that is the faithful default rather than a pessimistic one: the case
 * worth standing in for is a backend that holds no attestation record for this device — after a restore,
 * or after the nightly sweep collected it — which refuses the renewal and sends the device down a full
 * attestation. A double that renewed happily would exercise the cheap path and never the recovery.
 */
class InMemoryAttestClient(
    private val challengeValue: String? = "in-memory-challenge",
    /** Epoch seconds the minted token expires at. The default outlives any test's pinned clock. */
    private val tokenExpiresAtEpochSeconds: Long = 90L * 24 * 60 * 60,
    private val mints: Boolean = true,
    private val renews: Boolean = false,
) : AttestClient {

    override suspend fun challenge(): String? = challengeValue

    override suspend fun mintToken(
        deviceId: String,
        keyId: String,
        attestation: ByteArray,
        challenge: String,
    ): String? = if (mints) token(deviceId) else null

    override suspend fun renewToken(
        deviceId: String,
        assertion: ByteArray,
        challenge: String,
    ): String? = if (renews) token(deviceId) else null

    private fun token(deviceId: String) = "$deviceId.$tokenExpiresAtEpochSeconds.in-memory-signature"
}
