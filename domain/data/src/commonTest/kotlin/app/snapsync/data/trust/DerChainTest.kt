package app.snapsync.data.trust

import app.snapsync.ports.Proof
import app.snapsync.ports.ProofFormat
import app.snapsync.wire.models.AndroidKeyProof
import app.snapsync.wire.models.AppleAppAttestProof
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A chain handed across the port as concatenated DER comes apart into exactly its certificates. */
class DerChainTest {

    private val short = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x07)
    private val long = byteArrayOf(0x30, 0x81.toByte(), 0x80.toByte()) + ByteArray(0x80) { 1 }

    @Test
    fun `concatenated elements split back into each whole element`() {
        val parts = assertNotNull(DerChain.elements(short + long + short))
        assertEquals(3, parts.size)
        assertContentEquals(short, parts[0])
        assertContentEquals(long, parts[1])
        assertContentEquals(short, parts[2])
    }

    @Test
    fun `bytes that are not whole DER elements are refused rather than sent as fewer certificates`() {
        val refused = mapOf(
            "a chain cut short" to short + long.copyOf(long.size - 1),
            "a tag with no length" to byteArrayOf(0x30),
            "a high tag number" to byteArrayOf(0x1f, 0x01, 0x00),
            "a long form with no length bytes" to byteArrayOf(0x30, 0x80.toByte()),
            "a long form wider than any certificate" to byteArrayOf(0x30, 0x84.toByte(), 0, 0, 0, 1, 0),
            "a long form cut inside its length" to byteArrayOf(0x30, 0x82.toByte(), 0x01),
        )
        refused.forEach { (what, bytes) -> assertNull(DerChain.elements(bytes), what) }
    }

    @Test
    fun `a proof crosses to the mint in its format - an Android chain one certificate at a time`() {
        val android = assertIs<AndroidKeyProof>(Proofs.wire(Proof("k", ProofFormat.ANDROID_KEY, short + long)))
        assertEquals(listOf(Base64.encode(short), Base64.encode(long)), android.chain)
        assertEquals(
            AppleAppAttestProof("k", Base64.encode(short)),
            Proofs.wire(Proof("k", ProofFormat.APP_ATTEST, short)),
        )
        assertNull(
            Proofs.wire(Proof("k", ProofFormat.ANDROID_KEY, byteArrayOf(0x30))),
            "never sent as fewer certificates",
        )
    }
}
