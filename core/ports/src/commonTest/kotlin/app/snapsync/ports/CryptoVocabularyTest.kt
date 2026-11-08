package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The cipher's key and nonce, as the Crypto port carries them: their bytes, and a key never rendered. */
class CryptoVocabularyTest {

    @Test
    fun `a key carries its bytes and renders none of them, a nonce carries its bytes`() {
        val key = AesKey(ByteArray(32) { 7 })
        assertContentEquals(ByteArray(32) { 7 }, key.bytes)
        assertEquals("AesKey(***)", key.toString())
        assertContentEquals(ByteArray(12) { 3 }, GcmNonce(ByteArray(12) { 3 }).bytes)
    }
}
