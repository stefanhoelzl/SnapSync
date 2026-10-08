package app.snapsync.liveedge

import app.snapsync.contracts.SoftwareAttester
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * A key attested the way an Android emulator's KeyMint attests one, in software: a three-certificate chain — a
 * self-signed root, an intermediate, and the leaf carrying the key description (Android key attestation, OID
 * `1.3.6.1.4.1.11129.2.1.17`) with the backend's challenge digest and this app's package. The local rig's `any`
 * policy accepts exactly that; it proves the mint and renewal routes and their verifier, never hardware trust, which
 * is the production `hardware` policy's and refuses this chain.
 */
internal class SoftwareKeyAttestation(private val packageName: String = PACKAGE) : SoftwareAttester {

    private val root = p256()
    private val intermediate = p256()
    private val leaf = p256()

    override fun attest(challenge: String): ByteArray {
        val rootName = X500Name("CN=SnapSync contract attestation root")
        val intermediateName = X500Name("CN=SnapSync contract attestation intermediate")
        val rootCert = certificate(rootName, rootName, root, root) { }
        val intermediateCert = certificate(intermediateName, rootName, intermediate, root) { }
        val leafCert = certificate(X500Name("CN=Android Keystore Key"), intermediateName, leaf, intermediate) {
            addExtension(KEY_ATTESTATION, false, keyDescription(sha256(challenge.encodeToByteArray())))
        }
        return leafCert + intermediateCert + rootCert
    }

    override fun sign(challenge: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(leaf.private)
            update(challenge.encodeToByteArray())
            sign()
        }

    /** `KeyDescription`: a software attestation over [challengeDigest], naming [packageName] as the attested app. */
    private fun keyDescription(challengeDigest: ByteArray): DERSequence {
        val applicationId = DERSequence(
            arrayOf(
                DERSet(DERSequence(arrayOf(DEROctetString(packageName.encodeToByteArray()), ASN1Integer(1)))),
                DERSet(DEROctetString(ByteArray(DIGEST_BYTES))),
            ),
        )
        val softwareEnforced =
            DERSequence(DERTaggedObject(true, ATTESTATION_APPLICATION_ID, DEROctetString(applicationId)))
        return DERSequence(
            ASN1EncodableVector().apply {
                add(ASN1Integer(KEYMINT_VERSION))
                add(ASN1Enumerated(SOFTWARE))
                add(ASN1Integer(KEYMINT_VERSION))
                add(ASN1Enumerated(SOFTWARE))
                add(DEROctetString(challengeDigest))
                add(DEROctetString(ByteArray(0)))
                add(softwareEnforced)
                add(DERSequence())
            },
        )
    }

    private fun certificate(
        subject: X500Name,
        issuer: X500Name,
        key: KeyPair,
        signer: KeyPair,
        extend: X509v3CertificateBuilder.() -> Unit,
    ): ByteArray {
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now - VALIDITY),
            Date.from(now + VALIDITY),
            subject,
            key.public,
        )
        builder.extend()
        return builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signer.private)).encoded
    }

    private fun p256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        /** The package the local deployment's api accepts (`deployments/components/android.json`). */
        const val PACKAGE = "app.snapsync"
        val KEY_ATTESTATION = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
        const val ATTESTATION_APPLICATION_ID = 709
        const val KEYMINT_VERSION = 300L
        const val SOFTWARE = 0
        const val DIGEST_BYTES = 32
        val VALIDITY: Duration = Duration.ofDays(1)
    }
}
