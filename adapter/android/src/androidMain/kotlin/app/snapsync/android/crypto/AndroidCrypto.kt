package app.snapsync.android.crypto

import app.snapsync.model.AesKey
import app.snapsync.model.GcmNonce
import app.snapsync.ports.Crypto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Android [Crypto]: the platform's JCA provider (Conscrypt, BoringSSL underneath, on the CPU's AES instructions —
 * measured ~250 MB/s on the A40). Bound live to the `Crypto` contract on the emulator.
 */
class AndroidCrypto : Crypto {
    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // JCA refuses an empty key; HMAC pads a key with zeros to its block (RFC 2104), so one zero byte is the same key.
            init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(1), "HmacSHA256"))
            doFinal(message)
        }

    override fun aesGcmSeal(key: AesKey, nonce: GcmNonce, plaintext: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)

    override fun sha256(chunks: Sequence<ByteArray>): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            chunks.forEach(::update)
            digest()
        }

    override fun aesGcmOpen(key: AesKey, nonce: GcmNonce, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_BITS / Byte.SIZE_BITS) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, nonce).doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun gcm(mode: Int, key: AesKey, nonce: GcmNonce): Cipher {
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BITS, nonce.bytes))
        }
    }

    private companion object {
        const val TAG_BITS = 128
    }
}
