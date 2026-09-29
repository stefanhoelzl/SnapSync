package app.snapsync.http

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A chain handed across the port as concatenated DER comes apart into exactly its certificates. */
class DerChainTest {

    private val short = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x07)
    private val long = byteArrayOf(0x30, 0x81.toByte(), 0x80.toByte()) + ByteArray(0x80) { 1 }

    @Test
    fun `concatenated elements split back into each whole element`() {
        val parts = derElements(short + long + short)
        assertEquals(3, parts.size)
        assertContentEquals(short, parts[0])
        assertContentEquals(long, parts[1])
        assertContentEquals(short, parts[2])
    }

    @Test
    fun `a chain cut short is refused rather than sent as fewer certificates`() {
        assertFailsWith<IllegalArgumentException> { derElements(short + long.copyOf(long.size - 1)) }
        assertFailsWith<IllegalArgumentException> { derElements(byteArrayOf(0x30)) }
    }
}
