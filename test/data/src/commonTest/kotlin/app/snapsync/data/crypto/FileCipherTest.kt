package app.snapsync.data.crypto

import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.format.HeadRead
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryFiles
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val AREA = FileArea.SHARED
private const val EVENT = "E0000000-0000-4000-8000-000000000001"
private const val DEVICE = "A0000000-0000-4000-8000-00000000000A"

/**
 * An encrypted event's files, sealed and opened a segment at a time (capability `invite-link`, "Only the whole invite
 * opens an event's photos"): a file opens only under its event's key and as the resource it was sealed as, a file
 * that is cut, extended or altered yields no byte, and any read or write the files refuse leaves nothing at `<to>`. The
 * bytes themselves are held to the reference vectors and to Tink by `:adapter:generic:app`'s `EncryptedFileFormatTest`.
 */
class FileCipherTest {

    private val crypto = fakeCrypto()
    private val ids = EventKeyIds(crypto)
    private val key = ids.mint()
    private val shared = mutableMapOf<String, ByteArray>()
    private val files = Scripted(inMemoryFiles(shared = shared))
    private val cipher = FileCipher(crypto, files)

    private fun binding(role: ResourceRole = ResourceRole.PRIMARY) =
        FileBinding(testEventId(EVENT), testDeviceId(DEVICE), testAssetId("ASSET1"), role)

    /** Two plaintext segments and a little: the first, a whole one, and a last. */
    private val photo =
        ByteArray(EncryptedFileFormat.plaintextSegmentLength(0) + EncryptedFileFormat.plaintextSegmentLength(1) + 7) {
            (it * 31).toByte()
        }

    private fun sealedPhoto(): ByteArray {
        shared["p"] = photo
        assertEquals(SealOutcome.Sealed, cipher.seal(key, binding(), AREA, "p", "s"))
        return shared.getValue("s")
    }

    @Test
    fun `a sealed file opens again as the resource it was sealed as, under its event's key`() {
        val sealed = sealedPhoto()
        assertFalse("s.part" in shared, "the part is moved into place")
        assertEquals(OpenOutcome.Opened, cipher.open(key, binding(), AREA, "s", "o"))
        assertContentEquals(photo, shared.getValue("o"))
        assertContentEquals(photo, shared.getValue("p"), "the plaintext is left as it was")

        shared["empty"] = ByteArray(0)
        assertEquals(SealOutcome.Sealed, cipher.seal(key, binding(), AREA, "empty", "e"))
        assertEquals(OpenOutcome.Opened, cipher.open(key, binding(), AREA, "e", "eo"), "an empty file is one segment")
        assertContentEquals(ByteArray(0), shared.getValue("eo"))
        assertTrue(sealed.size > photo.size)
    }

    @Test
    fun `another key, another resource, or any changed byte opens nothing`() {
        val sealed = sealedPhoto()
        assertEquals(OpenOutcome.OtherKey, cipher.open(ids.mint(), binding(), AREA, "s", "o"))
        assertIs<OpenOutcome.Damaged>(cipher.open(key, binding(ResourceRole.LIVE), AREA, "s", "o"), "another role")
        shared["cut"] = sealed.copyOf(EncryptedFileFormat.HEAD_LENGTH + EncryptedFileFormat.ciphertextSegmentLength(0))
        assertIs<OpenOutcome.Damaged>(cipher.open(key, binding(), AREA, "cut", "o"), "cut at a segment boundary")
        shared["last"] = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertIs<OpenOutcome.Damaged>(cipher.open(key, binding(), AREA, "last", "o"), "the last segment changed")
        shared["head"] = sealed.copyOf().also { it[0] = 2 }
        assertEquals(
            "unknown format version 2",
            assertIs<OpenOutcome.Damaged>(cipher.open(key, binding(), AREA, "head", "o")).reason,
        )
        assertFalse("o" in shared || "o.part" in shared, "nothing written for any of them")
    }

    @Test
    fun `a file that cannot be read or written is unreadable, never damaged, and leaves nothing behind`() {
        sealedPhoto()
        assertIs<OpenOutcome.Unreadable>(cipher.open(key, binding(), AREA, "missing", "o"), "no head to read")
        files.failReadsPast = EncryptedFileFormat.HEAD_LENGTH.toLong()
        assertIs<OpenOutcome.Unreadable>(cipher.open(key, binding(), AREA, "s", "o"), "a segment that cannot be read")
        files.failReadsPast = null

        for (append in 1..3) {
            files.failAppend = append
            assertIs<OpenOutcome.Unreadable>(cipher.open(key, binding(), AREA, "s", "o"), "plaintext append $append")
        }
        files.failAppend = null
        files.failMove = true
        assertIs<OpenOutcome.Unreadable>(cipher.open(key, binding(), AREA, "s", "o"), "the part not moved into place")
        files.failMove = false
        assertFalse("o" in shared || "o.part" in shared)

        shared["p"] = photo
        for (append in 1..4) {
            files.failAppend = append
            assertIs<SealOutcome.Failed>(cipher.seal(key, binding(), AREA, "p", "t"), "sealed append $append")
        }
        files.failAppend = null
        files.failMove = true
        assertIs<SealOutcome.Failed>(cipher.seal(key, binding(), AREA, "p", "t"))
        files.failMove = false
        assertEquals(
            FileResult.NotFound,
            assertIs<SealOutcome.Failed>(cipher.seal(key, binding(), AREA, "none", "t")).result,
        )
        assertFalse("t" in shared || "t.part" in shared)
    }

    @Test
    fun `a part a crash left behind is never built on`() {
        shared["s.part"] = byteArrayOf(9, 9, 9)
        sealedPhoto()
        assertEquals(OpenOutcome.Opened, cipher.open(key, binding(), AREA, "s", "o"))
    }

    @Test
    fun `an edge seal carries a fresh head and only that one file's key, and renders neither`() {
        val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val seal = cipher.edgeSeal(key, binding())
        val headers = seal.headers()
        val head = assertIs<HeadRead.Read>(
            EncryptedFileFormat.decodeHead(decoder.decode(headers.getValue(EncryptedFileFormat.FILE_HEAD_HEADER))),
        ).head
        assertEquals(key.id.value, app.snapsync.format.EventKeyIdText.of(head.keyId).value)
        assertEquals(32, decoder.decode(headers.getValue(EncryptedFileFormat.FILE_KEY_HEADER)).size)
        assertEquals("EdgeSeal(***)", seal.toString())
        assertFalse(
            headers.getValue(EncryptedFileFormat.FILE_HEAD_HEADER) == cipher.edgeSeal(key, binding()).headers().getValue(EncryptedFileFormat.FILE_HEAD_HEADER),
            "a fresh salt per file",
        )
    }
}

/** [inner], with levers: the [failAppend]th append fails, the move fails, a read past [failReadsPast] fails. */
private class Scripted(private val inner: Files) : Files by inner {
    var failAppend: Int? = null
    var failMove = false
    var failReadsPast: Long? = null
    private var appends = 0

    override fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> {
        appends++
        return when (failAppend) {
            null -> inner.append(area, path, bytes)
            else -> if (appends == failAppend) FileResult.Failed("refused") else inner.append(area, path, bytes)
        }
    }

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> {
        val past = failReadsPast
        return if (past != null && offset >= past) {
            FileResult.Denied(
                "locked",
            )
        } else {
            inner.readRange(area, path, offset, maxBytes)
        }
    }

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> =
        if (failMove) FileResult.Failed("refused") else inner.move(area, from, to)

    override fun delete(area: FileArea, path: String): FileResult<Unit> {
        appends = 0
        return inner.delete(area, path)
    }
}
