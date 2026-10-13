package app.snapsync.contracts.attest

import app.snapsync.contracts.Attester
import app.snapsync.wire.models.AndroidKeyProof
import app.snapsync.wire.models.Proof
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A key attested the way an Android emulator's KeyMint attests one, in software: a three-certificate chain — a
 * self-signed root, an intermediate, and the leaf carrying the key description (Android key attestation, OID
 * `1.3.6.1.4.1.11129.2.1.17`) with the backend's challenge digest and this app's package. The local api's `any` trust
 * accepts exactly that, and the production `hardware` trust refuses it: it proves the mint and renewal routes and their
 * verifier, never hardware trust.
 *
 * Every test host that talks to the real `api/` attests with one — the Backend contract's setup and its client, the
 * rig's JVM host and simulator app, the journeys' second member — so nothing needs the backend to serve a request that
 * carries no token. Common code, so the simulator app builds the same chain the JVM does.
 */
class SoftwareKeyAttestation(private val packageName: String = PACKAGE) : Attester {

    private val provider: CryptographyProvider = attestationProvider()
    private val ecdsa = provider.get(ECDSA)
    private val root = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
    private val intermediate = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
    private val leaf = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()

    /** The chain over [challenge], leaf first, each certificate DER — what an Android device mints with. */
    fun chain(challenge: String, now: Instant = Clock.System.now()): List<ByteArray> {
        val rootName = name("SnapSync software attestation root")
        val intermediateName = name("SnapSync software attestation intermediate")
        val digest = provider.get(SHA256).hasher().hashBlocking(challenge.encodeToByteArray())
        return listOf(
            certificate(
                name("Android Keystore Key"),
                intermediateName,
                leaf,
                intermediate,
                now,
                keyDescription(digest),
            ),
            certificate(intermediateName, rootName, intermediate, root, now, extension = null),
            certificate(rootName, rootName, root, root, now, extension = null),
        )
    }

    override fun proof(challenge: String): Proof = AndroidKeyProof(chain(challenge).map { Base64.encode(it) })

    /** The leaf key's ECDSA-SHA256 signature over [challenge], DER — what a renewal sends. */
    override fun sign(challenge: String): ByteArray =
        leaf.privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignatureBlocking(challenge.encodeToByteArray())

    /** `KeyDescription`: a software attestation over [challengeDigest], naming [packageName] as the attested app. */
    private fun keyDescription(challengeDigest: ByteArray): ByteArray {
        val applicationId = Der.sequence(
            Der.set(Der.sequence(Der.octetString(packageName.encodeToByteArray()), Der.integer(1))),
            Der.set(Der.octetString(ByteArray(DIGEST_BYTES))),
        )
        val softwareEnforced = Der.sequence(Der.explicit(ATTESTATION_APPLICATION_ID, Der.octetString(applicationId)))
        return Der.sequence(
            Der.integer(KEYMINT_VERSION),
            Der.enumerated(SOFTWARE),
            Der.integer(KEYMINT_VERSION),
            Der.enumerated(SOFTWARE),
            Der.octetString(challengeDigest),
            Der.octetString(ByteArray(0)),
            softwareEnforced,
            Der.sequence(),
        )
    }

    private fun certificate(
        subject: ByteArray,
        issuer: ByteArray,
        key: ECDSA.KeyPair,
        signer: ECDSA.KeyPair,
        now: Instant,
        extension: ByteArray?,
    ): ByteArray {
        val extensions = extension?.let {
            Der.explicit(EXTENSIONS, Der.sequence(Der.sequence(Der.oid(KEY_ATTESTATION), Der.octetString(it))))
        } ?: ByteArray(0)
        val tbs = Der.sequence(
            Der.explicit(VERSION, Der.integer(V3)),
            Der.integer(now.toEpochMilliseconds()),
            ECDSA_WITH_SHA256,
            issuer,
            Der.sequence(Der.utcTime(utc(now - VALIDITY)), Der.utcTime(utc(now + VALIDITY))),
            subject,
            key.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.DER),
            extensions,
        )
        val signature = signer.privateKey.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignatureBlocking(tbs)
        return Der.sequence(tbs, ECDSA_WITH_SHA256, Der.bitString(signature))
    }

    private fun name(commonName: String): ByteArray =
        Der.sequence(Der.set(Der.sequence(Der.oid(COMMON_NAME), Der.utf8String(commonName))))

    /** [instant] as UTCTime's digits, `YYMMDDHHMMSS`. */
    private fun utc(instant: Instant): String {
        val iso = instant.toString() // 2026-10-13T22:30:00Z, or with a fraction
        return iso.substring(2, 4) + iso.substring(5, 7) + iso.substring(8, 10) +
            iso.substring(11, 13) + iso.substring(14, 16) + iso.substring(17, 19)
    }

    private companion object {
        /** The package the local deployment's api accepts (`deployments/components/android.json`). */
        const val PACKAGE = "app.snapsync"
        const val KEY_ATTESTATION = "1.3.6.1.4.1.11129.2.1.17"
        const val COMMON_NAME = "2.5.4.3"
        val ECDSA_WITH_SHA256 = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))
        const val ATTESTATION_APPLICATION_ID = 709
        const val KEYMINT_VERSION = 300L
        const val SOFTWARE = 0L
        const val DIGEST_BYTES = 32
        const val VERSION = 0
        const val EXTENSIONS = 3
        const val V3 = 2L
        val VALIDITY = 1.days
    }
}

/** The platform's cryptography provider: the JDK's on the JVM and Android, CryptoKit on iOS. */
internal expect fun attestationProvider(): CryptographyProvider
