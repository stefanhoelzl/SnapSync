package app.snapsync.crypto

import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Crypto
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * The iOS [Crypto], in BOTH processes (the upload extension derives a file's key; the app seals and opens files):
 * the Security framework's generator, CommonCrypto's HMAC, and CryptoKit's AES-GCM — CommonCrypto declares no public
 * GCM, and CryptoKit is Swift-only, so it is reached through cryptography-kotlin's CryptoKit provider, which bridges it.
 * Measured on the SE2: ~2.6 GB/s sealing, ~3.0 GB/s opening.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCryptographyApi::class)
class IosCrypto : Crypto {

    private val gcm = CryptographyProvider.CryptoKit.get(AES.GCM)

    override fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        if (count == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        return out
    }

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val out = ByteArray(HASH_LENGTH)
        // A pinned empty array has no address: hand CommonCrypto a one-byte buffer and a zero length instead.
        val k = if (key.isEmpty()) ByteArray(1) else key
        val m = if (message.isEmpty()) ByteArray(1) else message
        k.usePinned { kp ->
            m.usePinned { mp ->
                out.usePinned { op ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        kp.addressOf(0),
                        key.size.convert(),
                        mp.addressOf(0),
                        message.size.convert(),
                        op.addressOf(0),
                    )
                }
            }
        }
        return out
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(key).encryptWithIvBlocking(nonce, plaintext)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG_LENGTH) return null
        val cipher = cipher(key)
        // CryptoKit answers a seal that does not authenticate by throwing; nothing else here throws for valid inputs.
        return runCatchingCancellable { cipher.decryptWithIvBlocking(nonce, sealed) }.getOrNull()
    }

    private fun cipher(key: ByteArray): IvAuthenticatedCipher {
        require(key.size == KEY_LENGTH) { "AES-256 key length ${key.size}" }
        return gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key).cipher()
    }

    private companion object {
        const val HASH_LENGTH = 32
        const val KEY_LENGTH = 32
        const val TAG_LENGTH = 16
    }
}
