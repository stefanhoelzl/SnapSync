package app.snapsync.mock

import app.snapsync.ports.Crypto

/**
 * A stand-in [Crypto] for common code, where no platform's primitives are reachable — **not cryptography**, and never
 * shipped (a shipped root links no mock). Honest in what a test can observe: the same key and nonce always seal to
 * the same bytes, a seal opens only under its own key and nonce and unchanged, and every draw of "random" bytes is
 * new. The real primitives are bound to the `Crypto` contract on every platform; this one deliberately is not.
 */
fun fakeCrypto(): Crypto = FakeCrypto()

private class FakeCrypto : Crypto {
    private var draws = 0L

    override fun randomBytes(count: Int): ByteArray = stream(longArrayOf(++draws, 0x5eed), count)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray = stream(mix(key) + mix(message), HASH_LENGTH)

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val pad = stream(mix(key) + mix(nonce), plaintext.size)
        val body = ByteArray(plaintext.size) { (plaintext[it].toInt() xor pad[it].toInt()).toByte() }
        return body + tag(key, nonce, body)
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_LENGTH) return null
        val body = sealed.copyOf(sealed.size - TAG_LENGTH)
        if (!tag(key, nonce, body).contentEquals(sealed.copyOfRange(body.size, sealed.size))) return null
        val pad = stream(mix(key) + mix(nonce), body.size)
        return ByteArray(body.size) { (body[it].toInt() xor pad[it].toInt()).toByte() }
    }

    private fun tag(key: ByteArray, nonce: ByteArray, body: ByteArray) =
        stream(mix(key) + mix(nonce) + mix(body) + longArrayOf(body.size.toLong()), TAG_LENGTH)

    /** Folds [bytes] into two words, order- and length-sensitive. */
    private fun mix(bytes: ByteArray): LongArray {
        var a = 0x243f6a8885a308d3L
        var b = bytes.size.toLong()
        bytes.forEach {
            a = splitmix(a xor (it.toLong() and 0xff))
            b = splitmix(b + a)
        }
        return longArrayOf(a, b)
    }

    /** [count] bytes expanded from [seed]. */
    private fun stream(seed: LongArray, count: Int): ByteArray {
        var state = seed.fold(0x13198a2e03707344L) { acc, word -> splitmix(acc xor word) }
        return ByteArray(count) { i ->
            if (i % Long.SIZE_BYTES == 0) state = splitmix(state)
            (state ushr (8 * (i % Long.SIZE_BYTES))).toByte()
        }
    }

    private fun splitmix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private companion object {
        const val HASH_LENGTH = 32
        const val TAG_LENGTH = 16
    }
}
