package app.snapsync.format

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The sealed metadata record's pure half: the envelope's layout and the record's JSON (decision record
 * `changes/seal-event-name`, D1/D3). The bytes it seals to under real primitives are held to the reference vectors by
 * `:adapter:generic:app`'s `SealedMetaFormatTest`; here, an [Hmac] that records its calls shows the key's derivation.
 */
class SealedMetaFormatTest {

    private val text = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private class Recording : Hmac {
        val messages = mutableListOf<List<Byte>>()
        override fun mac(key: ByteArray, message: ByteArray): ByteArray {
            messages += message.toList()
            return ByteArray(32) { messages.size.toByte() }
        }
    }

    @Test
    fun `the record's key is HKDF over the event key with its own label and no salt`() {
        val hmac = Recording()
        val key = SealedMetaFormat.metaKeyOf(ByteArray(32) { 1 }, hmac)
        assertEquals(SealedMetaFormat.KEY_LENGTH, key.size)
        assertEquals(
            "snapsync/meta/v1".encodeToByteArray().toList() + 1.toByte(),
            hmac.messages[1],
            "T(1) = HMAC(PRK, label ‖ 0x01): the label is the binding",
        )
    }

    @Test
    fun `an envelope is the version, the nonce and the sealed bytes, unpadded base64url`() {
        val nonce = ByteArray(SealedMetaFormat.NONCE_LENGTH) { it.toByte() }
        val sealed = ByteArray(SealedMetaFormat.TAG_LENGTH + 3) { (0xf0 + it).toByte() }
        val envelope = SealedMetaFormat.encode(nonce, sealed)
        assertContentEquals(byteArrayOf(1) + nonce + sealed, text.decode(envelope))
        val read = assertIs<EnvelopeRead.Read>(SealedMetaFormat.decode(envelope))
        assertContentEquals(nonce, read.nonce)
        assertContentEquals(sealed, read.sealed)
    }

    @Test
    fun `what is not this format is refused, and says why`() {
        val nonce = ByteArray(SealedMetaFormat.NONCE_LENGTH)
        val tag = ByteArray(SealedMetaFormat.TAG_LENGTH)
        assertEquals(
            EnvelopeRead.Refused("unknown version 2"),
            SealedMetaFormat.decode(text.encode(byteArrayOf(2) + nonce + tag)),
        )
        assertEquals(
            EnvelopeRead.Refused("truncated"),
            SealedMetaFormat.decode(text.encode(byteArrayOf(1) + nonce + tag.copyOf(15))),
        )
        assertEquals(EnvelopeRead.Refused("not unpadded base64url"), SealedMetaFormat.decode("not base64!"))
        assertEquals(
            EnvelopeRead.Refused("longer than ${SealedMetaFormat.MAX_TEXT_LENGTH}"),
            SealedMetaFormat.decode("A".repeat(SealedMetaFormat.MAX_TEXT_LENGTH + 1)),
        )
    }

    @Test
    fun `a record reads its name and carries every other key untouched through a rename`() {
        val record = SealedMetaFormat.recordOf("""{"future":[1,2],"name":"Ski"}""".encodeToByteArray())!!
        assertEquals("Ski", record.name)
        val renamed = record.named("Ski 2")
        assertEquals(
            """{"name":"Ski 2","future":[1,2]}""",
            SealedMetaFormat.plaintextOf(renamed).decodeToString(),
        )
        val reread = SealedMetaFormat.recordOf(SealedMetaFormat.plaintextOf(renamed))!!
        assertEquals("Ski 2", reread.name, "a record reads back as it was sealed")
        assertEquals("""{"name":"Ski 2","future":[1,2]}""", SealedMetaFormat.plaintextOf(reread).decodeToString())
        assertEquals("Ski", record.name, "renaming makes a new record and leaves the old one")
        assertEquals(
            """{"name":"Ana's 30th"}""",
            SealedMetaFormat.plaintextOf(SealedMetaFormat.recordNamed("Ana's 30th")).decodeToString(),
        )
    }

    @Test
    fun `a plaintext that is no record with a string name is none`() {
        for (bad in listOf(
            """{"name":1}""",
            """{"name":{}}""",
            """{"other":"x"}""",
            "[]",
            "not json",
            """{"name":null}""",
        )) {
            assertNull(SealedMetaFormat.recordOf(bad.encodeToByteArray()), bad)
        }
        assertNull(SealedMetaFormat.recordOf(byteArrayOf(0xff.toByte(), 0xfe.toByte())), "not JSON once decoded")
    }
}
