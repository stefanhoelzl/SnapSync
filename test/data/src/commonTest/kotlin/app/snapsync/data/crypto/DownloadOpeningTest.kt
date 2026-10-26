package app.snapsync.data.crypto

import app.snapsync.application.membership.Device
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.Membership
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryFiles
import app.snapsync.ports.FileArea
import app.snapsync.ports.SecureStoreRead
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SOURCE = "A0000000-0000-4000-8000-00000000000A"
private const val OTHER_EVENT = "B0000000-0000-4000-8000-00000000000B"

/**
 * How a downloaded resource is opened (capability `invite-link`, "Only the whole invite opens an event's photos"): only
 * with the joined event's key, as the resource the union named; a file that does not open is discarded and downloaded
 * again — and its fourth failure in a row is reported once; a lost key is the membership's state and counts toward no
 * report.
 */
@OptIn(CompositionOnly::class)
class DownloadOpeningTest {

    private val lines = mutableListOf<LogLine>()

    @BeforeTest
    fun record() = Log.install(
        listOf(object : LogWriter {
            override fun write(line: LogLine) {
                lines += line
            }
        }),
    )

    @AfterTest
    fun stop() = Log.install(emptyList())

    private fun logged(severity: Severity) = lines.count { it.tag == "DownloadOpening" && it.severity == severity }

    private val device = Device()
    private val crypto = fakeCrypto()
    private val ids = EventKeyIds(crypto)
    private val secure = RecordingSecureStore()
    private val keys = EventKeyStore(secure, ids)
    private val shared = mutableMapOf<String, ByteArray>()
    private val files = inMemoryFiles(shared = shared)
    private val cipher = FileCipher(crypto, files)
    private val opening = DownloadOpening(device.membership, keys, cipher, files)
    private val key = ids.mint()
    private val photo = ByteArray(1000) { (it * 7).toByte() }

    private fun joined(encryption: Encryption) {
        val plain = active()
        device.membership.enter(enrolled(Membership.Active(plain.event.copy(encryption = encryption), plain.share)))
    }

    /** [photo], sealed by [SOURCE] for the joined event, waiting at its landing. */
    private fun landed(): Landing.Sealed {
        val landing = assertIs<Landing.Sealed>(opening.landing("staged/p"))
        shared["plain"] = photo
        val binding = FileBinding(testEventId(EVENT), testDeviceId(SOURCE), testAssetId("ASSET1"), ResourceRole.PRIMARY)
        cipher.seal(key, binding, FileArea.SHARED, "plain", landing.sealedPath)
        return landing
    }

    private suspend fun open(landed: Landing.Sealed, fetchedFor: String? = EVENT, role: ResourceRole = ResourceRole.PRIMARY) =
        opening.open(landed, testDeviceId(SOURCE), testAssetId("ASSET1"), role, fetchedFor?.let(::testEventId))

    @Test
    fun `a plain event's download lands where it is staged, open as it arrived`() = runTest {
        joined(Encryption.Plain)
        val landing = assertIs<Landing.Plain>(opening.landing("staged/p"))
        assertEquals("staged/p", landing.stagedAt)
        assertEquals(
            OpenAnswer.Opened,
            opening.open(landing, testDeviceId(SOURCE), testAssetId("ASSET1"), ResourceRole.PRIMARY, null),
        )
        assertIs<Landing.Plain>(
            DownloadOpening(Device().membership, keys, cipher, files).landing("x"),
            "nothing joined",
        )
    }

    @Test
    fun `an encrypted event's download opens into its path, and the sealed file goes`() = runTest {
        joined(Encryption.Encrypted(key.id))
        keys.keep(KeyToKeep.Key(key), testEventId(EVENT))
        val landed = landed()
        assertEquals("staged/p.sealed", landed.stagedAt)
        assertEquals(OpenAnswer.Opened, open(landed))
        assertContentEquals(photo, shared.getValue("staged/p"))
        assertFalse("staged/p.sealed" in shared)
        assertEquals(listOf("staged/p.sealed", "staged/p.part"), SealedLanding.filesOf("staged/p"))

        val again = landed()
        assertEquals(OpenAnswer.Opened, open(again, fetchedFor = null), "fetched before transfers named their event")
    }

    @Test
    fun `a file that never opens is counted, and reported once however often it is retried`() = runTest {
        joined(Encryption.Encrypted(key.id))
        keys.keep(KeyToKeep.Key(key), testEventId(EVENT))
        repeat(6) { assertEquals(OpenAnswer.NotOpened, open(landed(), role = ResourceRole.LIVE)) }
        assertEquals(1, logged(Severity.Error), "the fourth failure in a row, once")
        val why = lines.flatMap { it.fields }.filter { it.name == "why" }.map { it.value.toString() }
        assertTrue(
            why.isNotEmpty() && why.all { "authenticate" in it },
            "opened as another resource, it is damaged: $why",
        )
        assertEquals(5, logged(Severity.Warn))
        assertEquals(OpenAnswer.Opened, open(landed()), "a file that opens ends the count")
    }

    @Test
    fun `every way a download cannot be opened leaves it unstaged`() = runTest {
        shared["staged/p.sealed"] = photo
        assertEquals(OpenAnswer.NotOpened, open(Landing.Sealed("staged/p.sealed", "staged/p")), "not joined")
        joined(Encryption.Encrypted(key.id))
        assertEquals(OpenAnswer.NotOpened, open(landed()), "no key kept")
        assertEquals(
            listOf(0, 2),
            listOf(logged(Severity.Error), logged(Severity.Warn)),
            "a lost key is warned, uncounted",
        )
        secure.answers[EncryptionSlots.EVENT_KEY] = SecureStoreRead.Unavailable("locked")
        keys.reload()
        assertEquals(OpenAnswer.NotOpened, open(landed()), "the key cannot be read now")
        keys.keep(KeyToKeep.Key(key), testEventId(EVENT))
        assertEquals(OpenAnswer.NotOpened, open(landed(), fetchedFor = OTHER_EVENT), "fetched for another event")
        assertEquals(OpenAnswer.NotOpened, open(Landing.Sealed("missing", "staged/m")), "unreadable")
        val foreign = assertIs<Landing.Sealed>(opening.landing("staged/p"))
        shared["plain"] = photo
        cipher.seal(
            ids.mint(),
            FileBinding(testEventId(EVENT), testDeviceId(SOURCE), testAssetId("ASSET1"), ResourceRole.PRIMARY),
            FileArea.SHARED,
            "plain",
            foreign.sealedPath,
        )
        assertEquals(OpenAnswer.NotOpened, open(foreign), "a file of another key")
        assertEquals(
            OpenAnswer.NotOpened,
            open(Landing.Sealed("staged/p.sealed", "staged/p")),
            "nothing there any more",
        )
        val unreadable = MembershipRepository(SharedDatabase(DatabasesMock().port(), legacy = null))
        assertIs<Landing.Plain>(
            DownloadOpening(unreadable, keys, cipher, files).landing("x"),
            "a membership that cannot be read",
        )
        assertFalse("staged/p" in shared)
    }
}
