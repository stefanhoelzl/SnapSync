package app.snapsync.format

import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventKeyId

/**
 * An event's encryption as the wire and the stores carry it: its key id's text, or none for a plain event — the event
 * row's `keyId` column and the backend's `keyId` field.
 */
object EncryptionText {
    /** The encryption a stored or served [keyId] names. */
    fun parse(keyId: String?): Encryption = keyId?.let { Encryption.Encrypted(EventKeyId(it)) } ?: Encryption.Plain

    /** [encryption]'s key id text, or `null` for a plain event. */
    fun of(encryption: Encryption): String? = when (encryption) {
        Encryption.Plain -> null
        is Encryption.Encrypted -> encryption.keyId.value
    }
}
