package app.snapsync.format

import app.snapsync.domain.asset.FileBinding
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.Base64

/**
 * **An encrypted event's sealed metadata record** — the layout alone, naming no port (decision record
 * `changes/seal-event-name`, D1–D3). What an event row holds of an encrypted event's own description, today its name,
 * which the service stores only as this record. Its TypeScript twin is `api/src/sealed-meta.ts`; both are held to
 * `test/vectors/sealed-meta.json`, produced by an implementation independent of either.
 *
 * ```
 * envelope  version (1 byte, 0x01) ‖ nonce (12 bytes) ‖ AES-256-GCM ciphertext ‖ tag (16 bytes), unpadded base64url
 * key       HKDF-SHA256(event key, no salt, "snapsync/meta/v1"), 32 bytes
 * plaintext a UTF-8 JSON object, today {"name": "…"}
 * ```
 * GCM carries no associated data: the binding is the derivation's label, as the encrypted file format binds a file —
 * and the key id needs no binding of its own, since it is derived from the very key the record opens under.
 *
 * The plaintext grows by ADDING keys only: a reader ignores the keys it does not know and a re-seal keeps them
 * ([MetaRecord]), so a field a newer build writes survives a rename by an older one. A change that is not additive is
 * a new [VERSION].
 *
 * **A photo's file name** in an encrypted event is the same envelope and record (decision record
 * `changes/seal-file-names`, D1), under a key derived PER PHOTO ([fileNameKeyOf]) — its label names the event, the
 * device, the asset and the role, so a record the service moves onto another photo opens under no key there, as a file
 * moved elsewhere does not decrypt.
 */
object SealedMetaFormat {
    const val VERSION: Byte = 1
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16

    /** The shortest envelope: an empty plaintext's. */
    const val MIN_LENGTH = 1 + NONCE_LENGTH + TAG_LENGTH

    /** The longest envelope TEXT the service stores — what bounds a record no app's rules checked. */
    const val MAX_TEXT_LENGTH = 1024

    private val LABEL = "snapsync/meta/v1".encodeToByteArray()
    private const val FILE_NAME_LABEL = "snapsync/file-name/v1"
    private const val NAME = "name"

    private val text = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** The key a record of the event whose key is [eventKey] is sealed with: [KEY_LENGTH] bytes. */
    fun metaKeyOf(eventKey: ByteArray, hmac: Hmac): ByteArray =
        Hkdf.derive(hmac, eventKey, ByteArray(0), LABEL, KEY_LENGTH)

    /** The key the name of the photo [binding] names is sealed with, in the event whose key is [eventKey]. */
    fun fileNameKeyOf(eventKey: ByteArray, binding: FileBinding, hmac: Hmac): ByteArray =
        Hkdf.derive(hmac, eventKey, ByteArray(0), fileNameLabel(binding), KEY_LENGTH)

    /** The derivation label binding a file name to its photo — the one place it is spelled. */
    fun fileNameLabel(binding: FileBinding): ByteArray =
        "$FILE_NAME_LABEL/${binding.event}/${binding.device}/${binding.asset}/${ResourceRoleText.of(binding.role)}"
            .encodeToByteArray()

    /** The envelope of [sealed] (ciphertext then tag) under [nonce], as the wire and the row carry it. */
    fun encode(nonce: ByteArray, sealed: ByteArray): String = text.encode(byteArrayOf(VERSION) + nonce + sealed)

    /** The nonce and sealed bytes of the envelope [envelope], or why it is not this format. */
    fun decode(envelope: String): EnvelopeRead {
        if (envelope.length > MAX_TEXT_LENGTH) return EnvelopeRead.Refused("longer than $MAX_TEXT_LENGTH")
        val bytes = try {
            text.decode(envelope)
        } catch (_: IllegalArgumentException) {
            return EnvelopeRead.Refused("not unpadded base64url")
        }
        return when {
            bytes.size < MIN_LENGTH -> EnvelopeRead.Refused("truncated")
            bytes[0] != VERSION -> EnvelopeRead.Refused("unknown version ${bytes[0]}")
            else -> EnvelopeRead.Read(
                nonce = bytes.copyOfRange(1, 1 + NONCE_LENGTH),
                sealed = bytes.copyOfRange(1 + NONCE_LENGTH, bytes.size),
            )
        }
    }

    /** The plaintext [record] is sealed as. */
    fun plaintextOf(record: MetaRecord): ByteArray = record.json().toString().encodeToByteArray()

    /** The record [plaintext] spells, or `null` unless it is a JSON object whose name is a string. */
    fun recordOf(plaintext: ByteArray): MetaRecord? {
        val json = try {
            // Decoded leniently: a record that authenticated is its sealer's own bytes, and an invalid sequence in it
            // reads as replacement characters rather than as no record.
            Json.parseToJsonElement(plaintext.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val name = json[NAME] as? JsonPrimitive ?: return null
        if (!name.isString) return null
        return MetaRecord(name.content, JsonObject(json - NAME))
    }

    /** A new event's record: [name] and nothing else. */
    fun recordNamed(name: String): MetaRecord = MetaRecord(name, JsonObject(emptyMap()))

    internal fun MetaRecord.json(): JsonObject = buildJsonObject {
        put(NAME, JsonPrimitive(name))
        rest.forEach { (key, value) -> put(key, value) }
    }
}

/**
 * An encrypted event's metadata, opened: its [name] — as written, not yet checked against the name rules, which are the
 * domain's — and every key this build does not know, carried untouched so a re-seal keeps them.
 */
class MetaRecord internal constructor(val name: String, internal val rest: JsonObject) {
    /** This record with its name replaced, every other key kept. */
    fun named(name: String): MetaRecord = MetaRecord(name, rest)
}

/** Whether an envelope's text is this format. */
sealed interface EnvelopeRead {
    class Read(val nonce: ByteArray, val sealed: ByteArray) : EnvelopeRead

    data class Refused(val reason: String) : EnvelopeRead
}
