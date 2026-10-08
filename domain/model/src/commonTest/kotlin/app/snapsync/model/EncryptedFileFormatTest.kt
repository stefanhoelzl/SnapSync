package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The format's pure half: the layout and HKDF's expansion. The bytes it produces under real primitives are held to the
 * reference vectors and to Google Tink by `:adapter:generic:app`'s `EncryptedFileFormatTest`; here, an [Hmac] that
 * records its calls shows the RFC 5869 structure itself.
 */
class EncryptedFileFormatTest {

    /** Not a MAC: the key, then the message, so a test can read back exactly what HKDF fed it. */
    private class Recording : Hmac {
        val calls = mutableListOf<Pair<List<Byte>, List<Byte>>>()
        override fun mac(key: ByteArray, message: ByteArray): ByteArray {
            calls += key.toList() to message.toList()
            return ByteArray(32) { (calls.size * 31 + it).toByte() }
        }
    }

    private val head = FileHead(
        keyId = ByteArray(8) { it.toByte() },
        salt = ByteArray(32) { (0x40 + it).toByte() },
        noncePrefix = ByteArray(7) { (0xa0 + it).toByte() },
    )

    @Test
    fun `HKDF extracts with the salt or zeros when none and then expands block by block`() {
        val hmac = Recording()
        val out = Hkdf.derive(hmac, byteArrayOf(1, 2), ByteArray(0), byteArrayOf(9), 40)
        assertEquals(40, out.size)
        assertEquals(List(32) { 0.toByte() } to listOf<Byte>(1, 2), hmac.calls[0], "extract: PRK = HMAC(zeros, IKM)")
        val prk = ByteArray(32) { (31 + it).toByte() }.toList()
        assertEquals(prk to listOf<Byte>(9, 1), hmac.calls[1], "T(1) = HMAC(PRK, info ‖ 0x01)")
        val t1 = ByteArray(32) { (62 + it).toByte() }.toList()
        assertEquals(prk to t1 + listOf<Byte>(9, 2), hmac.calls[2], "T(2) = HMAC(PRK, T(1) ‖ info ‖ 0x02)")
        assertEquals(3, hmac.calls.size, "40 bytes take two blocks")
        assertContentEquals(t1.toByteArray(), out.copyOf(32))
        val salted = Recording().also { Hkdf.derive(it, byteArrayOf(1), byteArrayOf(7), ByteArray(0), 0) }
        assertEquals(
            listOf<Byte>(7) to listOf<Byte>(1),
            salted.calls.single(),
            "a salt is the extract key; 0 bytes expand nothing",
        )
        assertFailsWith<IllegalArgumentException> {
            Hkdf.derive(Recording(), ByteArray(1), ByteArray(0), ByteArray(0), 255 * 32 + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Hkdf.derive(Recording(), ByteArray(1), ByteArray(0), ByteArray(0), -1)
        }
    }

    @Test
    fun `the key id and a file key are HKDF over the event key`() {
        val keyId = Recording()
        assertEquals(8, EncryptedFileFormat.keyIdOf(ByteArray(32), keyId).size)
        assertEquals("snapsync/key-id/v1".encodeToByteArray().toList() + 1, keyId.calls[1].second)
        val fileKey = Recording()
        val ad = EncryptedFileFormat.associatedData("e", "d", "a", "live")
        assertEquals("snapsync/v1/e/d/a/live", ad.decodeToString())
        assertEquals(32, EncryptedFileFormat.fileKeyOf(ByteArray(32), byteArrayOf(5), ad, fileKey).size)
        assertEquals(listOf<Byte>(5), fileKey.calls[0].first, "the file's salt")
        assertEquals(ad.toList() + 1, fileKey.calls[1].second, "bound to what the file is")
    }

    @Test
    fun `the head encodes as the prefix then the Tink header and decodes back`() {
        val bytes = EncryptedFileFormat.encodeHead(head)
        assertEquals(EncryptedFileFormat.HEAD_LENGTH, bytes.size)
        assertEquals(1, bytes[0].toInt(), "format version")
        assertContentEquals(head.keyId, bytes.copyOfRange(1, 9))
        assertEquals(40, bytes[9].toInt(), "Tink's header length")
        assertContentEquals(head.salt, bytes.copyOfRange(10, 42))
        assertContentEquals(head.noncePrefix, bytes.copyOfRange(42, 49))
        assertEquals(head, assertIs<HeadRead.Read>(EncryptedFileFormat.decodeHead(bytes + byteArrayOf(1, 2))).head)
        assertEquals(
            head.hashCode(),
            FileHead(head.keyId.copyOf(), head.salt.copyOf(), head.noncePrefix.copyOf()).hashCode(),
        )
        assertNotEquals<Any>(head, "a head")
        // Equal by every byte of every part: a head differing in any one part is another file's.
        val other = ByteArray(32) { 99 }
        assertNotEquals(head, FileHead(other.copyOf(8), head.salt, head.noncePrefix))
        assertNotEquals(head, FileHead(head.keyId, other, head.noncePrefix))
        assertNotEquals(head, FileHead(head.keyId, head.salt, other.copyOf(7)))
    }

    @Test
    fun `a head that is not this format is refused with why`() {
        val bytes = EncryptedFileFormat.encodeHead(head)
        assertIs<HeadRead.Refused>(EncryptedFileFormat.decodeHead(bytes.copyOf(48)))
        assertEquals(
            HeadRead.Refused("unknown format version 2"),
            EncryptedFileFormat.decodeHead(bytes.copyOf().also { it[0] = 2 }),
        )
        assertEquals(
            HeadRead.Refused("header length 41"),
            EncryptedFileFormat.decodeHead(bytes.copyOf().also { it[9] = 41 }),
        )
        assertFailsWith<IllegalArgumentException> { FileHead(ByteArray(7), head.salt, head.noncePrefix) }
        assertFailsWith<IllegalArgumentException> { FileHead(head.keyId, ByteArray(31), head.noncePrefix) }
        assertFailsWith<IllegalArgumentException> { FileHead(head.keyId, head.salt, ByteArray(8)) }
    }

    @Test
    fun `segments are 64 KiB but the first and each nonce names its number and whether it is last`() {
        assertEquals(65536 - 16 - 40, EncryptedFileFormat.plaintextSegmentLength(0))
        assertEquals(65536 - 16, EncryptedFileFormat.plaintextSegmentLength(1))
        assertEquals(65536 - 40, EncryptedFileFormat.ciphertextSegmentLength(0))
        assertEquals(65536, EncryptedFileFormat.ciphertextSegmentLength(7))
        val nonce = EncryptedFileFormat.segmentNonce(head.noncePrefix, 0x01020304, last = true)
        assertContentEquals(head.noncePrefix + byteArrayOf(1, 2, 3, 4, 1), nonce)
        assertEquals(0, EncryptedFileFormat.segmentNonce(head.noncePrefix, 0, last = false)[11].toInt())
    }
}
