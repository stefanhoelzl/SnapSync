package app.snapsync.mock

import app.snapsync.ports.AttestationChain
import app.snapsync.ports.CertificateFacts
import app.snapsync.wire.models.AndroidKeyProof
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.Proof

/**
 * The Android Keystore key attestation the in-memory integrity presents for a fresh key on an Android phone, and the
 * backend mock verifies: a certificate chain, leaf first, crossing the integrity port as concatenated DER — what the
 * app's own translation (`DerChain`) splits into the certificates a mint sends.
 *
 * Each "certificate" is a minimal well-formed DER element — a SEQUENCE holding one UTF8String — so the chain is whole
 * DER to the app, with no X.509 a mock would only imitate. The leaf's string is the mock's proof payload
 * ([payload], `attestation:<key>:<challenge>`), what the backend mock checks in place of Google's chain; the two above
 * it name the mock's intermediate and root, which [summary] describes.
 */
internal object MockKeyAttestation {

    /** What a fresh proof by [key] over [challenge] says — on either platform, the one thing the backend mock reads. */
    fun payload(key: String, challenge: String): String = "$ATTESTATION$key:$challenge"

    /**
     * The key [proof] attests over [challenge], where the in-memory integrity produced it: App Attest's names its key
     * and IS the payload; an Android key attestation's leaf certificate carries the payload, which names the key.
     */
    fun attestedKey(proof: Proof, challenge: String): String? = when (proof) {
        is AppleAppAttestProof -> proof.keyId.takeIf {
            base64OrNull(proof.attestation)?.contentEquals(payload(proof.keyId, challenge).encodeToByteArray()) == true
        }
        is AndroidKeyProof -> proof.chain.firstOrNull()?.let(::base64OrNull)?.let(::textOf)
            ?.let { text -> keyOf(text, challenge) }
    }

    /** The key [payload] names, where it is a fresh proof's payload over [challenge]; `null` for anything else. */
    fun keyOf(payload: String, challenge: String): String? =
        payload.removePrefix(ATTESTATION).removeSuffix(":$challenge").takeIf { key ->
            key.isNotEmpty() && payload(key, challenge) == payload
        }

    /** A fresh proof's chain over [payload]: leaf, intermediate, root, as concatenated DER. */
    fun chain(payload: String): ByteArray = certificate(payload) + certificate(INTERMEDIATE) + certificate(ROOT)

    /** The text [certificate] holds, or `null` for bytes that are not one of this attestation's certificates. */
    fun textOf(certificate: ByteArray): String? = element(certificate, 0, SEQUENCE)?.let { (start, end) ->
        if (end != certificate.size) return null
        element(certificate, start, UTF8_STRING)?.takeIf { (_, inner) -> inner == end }
            ?.let { (textStart, textEnd) -> certificate.copyOfRange(textStart, textEnd).decodeToString() }
    }

    /**
     * The certificates above the leaf, as the app summarises a chain it presented ([AttestationChain]) — fixed, so a
     * report offered for a refused phone reads the same every run.
     */
    val summary: AttestationChain = AttestationChain(
        certificates = listOf(
            CertificateFacts(
                subject = INTERMEDIATE,
                issuer = ROOT,
                notBefore = "2026-01-01T00:00:00Z",
                notAfter = "2036-01-01T00:00:00Z",
                key = "EC 256",
            ),
            CertificateFacts(
                ROOT,
                ROOT,
                notBefore = "2026-01-01T00:00:00Z",
                notAfter = "2046-01-01T00:00:00Z",
                key = "EC 384",
            ),
        ),
        rootKeySha256 = "0".repeat(SHA256_HEX_LENGTH),
    )

    private fun certificate(text: String): ByteArray = der(SEQUENCE, der(UTF8_STRING, text.encodeToByteArray()))

    /** One DER element: [tag], its length (short form, or long form in one or two bytes), [contents]. */
    private fun der(tag: Int, contents: ByteArray): ByteArray {
        val size = contents.size
        val length = when {
            size < LONG_FORM -> byteArrayOf(size.toByte())
            size <= BYTE -> byteArrayOf((LONG_FORM or 1).toByte(), size.toByte())
            else -> byteArrayOf((LONG_FORM or 2).toByte(), (size shr BITS).toByte(), size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + contents
    }

    /** Where the contents of the element at [at] tagged [tag] start and end, or `null` for any other element. */
    private fun element(bytes: ByteArray, at: Int, tag: Int): Pair<Int, Int>? {
        if (bytes.getOrNull(at)?.toInt()?.and(BYTE) != tag) return null
        val first = bytes.getOrNull(at + 1)?.toInt()?.and(BYTE) ?: return null
        val (start, size) = when {
            first < LONG_FORM -> at + 2 to first
            first == (LONG_FORM or 1) -> at + 3 to (bytes.getOrNull(at + 2)?.toInt()?.and(BYTE) ?: return null)
            first == (LONG_FORM or 2) ->
                at + 4 to
                    (
                        ((bytes.getOrNull(at + 2) ?: return null).toInt() and BYTE) shl BITS or
                            ((bytes.getOrNull(at + 3) ?: return null).toInt() and BYTE)
                        )
            else -> return null
        }
        return (start to start + size).takeIf { (_, end) -> end <= bytes.size }
    }

    private const val ATTESTATION = "attestation:"
    private const val ROOT = "CN=Mock Root,O=SnapSync Mock"
    private const val INTERMEDIATE = "CN=Mock Attestation,O=SnapSync Mock"
    private const val SEQUENCE = 0x30
    private const val UTF8_STRING = 0x0c
    private const val LONG_FORM = 0x80
    private const val BYTE = 0xff
    private const val BITS = 8
    private const val SHA256_HEX_LENGTH = 64
}
