package app.snapsync.fake

import app.snapsync.ports.AttestClient
import app.snapsync.ports.TokenOutcome
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
internal class InMemoryAttestKey(
    private val supported: Boolean = true,
) : AttestKey {

    /** Distinct per call, like the real Secure Enclave — a fresh key each time one is generated. */
    private var generated = 0
    private val keys = mutableSetOf<String>()

    override fun isSupported(): Boolean = supported

    // Refusals are exceptions, as the real service's are (`AttestKeyContract`): an unsupported process has no
    // App Attest to generate WITH, and no key but one this service generated can attest or assert.
    override suspend fun generateKey(): String {
        check(supported) { "App Attest generateKey failed: unsupported in this process" }
        return "in-memory-key-${++generated}".also { keys += it }
    }

    override suspend fun attest(keyId: String, challenge: String): ByteArray =
        "attestation:${known(keyId, "attestKey")}:$challenge".encodeToByteArray()

    override suspend fun assert(keyId: String, challenge: String): ByteArray =
        "assertion:${known(keyId, "generateAssertion")}:$challenge".encodeToByteArray()

    private fun known(keyId: String, step: String): String {
        check(supported) { "App Attest $step failed: unsupported in this process" }
        check(keyId in keys) { "App Attest $step failed: no such key $keyId" }
        return keyId
    }
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
 * attestation. A double that renewed happily would exercise the cheap path and never the recovery. `renews =
 * true` stands in for a device the backend holds an enrolment for — a state no contract host reaches, because
 * enrolling takes a genuine attestation.
 *
 * It mints only for what the real edge would accept in its place: an attestation of the shape
 * [InMemoryAttestKey] produces for that key, over the challenge THIS double issued (`AttestClientContract`'s
 * refusal clauses). [mints] = false stands in for an edge refusing even that — a genuine attestation it
 * declines.
 */
internal class InMemoryAttestClient(
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
    ): TokenOutcome {
        val genuine = challenge == challengeValue &&
            attestation.contentEquals("attestation:$keyId:$challenge".encodeToByteArray())
        return if (mints && genuine) TokenOutcome.Minted(token(deviceId)) else TokenOutcome.Refused
    }

    override suspend fun renewToken(
        deviceId: String,
        assertion: ByteArray,
        challenge: String,
    ): TokenOutcome = if (renews) TokenOutcome.Minted(token(deviceId)) else TokenOutcome.NotAttested

    private fun token(deviceId: String) = "$deviceId.$tokenExpiresAtEpochSeconds.in-memory-signature"
}
