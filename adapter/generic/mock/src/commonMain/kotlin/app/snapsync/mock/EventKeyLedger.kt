package app.snapsync.mock

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.FileHead
import app.snapsync.format.Hmac
import app.snapsync.model.roleFromUploadKey
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.AesKey
import app.snapsync.ports.Crypto
import app.snapsync.ports.GcmNonce
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.Event
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.runtime.WireJson
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
                if (count == EventKey.LENGTH) lock.locked { drawn += bytes.copyOf() }
            }
        }
    }

    /** The backend's transport, recording the key id each keyed create (`createEvent`) answered with an event id. */
    fun recording(delegate: HttpTransport): HttpTransport = HttpTransport { request ->
        delegate.send(request).also { result -> record(request, result) }
    }

    /** [eventId]'s key, as its invite carries it, or `null` for an event this device did not create encrypted. */
    @OptIn(MakesEventKey::class)
    fun eventKeyOf(eventId: String): EventKey? = keyOf(eventId)?.let(EventKey::of)

    /** [eventId]'s key, or `null` for an event this device did not create encrypted. */
    fun keyOf(eventId: String): ByteArray? {
        val hmac = primitives?.let { portHmac(it) } ?: return null
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
        val hmac = portHmac(crypto)
        val binding = FileBinding(
            EventId.parse(tag[EVENT])!!,
            DeviceId.parse(tag[DEVICE])!!,
            AssetId.parse(tag[ASSET])!!,
            roleFromUploadKey(tag[RESOURCE]),
        )
        val associated = EncryptedFileFormat.associatedData(binding)
        val head = FileHead.of(
            keyId = EncryptedFileFormat.keyIdOf(key, hmac),
            salt = crypto.randomBytes(EncryptedFileFormat.SALT_LENGTH),
            noncePrefix = crypto.randomBytes(EncryptedFileFormat.NONCE_PREFIX_LENGTH),
        )!!
        val fileKey = AesKey(EncryptedFileFormat.fileKeyOf(key, head.salt, associated, hmac))
        var file = EncryptedFileFormat.encodeHead(head)
        var offset = 0
        var segment = 0
        do {
            val end = minOf(bytes.size, offset + EncryptedFileFormat.plaintextSegmentLength(segment))
            val last = end == bytes.size
            val nonce = GcmNonce(EncryptedFileFormat.segmentNonce(head.noncePrefix, segment, last))
            file += crypto.aesGcmSeal(fileKey, nonce, bytes.copyOfRange(offset, end))
            offset = end
            segment++
        } while (!last)
        return file
    }

    private fun record(request: HttpRequest, result: HttpResult) {
        val created = result as? HttpResult.Response ?: return
        if (request.method != Routes.CreateEvent.METHOD || !request.url.substringBefore('?').endsWith(Routes.CreateEvent.TEMPLATE)) return
        if (created.status != CREATED) return
        val keyId = request.body?.let {
            runCatchingCancellable { WireJson.decodeFromString(EventCreate.serializer(), it) }.getOrNull()
        }
            ?.keyId ?: return
        val eventId = runCatchingCancellable { WireJson.decodeFromString(Event.serializer(), created.body) }.getOrNull()
            ?.eventId ?: return
        lock.locked { keyIds[eventId] = keyId }
    }

    private fun idOf(key: ByteArray, hmac: Hmac): String =
        EncryptedFileFormat.keyIdOf(key, hmac).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val CREATED = 201
        const val TAG_FIELDS = 4
        const val DEVICE = 0
        const val ASSET = 1
        const val RESOURCE = 2
        const val EVENT = 3
    }
}

/** The format's HMAC over the process's primitives. */
private fun portHmac(crypto: Crypto): Hmac = object : Hmac {
    override fun mac(key: ByteArray, message: ByteArray): ByteArray = crypto.hmacSha256(key, message)
}
