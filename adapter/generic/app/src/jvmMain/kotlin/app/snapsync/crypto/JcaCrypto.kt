package app.snapsync.crypto

import app.snapsync.ports.Crypto
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The JVM [Crypto]: the JDK's own providers — the crypto of the JVM binaries (the desktop harnesses, the JVM rig host),
 * and the live host of the `Crypto` contract and the encrypted file format's vectors on every `./gradlew build`.
 */
class JcaCrypto : Crypto {
    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_BITS / Byte.SIZE_BITS) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, nonce).doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == KEY_LENGTH) { "AES-256 key length ${key.size}" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce)) }
    }

    private companion object {
        const val KEY_LENGTH = 32
        const val TAG_BITS = 128
    }
}
