package app.snapsync.data.upload

import app.snapsync.data.backend.ok
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.HttpResult
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSource
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A row's upload made a transfer of the app's own session: streamed where the session takes resources, otherwise from
 * the resource's staged file — sealed into it for an encrypted event, hashed so a failure can resume, and sent on from
 * where the backend says a failed attempt's bytes stopped. The row is requested once the session accepted it.
 */
class SessionTierTest {

    private val sha = Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256
    private val range = Routes.UploadResource.HEADER_CONTENT_RANGE

    @Test
    fun `a session that takes resources streams a plain upload, and its row is requested`() = runTest {
        val world = SessionWorld(takesResources = true)
        world.joined()
        world.network = TransferNetwork.UNRESTRICTED_ONLY

        val answer = world.tier.create(world.event, world.discovered(), SessionWorld.From)

        assertEquals(RowTransition.Transitioned, assertIs<CreateAnswer.Created>(answer).recorded)
        val made = world.session.made.single()
        assertEquals(SessionWorld.Handle, assertIs<TransferSource.Resource>(made.source).handle)
        assertEquals(world.tag(), made.tag)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, made.target.network)
        assertEquals("video/quicktime", made.target.headers["Content-Type"])
        assertIs<UploadRow.Requested>(world.row())
        assertEquals(0, world.library.exports)
    }

    @Test
    fun `a session that sends files gets the bytes staged and hashed, and sent whole`() = runTest {
        val world = SessionWorld()
        world.joined()

        assertIs<CreateAnswer.Created>(world.tier.create(world.event, world.discovered(), SessionWorld.From))

        val file = assertIs<TransferSource.File>(world.session.made.single().source)
        assertEquals("/os/${world.staged.path(world.tag())}", file.path)
        assertEquals(0, file.from)
        assertTrue(world.session.made.single().target.headers.containsKey(sha))
        assertNull(world.session.made.single().target.headers[range])
        assertTrue(world.staged.path(world.tag()) in world.files.held)
        assertTrue(world.staged.digestPath(world.tag()) in world.files.held)
    }

    @Test
    fun `an encrypted event's upload is sealed into its staged file, the plaintext gone`() = runTest {
        val world = SessionWorld(takesResources = true)
        world.joinedEncrypted()

        assertIs<CreateAnswer.Created>(world.tier.create(world.event, world.discovered(), SessionWorld.From))

        assertIs<TransferSource.File>(world.session.made.single().source)
        val staged = world.staged.path(world.tag())
        assertFalse(world.files.held.getValue(staged).contentEquals(world.library.bytes), "sealed, never the plaintext")
        assertFalse("$staged.plain" in world.files.held)
    }

    @Test
    fun `an encrypted event whose key this device does not hold uploads nothing`() = runTest {
        val world = SessionWorld()
        world.joined(Encryption.Encrypted(world.ids.mint().id))

        val answer = world.tier.create(world.event, world.discovered(), SessionWorld.From)

        assertEquals(SealVerdict.Withheld.Because.KEY_LOST, assertIs<CreateAnswer.Withheld>(answer).because)
        assertEquals(emptyList(), world.session.made)
        assertIs<UploadRow.Discovered>(world.row())
    }

    @Test
    fun `nothing is made where this device cannot be addressed`() = runTest {
        val world = SessionWorld()
        world.joined()
        val row = world.discovered()

        world.identity.read = DeviceIdRead.Absent
        assertIs<CreateAnswer.Unaddressed>(world.tier.create(world.event, row, SessionWorld.From))
        world.identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(CreateAnswer.Unaddressed("locked"), world.tier.create(world.event, row, SessionWorld.From))
        assertEquals(emptyList(), world.session.made)
    }

    @Test
    fun `a session that takes no more keeps the hashed file for the next attempt, and the row waits`() = runTest {
        val world = SessionWorld()
        world.joined()
        world.session.answer = CreateOutcome.LIMIT_EXCEEDED

        assertEquals(CreateAnswer.Full, world.tier.create(world.event, world.discovered(), SessionWorld.From))

        assertTrue(world.staged.path(world.tag()) in world.files.held)
        assertIs<UploadRow.Discovered>(world.row())
    }

    @Test
    fun `a file that cannot be hashed goes as it always did, and goes again when no transfer was made`() = runTest {
        val world = SessionWorld()
        world.joined()
        world.files.unreadable = true
        world.session.answer = CreateOutcome.FAILED

        assertIs<CreateAnswer.Failed>(world.tier.create(world.event, world.discovered(), SessionWorld.From))

        assertFalse(world.session.made.single().target.headers.containsKey(sha))
        assertFalse(world.staged.path(world.tag()) in world.files.held)
    }

    @Test
    fun `a failed attempt's file is sent on from where its bytes stand`() = runTest {
        val world = SessionWorld()
        world.joined()
        val row = world.discovered()
        world.session.answer = CreateOutcome.LIMIT_EXCEEDED
        world.tier.create(world.event, row, SessionWorld.From)
        world.session.answer = CreateOutcome.CREATED
        world.standing(HttpResult.Response(200, mapOf("Upload-Offset" to "4096"), ""))

        assertIs<CreateAnswer.Created>(world.tier.create(world.event, row, SessionWorld.From))

        assertEquals(1, world.library.exports, "the same bytes, never exported again")
        val resumed = world.session.made.last()
        assertEquals(4096, assertIs<TransferSource.File>(resumed.source).from)
        assertEquals("bytes 4096-9999/10000", resumed.target.headers[range])
    }

    @Test
    fun `bytes that already landed are sent from their last byte, and an unaskable standing makes nothing now`() =
        runTest {
            val world = SessionWorld()
            world.joined()
            val row = world.discovered()
            world.session.answer = CreateOutcome.FAILED
            world.tier.create(world.event, row, SessionWorld.From)

            world.standing(ok(204))
            world.tier.create(world.event, row, SessionWorld.From)
            assertEquals(9_999, assertIs<TransferSource.File>(world.session.made.last().source).from)

            world.standing(HttpResult.Unreachable("offline"))
            assertEquals(CreateAnswer.NotNow, world.tier.create(world.event, row, SessionWorld.From))
            assertEquals(2, world.session.made.size)
        }

    @Test
    fun `a digest whose file is gone, or that is no digest, is dropped and the bytes exported again`() = runTest {
        val world = SessionWorld()
        world.joined()
        val row = world.discovered()
        val digest = world.staged.digestPath(world.tag())

        world.files.held[digest] = "${"a".repeat(64)} 10".encodeToByteArray()
        world.tier.create(world.event, row, SessionWorld.From)
        assertEquals(1, world.library.exports)

        world.files.held[world.staged.path(world.tag())] = ByteArray(1)
        world.files.held[digest] = "not a digest".encodeToByteArray()
        world.tier.create(world.event, world.discovered("A2"), SessionWorld.From)
        world.tier.create(world.event, row, SessionWorld.From)
        assertEquals(3, world.library.exports)
    }

    @Test
    fun `an export or seal that fails, or an area that cannot hold the file, makes nothing and leaves nothing`() =
        runTest {
            val world = SessionWorld()
            world.joined()
            val row = world.discovered()

            world.library.fails = true
            assertIs<CreateAnswer.Failed>(world.tier.create(world.event, row, SessionWorld.From))
            world.library.fails = false

            world.files.unplaced = true
            assertIs<CreateAnswer.Failed>(world.tier.create(world.event, row, SessionWorld.From))
            world.files.unplaced = false

            val encrypted = SessionWorld()
            encrypted.joinedEncrypted()
            encrypted.library.writes = false
            assertIs<CreateAnswer.Failed>(
                encrypted.tier.create(encrypted.event, encrypted.discovered(), SessionWorld.From),
            )

            assertEquals(emptyList(), world.session.made + encrypted.session.made)
            assertEquals(emptyList(), world.files.held.keys.filter { it.startsWith(UPLOAD_STAGING_DIR) })
        }

    @Test
    fun `a kept digest whose file cannot be looked at is dropped, and the bytes exported again`() = runTest {
        val world = SessionWorld()
        world.joined()
        val row = world.discovered()
        world.session.answer = CreateOutcome.FAILED
        world.tier.create(world.event, row, SessionWorld.From)

        world.files.existsFails = true
        world.tier.create(world.event, row, SessionWorld.From)

        assertEquals(2, world.library.exports)
    }

    @Test
    fun `an encrypted event whose export fails, or whose plaintext the area cannot hold, makes nothing`() = runTest {
        val world = SessionWorld()
        world.joinedEncrypted()
        val row = world.discovered()

        world.library.fails = true
        assertIs<CreateAnswer.Failed>(world.tier.create(world.event, row, SessionWorld.From))
        world.library.fails = false
        world.files.unplacedSuffixes += ".plain"
        assertIs<CreateAnswer.Failed>(world.tier.create(world.event, row, SessionWorld.From))

        assertEquals(emptyList(), world.session.made)
    }
}
