package app.snapsync.android.attest

import app.snapsync.model.AttestationChain
import app.snapsync.model.CertificateFacts
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.security.auth.x500.X500Principal

/**
 * [chain] — the Keystore's, leaf first — summarised for the operator ([AttestationChain]): every certificate above the
 * leaf, and the fingerprint of the root's key. `null` for a chain with no certificate above the leaf.
 *
 * Names are kept verbatim — an RKP attestation certificate is named after its own serial, and that is not redacted (the
 * backend already receives every chain); no serial is read as a field of its own.
 */
internal fun summarise(chain: Array<Certificate>): AttestationChain? {
    val above = chain.drop(1).filterIsInstance<X509Certificate>()
    val root = above.lastOrNull() ?: return null
    return AttestationChain(
        certificates = above.map { cert ->
            CertificateFacts(
                subject = cert.subjectX500Principal.getName(X500Principal.RFC2253),
                issuer = cert.issuerX500Principal.getName(X500Principal.RFC2253),
                notBefore = cert.notBefore.toInstant().toString(),
                notAfter = cert.notAfter.toInstant().toString(),
                key = keyOf(cert),
            )
        },
        rootKeySha256 = MessageDigest.getInstance("SHA-256").digest(root.publicKey.encoded).toHex(),
    )
}

/** `EC 256`, `RSA 4096` — the key's algorithm and size, or the algorithm alone where the size is not exposed. */
private fun keyOf(cert: X509Certificate): String = when (val key = cert.publicKey) {
    is ECPublicKey -> "EC ${key.params.order.bitLength()}"
    is RSAPublicKey -> "RSA ${key.modulus.bitLength()}"
    else -> key.algorithm
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
