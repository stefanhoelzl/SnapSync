package app.snapsync.data.crypto

import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.MintsIdentifiedKey
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.EventKeyIdText
import app.snapsync.format.Hmac
import app.snapsync.ports.Crypto

/**
 * An event key's id, derived from the key (the encrypted file format, `docs/architecture.md`) — what the event row and
 * the backend hold of it, telling a wrong key from a damaged file without revealing the key — and a fresh key.
 */
@OptIn(KeyMaterial::class, MakesEventKey::class, MintsIdentifiedKey::class)
class EventKeyIds(private val crypto: Crypto) {
    private val hmac = CryptoHmac(crypto)

    /** [key], with the id its derivation gives it. */
    fun identify(key: EventKey): IdentifiedKey =
        IdentifiedKey(key, EventKeyIdText.of(EncryptedFileFormat.keyIdOf(key.bytes(), hmac)))

    /** A fresh key, from the platform's secure generator, with its id. */
    fun mint(): IdentifiedKey = identify(
        EventKey.of(crypto.randomBytes(EventKey.LENGTH))
            ?: throw InvalidProgramState("Crypto answers exactly the count of bytes asked"),
    )
}

/** The format's HMAC, over the platform's. */
internal class CryptoHmac(private val crypto: Crypto) : Hmac {
    override fun mac(key: ByteArray, message: ByteArray): ByteArray = crypto.hmacSha256(key, message)
}
