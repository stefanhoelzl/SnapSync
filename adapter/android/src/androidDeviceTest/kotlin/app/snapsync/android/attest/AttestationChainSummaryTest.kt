package app.snapsync.android.attest

import app.snapsync.android.network.EmulatorNetwork
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The summary a fresh proof carries for the operator (capability `privacy-security`, what a report the app offered for
 * a refused phone holds), over the emulator's real Keystore chain: every certificate above the leaf with its names
 * verbatim, and the root's key fingerprint as the backend pins it.
 */
class AttestationChainSummaryTest {

    @BeforeTest
    fun `the Keystore can provision an attestation key`() =
        EmulatorNetwork.requireInternet("the Keystore provisions attestation keys over the network (RKP)")

    @Test
    fun `a fresh proof summarises the certificates above the leaf and fingerprints the root key`() = runTest {
        val proof = AndroidDeviceIntegrity().prove("summary-challenge")
        val summary = assertNotNull(proof.chain, "a fresh Android proof carries its chain summary")
        val chain = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCertificateChain(proof.handle)
            .map { it as X509Certificate }

        assertEquals(chain.size - 1, summary.certificates.size, "every certificate above the leaf, and not the leaf")
        val root = chain.last()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.publicKey.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(fingerprint, summary.rootKeySha256)
        assertEquals(root.subjectX500Principal.getName("RFC2253"), summary.certificates.last().subject)

        // Names verbatim, never redacted — even an RKP certificate named after its own serial (decision record D11).
        assertEquals(chain.drop(1).map { it.subjectX500Principal.getName("RFC2253") }, summary.certificates.map { it.subject })
        assertEquals(chain.drop(1).map { it.issuerX500Principal.getName("RFC2253") }, summary.certificates.map { it.issuer })
        assertEquals(null, AndroidDeviceIntegrity().prove("renewal", proof.handle).chain, "a renewal carries none")
    }
}
