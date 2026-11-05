package app.snapsync.integration

import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.HeadRead
import app.snapsync.levers.WorldEvent
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

// What someone holding an encrypted event's stored objects can do with them, given a key or not: open them as a
// member's app does (the encrypted file format, `docs/architecture.md`), over the JDK's own primitives — independent of
// the app's crypto adapters, and of the mock that stores the bytes.

private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** The event key an encrypted event's [invite] carries in its `#k=` fragment. */
fun keyOfInvite(invite: String): ByteArray = urlSafe.decode(invite.substringAfter("#k="))

/** The bytes a header carries base64url, as the edge seal's headers carry them. */
fun base64UrlDecoded(value: String): ByteArray = urlSafe.decode(value)

/** The object [stored] holds, as the storage holds it. */
fun WorldEvent.BytesStored.storedBytes(): ByteArray = Base64.decode(checkNotNull(bytes) { "$this stored no bytes" })

/** HMAC-SHA256 over the JDK. */
private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

/** HKDF-SHA256 (RFC 5869) for one 32-byte key: extract under [salt], expand with [info] — one block. */
private fun hkdf32(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray =
    hmac(hmac(salt, ikm), info + byteArrayOf(1))

/** What a stored file is: the event, device, asset and role it is the object of. */
data class Binding(val event: String, val device: String, val asset: String, val role: String) {
    /** The associated data a file of this binding is sealed under. */
    val associatedData: ByteArray get() = "snapsync/v1/$event/$device/$asset/$role".encodeToByteArray()
}

/**
 * The key one file is sealed with: what [eventKey] derives for the file whose opening bytes are [head], bound to
 * [binding] — `null` when [head] is not the format's.
 */
fun fileKeyOf(eventKey: ByteArray, head: ByteArray, binding: Binding): ByteArray? {
    val read = (EncryptedFileFormat.decodeHead(head) as? HeadRead.Read)?.head ?: return null
    return hkdf32(eventKey, read.salt, binding.associatedData)
}

/**
 * [object]'s bytes opened with [eventKey], as a member's app opens a stored photo: its plaintext, or `null` when the key
 * does not open it — every segment's tag checked.
 */
fun WorldEvent.BytesStored.openedWith(eventKey: ByteArray): ByteArray? {
    val sealed = storedBytes()
    val read = (EncryptedFileFormat.decodeHead(sealed) as? HeadRead.Read)?.head ?: return null
    val key = fileKeyOf(eventKey, sealed, Binding(event, device, asset, role)) ?: return null
    val plain = ByteArrayOutputStream()
    var at = EncryptedFileFormat.HEAD_LENGTH
    var segment = 0
    while (true) {
        val length = EncryptedFileFormat.ciphertextSegmentLength(segment)
        val last = sealed.size - at <= length
        val chunk = sealed.copyOfRange(at, if (last) sealed.size else at + length)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = EncryptedFileFormat.segmentNonce(read.noncePrefix, segment, last)
        val spec = GCMParameterSpec(EncryptedFileFormat.TAG_LENGTH * Byte.SIZE_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        try {
            plain.write(cipher.doFinal(chunk))
        } catch (_: AEADBadTagException) {
            return null
        }
        if (last) return plain.toByteArray()
        at += length
        segment++
    }
}

/** Whether [bytes] holds [part] anywhere. */
fun ByteArray.holds(part: ByteArray): Boolean =
    part.isNotEmpty() && (0..size - part.size).any { at -> part.indices.all { this[at + it] == part[it] } }

/** Every way a text could carry [key]: base64 and base64url, padded or not, and hex either case. */
fun spellingsOf(key: ByteArray): List<String> {
    val hex = key.joinToString("") { "%02x".format(it) }
    return listOf(
        Base64.encode(key),
        Base64.withPadding(Base64.PaddingOption.ABSENT).encode(key),
        Base64.UrlSafe.encode(key),
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(key),
        hex,
        hex.uppercase(),
    )
}

/** Whether [request] carries [key] — in its address, a header or its body, in any spelling. */
fun carries(request: WorldEvent.BackendReceived, key: ByteArray): Boolean {
    val texts = listOfNotNull(request.url, request.body) + request.headers.values
    val spellings = spellingsOf(key)
    return texts.any { text -> spellings.any { it in text } }
}

/** The file a byte route's [url] names (`…/events/<e>/files/devices/<d>/<a>/<role>?…`). */
fun bindingOf(url: String): Binding {
    val event = url.substringAfter("/events/").substringBefore('/')
    val (device, asset, role) = url.substringAfter("/files/devices/").substringBefore('?').split('/')
    return Binding(event, device, asset, role)
}
