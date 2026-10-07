package app.snapsync.mock

import app.snapsync.model.CreateEventRequest
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.EventCreated
import app.snapsync.model.FileHead
import app.snapsync.model.Hmac
import app.snapsync.model.Reply
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.Backend
import app.snapsync.ports.Crypto
import kotlin.concurrent.Volatile

/**
 * **The keys of the encrypted events this device created** — what the world playing the other members needs and a
 * phone never gives away: an event's key leaves the creating device only inside its invite (the encrypted file format,
 * `docs/architecture.md`), and a test asks for an event by id. Every key the process's [recording] crypto draws and
 * every event id the backend answers a keyed create with are recorded here, so an event's key is the drawn key whose id
 * its create carried. Durable like the rest of a mocked device: a relaunch or a reinstall forgets nothing of it.
 */
class EventKeyLedger {
    private val lock = mockLock()
    private val drawn = mutableListOf<ByteArray>()
    private val keyIds = mutableMapOf<String, String>()

    /** The primitives the process's crypto is built over — what derives key ids and seals; none before a launch. */
    @Volatile private var primitives: Crypto? = null

    /** The process's [Crypto]: [delegate]'s, recording every draw a key could be. */
    fun recording(delegate: Crypto): Crypto {
        primitives = delegate
        return object : Crypto by delegate {
            override fun randomBytes(count: Int): ByteArray = delegate.randomBytes(count).also { bytes ->
                if (count == EncryptedFileFormat.KEY_LENGTH) lock.locked { drawn += bytes.copyOf() }
            }
        }
    }

    /** The backend port, recording the key id each keyed create answered with an event id. */
    fun recording(delegate: Backend): Backend = object : Backend by delegate {
        override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> =
            delegate.createEvent(token, req).also { reply -> record(reply, req.keyId) }
    }

    /** [eventId]'s key, or `null` for an event this device did not create encrypted. */
    fun keyOf(eventId: String): ByteArray? {
        val hmac = primitives?.let { Hmac(it::hmacSha256) } ?: return null
        return lock.locked { keyIds[eventId]?.let { keyId -> drawn.firstOrNull { idOf(it, hmac) == keyId } } }
    }

    /**
     * [bytes] as a member would have uploaded them into the event the transfer [description] fetches for — sealed in
     * the encrypted file format when this device created that event encrypted, byte for byte as the app's own sealing
     * lays a file out; else `null` (stored as they are). The description is the app's transfer tag: source device,
     * asset, resource key and event, newline-separated.
     */
    internal fun sealed(description: String, bytes: ByteArray): ByteArray? {
        val tag = description.split("\n").takeIf { it.size == TAG_FIELDS }
        val key = tag?.let { keyOf(it[EVENT]) }
        val crypto = primitives
        if (tag == null || key == null || crypto == null) return null
        val hmac = Hmac(crypto::hmacSha256)
        val role = roleFromUploadKey(tag[RESOURCE]).wire
        val associated = EncryptedFileFormat.associatedData(tag[EVENT], tag[DEVICE], tag[ASSET], role)
        val head = FileHead(
            keyId = EncryptedFileFormat.keyIdOf(key, hmac),
            salt = crypto.randomBytes(EncryptedFileFormat.SALT_LENGTH),
            noncePrefix = crypto.randomBytes(EncryptedFileFormat.NONCE_PREFIX_LENGTH),
        )
        val fileKey = EncryptedFileFormat.fileKeyOf(key, head.salt, associated, hmac)
        var file = EncryptedFileFormat.encodeHead(head)
        var offset = 0
        var segment = 0
        do {
            val end = minOf(bytes.size, offset + EncryptedFileFormat.plaintextSegmentLength(segment))
            val last = end == bytes.size
            val nonce = EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last)
            file += crypto.aesGcmSeal(fileKey, nonce, bytes.copyOfRange(offset, end))
            offset = end
            segment++
        } while (!last)
        return file
    }

    private fun record(reply: Reply<EventCreated>, keyId: String?) {
        val created = (reply as? Reply.Ok)?.value ?: return
        if (keyId != null) lock.locked { keyIds[created.eventId] = keyId }
    }

    private fun idOf(key: ByteArray, hmac: Hmac): String =
        EncryptedFileFormat.keyIdOf(key, hmac).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val TAG_FIELDS = 4
        const val DEVICE = 0
        const val ASSET = 1
        const val RESOURCE = 2
        const val EVENT = 3
    }
}
