package app.snapsync.crypto

import app.snapsync.data.crypto.EdgeSeal
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.OpenOutcome
import app.snapsync.data.crypto.SealOutcome
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.files.JvmFiles
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.HeadRead
import app.snapsync.format.Hkdf
import app.snapsync.format.Hmac
import app.snapsync.format.ResourceRoleText
import app.snapsync.ports.Crypto
import app.snapsync.ports.FileArea
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Files as Nio

/**
 * The encrypted file format's Kotlin half — [EncryptedFileFormat] framed by [FileCipher] over the real [JcaCrypto] and
 * [JvmFiles] — held to the shared reference vectors (`test/vectors/encrypted-file.json`, produced independently of this
 * code; the TypeScript half is held to the same file) and to Google Tink itself, which must open what is written here
 * and whose output must open here. Exact bytes throughout: the edge's encryption of an extension upload must be what
 * the app writes, so "it round-trips" would prove nothing.
 */
@OptIn(MakesEventKey::class)
class EncryptedFileFormatTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("snapsync.vectorsDir"), "encrypted-file.json").readText(),
    ).jsonObject

    private val dir: File = Nio.createTempDirectory("encrypted-file").toFile()
    private val files = JvmFiles(shared = dir, private = null)
    private val area = FileArea.SHARED

    private fun String.hex(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.int
    private fun pattern(n: Int, f: (Int) -> Int) = ByteArray(n) { f(it).toByte() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    /** The real primitives, but the "random" draws are the ones a vector was made with. */
    private fun drawing(vararg draws: ByteArray): Crypto {
        val queue = ArrayDeque(draws.toList())
        return object : Crypto by JcaCrypto() {
            override fun randomBytes(count: Int): ByteArray = queue.removeFirst().also { check(it.size == count) }
        }
    }

    private val jcaHmac = object : Hmac {
        override fun mac(key: ByteArray, message: ByteArray): ByteArray = JcaCrypto().hmacSha256(key, message)
    }

    /** [bytes] as an event key, with its id. */
    private fun identified(bytes: ByteArray): IdentifiedKey = EventKeyIds(JcaCrypto()).identify(EventKey.of(bytes)!!)

    /** What a vector's associated data names: `snapsync/v1/<event>/<device>/<asset>/<role>`. */
    private fun bindingOf(associatedData: ByteArray): FileBinding =
        associatedData.decodeToString().removePrefix("snapsync/v1/").split("/").let { parts ->
            val (event, device, asset) = parts
            val role = parts[3]
            FileBinding(
                EventId.parse(event)!!,
                DeviceId.parse(device)!!,
                AssetId.parse(asset)!!,
                ResourceRoleText.parse(role)!!,
            )
        }

    private fun headerBytes(seal: EdgeSeal, header: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(seal.headers().getValue(header))

    /** The resource a test file is, as [role]. */
    private fun binding(role: ResourceRole) = FileBinding(
        EventId.parse("0a1b2c3d-0000-4000-8000-000000000001")!!,
        DeviceId.parse("0a1b2c3d-0000-4000-8000-000000000002")!!,
        AssetId.parse("a")!!,
        role,
    )

    private fun write(name: String, bytes: ByteArray): String = name.also { File(dir, it).writeBytes(bytes) }
    private fun read(name: String): ByteArray = File(dir, name).readBytes()

    private fun assertPinned(actual: ByteArray, v: JsonObject, prefix: String = "ciphertext") {
        if (v.containsKey(prefix)) {
            assertEquals(v.str(prefix), actual.hex())
        } else {
            assertEquals(v.num("${prefix}Length"), actual.size)
            assertEquals(v.str("${prefix}Head"), actual.copyOf(64).hex())
            assertEquals(v.str("${prefix}Sha256"), sha256(actual))
        }
    }

    @Test
    fun `HKDF is RFC 5869`() {
        val hmac = jcaHmac
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.derive(
                hmac,
                ByteArray(22) { 0x0b },
                "000102030405060708090a0b0c".hex(),
                "f0f1f2f3f4f5f6f7f8f9".hex(),
                42,
            ).hex(),
            "test case 1",
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.derive(hmac, ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42).hex(),
            "test case 3: no salt, no info",
        )
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hkdf.derive(hmac, ikm, salt, info, 82).hex(),
            "test case 2: longer inputs, three blocks",
        )
    }

    @Test
    fun `the key id is the vectors'`() {
        vectors.getValue("keyId").jsonArray.map { it.jsonObject }.forEach { v ->
            assertEquals(v.str("keyId"), identified(v.str("eventKey").hex()).id.value)
        }
    }

    @Test
    fun `every stream vector of the app's segment size is written to its exact bytes and opens again`() {
        val ours = vectors.getValue("stream").jsonArray.map { it.jsonObject }
            .filter { it.num("segmentSize") == EncryptedFileFormat.SEGMENT_LENGTH }
        assertTrue(ours.isNotEmpty())
        ours.forEachIndexed { i, v ->
            val key = identified(v.str("ikm").hex())
            val ad = v.str("associatedData").hex()
            val binding = bindingOf(ad)
            assertContentEquals(
                ad,
                EncryptedFileFormat.associatedData(binding),
                "the vector's resource, spelled as ours",
            )
            val plain = pattern(v.num("plaintextLength")) { it * 7 + 3 }
            val cipher = FileCipher(drawing(v.str("salt").hex(), v.str("noncePrefix").hex()), files)
            assertEquals(SealOutcome.Sealed, cipher.seal(key, binding, area, write("plain$i", plain), "sealed$i"))
            val sealed = read("sealed$i")
            assertPinned(sealed.copyOfRange(EncryptedFileFormat.PREFIX_LENGTH, sealed.size), v)
            assertEquals(OpenOutcome.Opened, cipher.open(key, binding, area, "sealed$i", "opened$i"))
            assertContentEquals(plain, read("opened$i"))
        }
    }

    @Test
    fun `the reference file is written to its exact bytes`() {
        val v = vectors.getValue("file").jsonArray.first().jsonObject
        val key = identified(v.str("eventKey").hex())
        val binding = FileBinding(
            EventId.parse(v.str("eventId"))!!,
            DeviceId.parse(v.str("deviceId"))!!,
            AssetId.parse(v.str("assetId"))!!,
            ResourceRoleText.parse(v.str("role"))!!,
        )
        val plain = pattern(v.num("plaintextLength")) { it * 13 + 1 }
        val cipher = FileCipher(drawing(v.str("salt").hex(), v.str("noncePrefix").hex()), files)
        cipher.seal(key, binding, area, write("plain", plain), "sealed")
        assertPinned(read("sealed"), v, prefix = "file")
    }

    @Test
    fun `Tink opens what is written here, and what Tink writes opens here`() {
        val eventKey = ByteArray(32) { (it * 3).toByte() }
        val key = identified(eventKey)
        val binding = binding(ResourceRole.PRIMARY)
        val ad = EncryptedFileFormat.associatedData(binding)
        val tink = AesGcmHkdfStreaming(eventKey, "HmacSha256", 32, EncryptedFileFormat.SEGMENT_LENGTH, 0)
        val cipher = FileCipher(JcaCrypto(), files)
        val first = EncryptedFileFormat.plaintextSegmentLength(0)
        val rest = EncryptedFileFormat.plaintextSegmentLength(1)
        for (n in listOf(0, 1, first, first + 1, first + rest, first + 3 * rest + 17, 1_000_000)) {
            val plain = pattern(n) { it * 31 + n }
            cipher.seal(key, binding, area, write("p$n", plain), "s$n")
            val ours = read("s$n")
            val opened = tink.newDecryptingStream(
                ByteArrayInputStream(ours, EncryptedFileFormat.PREFIX_LENGTH, ours.size),
                ad,
            ).readAllBytes()
            assertContentEquals(plain, opened, "Tink opens $n bytes")

            val tinkWritten = ByteArrayOutputStream().also { out ->
                tink.newEncryptingStream(
                    out,
                    ad,
                ).use { it.write(plain) }
            }.toByteArray()
            write(
                "t$n",
                byteArrayOf(EncryptedFileFormat.VERSION) + EncryptedFileFormat.keyIdOf(eventKey, jcaHmac) + tinkWritten,
            )
            assertEquals(OpenOutcome.Opened, cipher.open(key, binding, area, "t$n", "o$n"), "Tink's $n bytes open")
            assertContentEquals(plain, read("o$n"))
        }
    }

    @Test
    fun `a damaged, cut, extended, moved or foreign file never yields a byte`() {
        val eventKey = identified(ByteArray(32) { 1 })
        val primary = binding(ResourceRole.PRIMARY)
        val cipher = FileCipher(JcaCrypto(), files)
        cipher.seal(eventKey, primary, area, write("plain", pattern(200_000) { it }), "sealed")
        val sealed = read("sealed")
        fun opens(bytes: ByteArray, key: IdentifiedKey = eventKey, boundTo: FileBinding = primary): OpenOutcome {
            write("case", bytes)
            File(dir, "out").delete()
            return cipher.open(key, boundTo, area, "case", "out").also {
                if (it != OpenOutcome.Opened) {
                    assertTrue(
                        !File(dir, "out").exists() && !File(dir, "out.part").exists(),
                        "nothing written for $it",
                    )
                }
            }
        }
        assertIs<OpenOutcome.Damaged>(
            opens(sealed.copyOf().also { it[sealed.size - 100] = (it[sealed.size - 100] + 1).toByte() }),
        )
        val boundary = EncryptedFileFormat.HEAD_LENGTH + EncryptedFileFormat.ciphertextSegmentLength(0)
        assertIs<OpenOutcome.Damaged>(
            opens(sealed.copyOf(boundary)),
            "cut at a segment boundary: the new last was sealed as not-last",
        )
        assertIs<OpenOutcome.Damaged>(opens(sealed + byteArrayOf(0)))
        assertIs<OpenOutcome.Damaged>(
            opens(sealed, boundTo = binding(ResourceRole.LIVE)),
        )
        assertEquals(OpenOutcome.OtherKey, opens(sealed, key = identified(ByteArray(32))))
        assertIs<OpenOutcome.Damaged>(opens(sealed.copyOf().also { it[0] = 2 }), "a future format version")
        assertIs<OpenOutcome.Damaged>(opens(sealed.copyOf(10)), "shorter than the head")
        assertIs<OpenOutcome.Unreadable>(cipher.open(eventKey, primary, area, "missing", "out"))
        assertEquals(OpenOutcome.Opened, opens(sealed))
    }

    @Test
    fun `a seal for elsewhere carries a fresh head and only that file's key`() {
        val eventKey = ByteArray(32) { 9 }
        val binding = binding(ResourceRole.PRIMARY)
        val cipher = FileCipher(JcaCrypto(), files)
        val seal = cipher.edgeSeal(identified(eventKey), binding)
        val head = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(headerBytes(seal, EncryptedFileFormat.FILE_HEAD_HEADER)),
        ).head
        assertContentEquals(EncryptedFileFormat.keyIdOf(eventKey, jcaHmac), head.keyId)
        val fileKey = headerBytes(seal, EncryptedFileFormat.FILE_KEY_HEADER)
        assertContentEquals(
            EncryptedFileFormat.fileKeyOf(eventKey, head.salt, EncryptedFileFormat.associatedData(binding), jcaHmac),
            fileKey,
        )
        assertTrue(!fileKey.contentEquals(eventKey))
        assertEquals("EdgeSeal(***)", seal.toString(), "an edge seal never renders its key")
        val again = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(
                headerBytes(cipher.edgeSeal(identified(eventKey), binding), EncryptedFileFormat.FILE_HEAD_HEADER),
            ),
        ).head
        assertTrue(!again.salt.contentEquals(head.salt), "a fresh salt per file")
    }
}
