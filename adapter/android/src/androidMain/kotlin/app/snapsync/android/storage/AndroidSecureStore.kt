package app.snapsync.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.nio.file.Files as Nio

/**
 * The Android [SecureStore]: each slot is one app-private file under [directory], holding its value encrypted with
 * AES-GCM under ONE Android Keystore key ([alias]) — the key never leaves the Keystore, so a copied file is unreadable
 * anywhere else. The key requires no user authentication, so background work reads it whenever the app runs at all
 * (the app is not direct-boot aware: nothing of it runs before the first unlock), which is every item's
 * [StoredProtection.BACKGROUND_READABLE].
 *
 * **Slots stay distinct by all three coordinates.** A slot's [SecureSlot.shared] flag means nothing on Android — one
 * process, nobody to share with — but it is kept in the address: the device id's shared and legacy slots have the same
 * service and account, and the identity service adopts from the one into the other, so collapsing them would make the
 * adoption read and delete the very item it wrote. The slot's address is also the cipher's associated data, so a file
 * moved to another slot's name does not decrypt there.
 *
 * **What outlives what.** The port's iOS reading is "outlives the app install"; this store does not — app-private
 * storage and the Keystore key both go with the app, and Auto Backup is off. What that costs is carried elsewhere: the
 * device id is re-derived from the platform's id ([AndroidPlatformDeviceId]), and the attestation re-runs.
 *
 * **A lost key is absence, not unavailability** — the one place this store deliberately answers differently from "could
 * not look". A value encrypted under a key the Keystore no longer has, or has permanently invalidated, or that fails its
 * authentication tag, can never be read again by anyone: answering [SecureStoreRead.Unavailable] would leave the
 * device without an identity forever, because the identity service never mints over an unavailable read. So such an
 * item reads [SecureStoreRead.Absent] and its ciphertext is deleted; the device id then comes back the same from the
 * platform id. A Keystore that merely fails to answer (a busy service, an I/O error) stays [SecureStoreRead.Unavailable].
 *
 * Reads and writes are serialized: two first writes racing would each generate the key, the second replacing the first,
 * and the first's item would then read as lost.
 */
class AndroidSecureStore(
    private val directory: File,
    private val alias: String = PRODUCTION_ALIAS,
) : SecureStore {

    /** Production: `filesDir/secure`, under the production key (a secondary constructor, not defaults). */
    constructor(context: Context) : this(File(context.filesDir, "secure"))

    @Synchronized
    override fun read(slot: SecureSlot): SecureStoreRead {
        val file = fileOf(slot)
        val sealed = try {
            Nio.readAllBytes(file.toPath())
        } catch (_: NoSuchFileException) {
            return SecureStoreRead.Absent
        } catch (e: IOException) {
            return SecureStoreRead.Unavailable("read ${slot.describe()}: ${e.describe()}")
        }
        return try {
            val key = existingKey() ?: return lost(file, slot, "the Keystore holds no key '$alias'")
            SecureStoreRead.Found(open(key, sealed, slot), StoredProtection.BACKGROUND_READABLE)
        } catch (e: KeyPermanentlyInvalidatedException) {
            lost(file, slot, e.describe())
        } catch (e: AEADBadTagException) {
            lost(file, slot, e.describe())
        } catch (e: GeneralSecurityException) {
            SecureStoreRead.Unavailable("decrypt ${slot.describe()}: ${e.describe()}")
        } catch (e: IllegalArgumentException) {
            lost(file, slot, "malformed item: ${e.describe()}")
        } catch (e: RuntimeException) {
            // The Keystore reports a service it could not reach as a `ProviderException` and its kin.
            SecureStoreRead.Unavailable("decrypt ${slot.describe()}: ${e.describe()}")
        }
    }

    @Synchronized
    override fun write(slot: SecureSlot, value: String): WriteOutcome = try {
        val sealed = seal(keyForWriting(), value, slot)
        val target = fileOf(slot).toPath()
        Nio.createDirectories(target.parent)
        val temp = Nio.createTempFile(target.parent, ".slot.", ".tmp")
        try {
            Nio.write(temp, sealed)
            Nio.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Nio.deleteIfExists(temp)
        }
        WriteOutcome.Ok
    } catch (e: IOException) {
        WriteOutcome.Failed("write ${slot.describe()}: ${e.describe()}")
    } catch (e: GeneralSecurityException) {
        WriteOutcome.Failed("encrypt ${slot.describe()}: ${e.describe()}")
    } catch (e: RuntimeException) {
        WriteOutcome.Failed("encrypt ${slot.describe()}: ${e.describe()}")
    }

    // Every item is filed background-readable from its first write; there is nothing to upgrade.
    override fun migrateProtection(slot: SecureSlot): WriteOutcome = WriteOutcome.Ok

    @Synchronized
    override fun delete(slot: SecureSlot): WriteOutcome = try {
        Nio.deleteIfExists(fileOf(slot).toPath())
        WriteOutcome.Ok
    } catch (e: IOException) {
        WriteOutcome.Failed("delete ${slot.describe()}: ${e.describe()}")
    }

    private fun lost(file: File, slot: SecureSlot, why: String): SecureStoreRead {
        // The ciphertext is garbage to everyone now; leaving it would only make the next read take this path again.
        try {
            Nio.deleteIfExists(file.toPath())
        } catch (e: IOException) {
            return SecureStoreRead.Unavailable("${slot.describe()} is undecryptable ($why) and could not be removed: ${e.describe()}")
        }
        return SecureStoreRead.Absent
    }

    private fun seal(key: SecretKey, value: String, slot: SecureSlot): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(addressOf(slot))
        val iv = cipher.iv
        val body = cipher.doFinal(value.encodeToByteArray())
        return ByteBuffer.allocate(1 + 1 + iv.size + body.size).put(FORMAT).put(iv.size.toByte()).put(iv).put(body).array()
    }

    private fun open(key: SecretKey, sealed: ByteArray, slot: SecureSlot): String {
        val buffer = ByteBuffer.wrap(sealed)
        require(sealed.size > 2 && buffer.get() == FORMAT) { "unknown item format" }
        val iv = ByteArray(buffer.get().toInt()).also { require(it.size in 1..buffer.remaining()) { "truncated item" }; buffer.get(it) }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(addressOf(slot))
        return cipher.doFinal(body).decodeToString()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun keyForWriting(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
        }
        .generateKey()

    /** The slot's full address — its file name's source and the cipher's associated data. */
    private fun addressOf(slot: SecureSlot): ByteArray =
        "${if (slot.shared) "shared" else "unshared"}\u0000${slot.service}\u0000${slot.account}".encodeToByteArray()

    private fun fileOf(slot: SecureSlot): File =
        File(directory, MessageDigest.getInstance("SHA-256").digest(addressOf(slot)).joinToString("") { "%02x".format(it) })

    private fun SecureSlot.describe() = "$service/$account${if (shared) " (shared)" else ""}"

    private fun Throwable.describe(): String = when (this) {
        is AccessDeniedException -> "access denied: $message"
        else -> "${this::class.simpleName}: $message"
    }

    companion object {
        /** The production key's alias — runtime identity: every item on an installed device is sealed under it. */
        const val PRODUCTION_ALIAS = "app.snapsync.securestore"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128

        /** The item layout's version byte: `[FORMAT][ivLength][iv][ciphertext‖tag]`. */
        private const val FORMAT: Byte = 1
    }
}
