package app.snapsync.format

import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventKeyId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An event's encryption as the event row and the backend carry it: its key id, or none. */
class EncryptionTextTest {

    @Test
    fun `a key id is an encrypted event, none a plain one, and back`() {
        assertEquals(Encryption.Plain, EncryptionText.parse(null))
        assertEquals(Encryption.Encrypted(EventKeyId("0011")), EncryptionText.parse("0011"))
        assertNull(EncryptionText.of(Encryption.Plain))
        assertEquals("0011", EncryptionText.of(Encryption.Encrypted(EventKeyId("0011"))))
    }

    @Test
    fun `a key id's text is its bytes in lowercase hex`() {
        assertEquals(EventKeyId("00ff10ab"), EventKeyIdText.of(byteArrayOf(0, -1, 16, -85)))
    }

    @Test
    fun `a header value is unpadded base64url`() {
        assertEquals("AP8", EncryptedFileFormat.headerValue(byteArrayOf(0, -1)))
    }
}
