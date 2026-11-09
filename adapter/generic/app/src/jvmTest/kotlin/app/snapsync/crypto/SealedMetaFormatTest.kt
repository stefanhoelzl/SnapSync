package app.snapsync.crypto

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.MetaCipher
import app.snapsync.data.crypto.MetaOpening
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.format.Hmac
import app.snapsync.format.SealedMetaFormat
import app.snapsync.ports.Crypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sealed metadata record's Kotlin half — [SealedMetaFormat] sealed and opened by [MetaCipher] over the real
 * [JcaCrypto] — held to the shared reference vectors (`test/vectors/sealed-meta.json`, produced independently of this
 * code; the TypeScript half is held to the same file). Exact envelopes: the site opens what the app seals.
 */
@OptIn(MakesEventKey::class)
class SealedMetaFormatTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("snapsync.vectorsDir"), "sealed-meta.json").readText(),
    ).jsonObject

    private fun String.hex(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }

    private fun identified(bytes: ByteArray): IdentifiedKey = EventKeyIds(JcaCrypto()).identify(EventKey.of(bytes)!!)

    /** The real primitives, but the nonce is the one a vector was made with. */
    private fun drawing(nonce: ByteArray): Crypto = object : Crypto by JcaCrypto() {
        override fun randomBytes(count: Int): ByteArray = nonce.also { check(it.size == count) }
    }

    private val jcaHmac = object : Hmac {
        override fun mac(key: ByteArray, message: ByteArray): ByteArray = JcaCrypto().hmacSha256(key, message)
    }

    @Test
    fun `the record's key is the vectors'`() {
        list("metaKey").forEach { v ->
            assertEquals(v.str("metaKey"), SealedMetaFormat.metaKeyOf(v.str("eventKey").hex(), jcaHmac).hex())
            assertEquals(v.str("keyId"), identified(v.str("eventKey").hex()).id.value)
        }
    }

    @Test
    fun `every seal vector is written to its exact envelope and opens to its record`() {
        val seals = list("seal")
        assertTrue(seals.isNotEmpty())
        seals.forEach { v ->
            val key = identified(v.str("eventKey").hex())
            val record = SealedMetaFormat.recordOf(v.str("plaintext").encodeToByteArray())!!
            assertEquals(
                v.str("envelope"),
                MetaCipher(drawing(v.str("nonce").hex())).seal(key, record),
                "the vector's record seals to its envelope: ${v.str("plaintext")}",
            )
            val opened = assertIs<MetaOpening.Opened>(MetaCipher(JcaCrypto()).open(key, v.str("envelope")))
            assertEquals(v.str("plaintext"), SealedMetaFormat.plaintextOf(opened.record).decodeToString())
        }
    }

    @Test
    fun `a rename keeps every key this build does not know`() {
        val v = list("seal").single { it.str("plaintext").contains("future") }
        val key = identified(v.str("eventKey").hex())
        val cipher = MetaCipher(JcaCrypto())
        val opened = assertIs<MetaOpening.Opened>(cipher.open(key, v.str("envelope"))).record
        val renamed = assertIs<MetaOpening.Opened>(
            cipher.open(key, cipher.seal(key, opened.named("Ski Trip 2"))),
        ).record
        assertEquals("Ski Trip 2", renamed.name)
        assertEquals(
            Json.parseToJsonElement("""{"name":"Ski Trip 2","future":{"kept":true}}"""),
            Json.parseToJsonElement(SealedMetaFormat.plaintextOf(renamed).decodeToString()),
        )
    }

    @Test
    fun `every reject vector is damaged, never opened`() {
        val rejects = list("reject")
        assertTrue(rejects.isNotEmpty())
        rejects.forEach { v ->
            assertIs<MetaOpening.Damaged>(
                MetaCipher(JcaCrypto()).open(identified(v.str("eventKey").hex()), v.str("envelope")),
                v.str("reason"),
            )
        }
    }

    @Test
    fun `a seal draws a fresh nonce every time`() {
        val key = identified(ByteArray(32) { 7 })
        val cipher = MetaCipher(JcaCrypto())
        val record = SealedMetaFormat.recordNamed("Ana's 30th")
        assertNotEquals(cipher.seal(key, record), cipher.seal(key, record))
    }

    @Test
    fun `an envelope longer than the cap, padded, or not base64url is refused before any cipher runs`() {
        val key = identified(ByteArray(32) { 7 })
        val cipher = MetaCipher(JcaCrypto())
        val good = cipher.seal(key, SealedMetaFormat.recordNamed("x"))
        listOf("$good==", "a".repeat(SealedMetaFormat.MAX_TEXT_LENGTH + 1), "not base64!", "").forEach {
            assertIs<MetaOpening.Damaged>(cipher.open(key, it), it.take(20))
        }
    }
}
