package app.snapsync.services.crypto

import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.Hmac
import app.snapsync.model.SecureSlots
import app.snapsync.model.decodeEventKey
import app.snapsync.model.encodeEventKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.DevControls
import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.services.secure.persist
import app.snapsync.services.secure.readExisting

/**
 * **An encrypted event's key, on this device** (the encrypted file format, `docs/architecture.md`): minted by the
 * creating device, carried to every other only inside the invite link, and kept here in the secure store's shared
 * slot ([SecureSlots.EVENT_KEY]) — never in the event config, never in a log, never sent to the backend, which holds
 * only its id.
 *
 * One slot: single active membership is the current contract, so a join writes the joined event's key over whatever
 * was there, and a leave or a reset removes it.
 */
class EventKeys(private val crypto: Crypto, private val store: SecureStore) {

    private val hmac = Hmac(crypto::hmacSha256)

    /** A fresh key, as a link carries it, and its id, as the event row holds it. */
    fun mint(): MintedKey {
        val key = crypto.randomBytes(EncryptedFileFormat.KEY_LENGTH)
        return MintedKey(encodeEventKey(key), idOf(key))
    }

    /** The id of the key [linkKey] names, or `null` when it names no key. */
    fun idOf(linkKey: String): String? = decodeEventKey(linkKey)?.let(::idOf)

    /**
     * Whether a link carrying [linkKey] (or none) opens the event whose row holds [eventKeyId] (or none). A plain
     * event wants no key, an encrypted one exactly its own — a link without the key, or with another, never joins.
     */
    fun opens(linkKey: String?, eventKeyId: String?): Boolean =
        if (linkKey == null) eventKeyId == null else eventKeyId != null && idOf(linkKey) == eventKeyId

    /** Keep [linkKey] as the joined event's key, or throw [SecureStoreUnavailable] when the store refuses it. */
    fun keep(linkKey: String) = persist(store, SecureSlots.EVENT_KEY, linkKey)

    /**
     * The joined event's key, or `null` when none is kept. Throws [SecureStoreUnavailable] when it could not be read —
     * a locked device is never mistaken for a plain event.
     */
    fun current(): ByteArray? = readExisting(store, SecureSlots.EVENT_KEY)?.let(::decodeEventKey)

    /** The joined event's key as its invite link carries it, or `null` when none is kept. Throws like [current]. */
    fun linkKey(): String? = readExisting(store, SecureSlots.EVENT_KEY)

    /** Remove the kept key — at a leave or a reset. Deleting nothing is fine. */
    fun forget() {
        store.delete(SecureSlots.EVENT_KEY)
    }

    private fun idOf(key: ByteArray): String =
        EncryptedFileFormat.keyIdOf(key, hmac).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** A freshly minted event key: [linkKey] for the invite link, [keyId] for the event row. */
class MintedKey(val linkKey: String, val keyId: String)

/**
 * **Whether a new event is encrypted, and its key** (the encrypted file format, `docs/architecture.md`): a fresh key
 * while the build's development control says so ([DevControls.encryptsNewEvents] — a rig build's switch until
 * encryption is enabled), else none and the event is plain. Asked once per create.
 */
class EventKeyMinting(private val keys: EventKeys, private val controls: DevControls) {
    fun forNewEvent(): MintedKey? = if (controls.encryptsNewEvents()) keys.mint() else null
}
