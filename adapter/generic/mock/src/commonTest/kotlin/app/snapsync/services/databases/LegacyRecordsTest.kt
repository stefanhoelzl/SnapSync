package app.snapsync.services.databases

import app.snapsync.data.membership.EventCheck
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.PendingLeaves
import app.snapsync.data.membership.PendingLeavesRead
import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.SharedRecordsReadiness
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.membership.CheckedAt
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.EventConfig
import app.snapsync.model.MemberCounts
import app.snapsync.model.UploadedManifest
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.asConfig
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The app's one-time copy of the shared records' legacy homes into the shared database (capability `photo-sharing`; a
 * shim, `PENDING_CLEANUPS.md`). An updated device holds its records in files and Preferences keys; the app's first open
 * moves each into its rows, and the extension pauses until it has. Each source is held to the same three answers: its
 * contents are copied and the old home deleted, an absent home is nothing to copy, and an unreadable one is retried —
 * its reader meanwhile answers "could not look", never "absent".
 */
class LegacyRecordsTest {

    private val databases = DatabasesMock()
    private val shared = mutableMapOf<String, ByteArray>()
    private val denied = mutableSetOf<Pair<FileArea, String>>()
    private val values = mutableMapOf<String, String>()

    /** The shared database as the APP opens it — a new process each call, so each runs the copy afresh. */
    private fun app(): SharedDatabase =
        SharedDatabase(
            databases.port(),
            LegacyRecords(inMemoryFiles(shared = shared, denied = denied), inMemoryPreferences(values)),
        )

    /** The shared database as the EXTENSION opens it: it never copies. */
    private fun extension(): SharedDatabase = SharedDatabase(databases.port(), legacy = null)

    private fun membershipOf(db: SharedDatabase) = MembershipRepository(db)

    /** The membership [db] holds, in the old zones' shape — `null` unless it reads as joined. */
    private fun joinedIn(db: SharedDatabase): EventConfig? =
        (membershipOf(db).read() as? MembershipRead.Joined)?.membership?.asConfig()

    // ---- the whole copy -----------------------------------------------------------------------------------------

    @Test
    fun `a device with no legacy home is ready at the app's first open`() {
        assertEquals(SharedRecordsReadiness.Ready, app().readiness())
        assertIs<MembershipRead.NotJoined>(membershipOf(app()).read(), "nothing copied is definitively not joined")
    }

    @Test
    fun `the extension pauses until the app has copied - and reads nothing meanwhile`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()

        assertEquals(SharedRecordsReadiness.LegacyPending, extension().readiness())
        assertIs<MembershipRead.Unavailable>(membershipOf(extension()).read(), "never read as not joined")

        app().readiness()
        assertEquals(SharedRecordsReadiness.Ready, extension().readiness())
        assertEquals(JOINED, joinedIn(extension()))
    }

    @Test
    fun `a locked database is unavailable - not pending`() {
        databases.lock()
        assertIs<SharedRecordsReadiness.Unavailable>(app().readiness())
    }

    // ---- the membership -----------------------------------------------------------------------------------------

    @Test
    fun `the membership file becomes the membership row and is deleted`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()

        assertEquals(JOINED, joinedIn(app()))
        assertFalse(CONFIG_FILE_NAME in shared, "the legacy file is gone")
    }

    @Test
    fun `a membership's member counts are copied, the waiting one only when it was recorded`() {
        for (counts in listOf(MemberCounts(3, 1, waiting = 2), MemberCounts(3, 1))) {
            val legacy = JOINED.copy(members = counts)
            shared[CONFIG_FILE_NAME] = encodeConfigFile(legacy).encodeToByteArray()
            val db =
                SharedDatabase(
                    DatabasesMock().port(),
                    LegacyRecords(inMemoryFiles(shared = shared), inMemoryPreferences()),
                )
            assertEquals(counts, joinedIn(db)?.members, "$counts")
        }
    }

    @Test
    fun `an unreadable membership file stays pending and reads as unreadable until it can be copied`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        denied += FileArea.SHARED to CONFIG_FILE_NAME

        val read = membershipOf(app()).read()
        assertIs<MembershipRead.Unavailable>(read, "a membership that could not be copied is never a leave")
        assertEquals(SharedRecordsReadiness.LegacyPending, app().readiness())

        denied.clear()
        assertEquals(JOINED, joinedIn(app()), "the next open copies it")
    }

    @Test
    fun `a membership file that does not decode is left in place and never read as a leave`() {
        shared[CONFIG_FILE_NAME] = """{"v":2,"payload":{"eventId":"from-a-later-build"}}""".encodeToByteArray()

        assertIs<MembershipRead.Unavailable>(membershipOf(app()).read())
        assertTrue(CONFIG_FILE_NAME in shared)
    }

    @Test
    fun `a copied source is never copied again - its leftover is deleted at the next open`() = runTest {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        membershipOf(app()).forget() // copied, then forgotten
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray() // a delete the crash never made
        values[ALBUM_MAP] = "{}" // and a key the same crash left

        assertIs<MembershipRead.NotJoined>(membershipOf(app()).read(), "the mark outranks the leftover")
        assertFalse(CONFIG_FILE_NAME in shared, "the leftover file is gone")
        assertTrue(values.isEmpty(), "the leftover key is gone")
    }

    @Test
    fun `a membership file that is not text stays pending`() {
        shared[CONFIG_FILE_NAME] = byteArrayOf(0xC3.toByte(), 0x28)

        assertIs<MembershipRead.Unavailable>(membershipOf(app()).read())
        assertTrue(CONFIG_FILE_NAME in shared)
    }

    @Test
    fun `a copied file that cannot be deleted is still copied - its mark outranks it`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        val keeping = object : Files by inMemoryFiles(shared = shared) {
            override fun delete(area: FileArea, path: String): FileResult<Unit> = FileResult.Failed("busy")
        }
        val db = SharedDatabase(databases.port(), LegacyRecords(keeping, inMemoryPreferences(values)))

        assertEquals(JOINED, joinedIn(db))
        assertTrue(CONFIG_FILE_NAME in shared, "left behind, for a later open to delete")
    }

    // ---- the manifest record ------------------------------------------------------------------------------------

    @Test
    fun `the manifest record keeps its event - version - json`() {
        val json = """{"deviceId":"D","assets":[],"version":7}"""
        shared[MANIFEST_FILE] = "${testEventId("E").value} $json".encodeToByteArray()

        assertEquals(UploadedManifest(testEventId("E"), 7, json), DeviceManifestService(app()).loadLastUploaded())
        assertFalse(MANIFEST_FILE in shared)
    }

    @Test
    fun `legacy records that name no event are dropped and the rest are copied`() = runTest {
        shared[MANIFEST_FILE] = "E1 {\"deviceId\":\"D\",\"assets\":[],\"version\":7}".encodeToByteArray()
        shared[LEAVES_FILE] = "E1\n${testEventId("F").value} received\n".encodeToByteArray()
        values[ALBUM_MAP] = """{"E1":"album-1","${testEventId("E2").value}":"album-2"}"""

        assertNull(DeviceManifestService(app()).loadLastUploaded(), "the manifest record named no event")
        assertEquals(
            PendingLeavesRead.Ok(setOf(testEventId("F"))),
            PendingLeaves(app()).outstanding(),
            "only the line naming an event is a leave",
        )
        assertEquals(
            "album-2",
            AlbumMapService(app()).get(testEventId("E2")),
            "only the key naming an event is an album",
        )
        assertEquals(SharedRecordsReadiness.Ready, app().readiness())
    }

    @Test
    fun `a manifest record that does not parse is dropped - one republish`() {
        shared[MANIFEST_FILE] = "${testEventId("E").value} not-json".encodeToByteArray()

        assertNull(DeviceManifestService(app()).loadLastUploaded())
        assertEquals(SharedRecordsReadiness.Ready, app().readiness())
    }

    @Test
    fun `a manifest record with no version is dropped`() {
        shared[MANIFEST_FILE] = "${testEventId("E").value} {\"deviceId\":\"D\"}".encodeToByteArray()

        assertNull(DeviceManifestService(app()).loadLastUploaded())
    }

    @Test
    fun `an unreadable manifest record stays pending`() {
        shared[MANIFEST_FILE] = "${testEventId("E").value} {}".encodeToByteArray()
        denied += FileArea.SHARED to MANIFEST_FILE

        assertEquals(SharedRecordsReadiness.LegacyPending, app().readiness())
    }

    // ---- the pending leaves -------------------------------------------------------------------------------------

    @Test
    fun `every pending leave is copied, a received marker read and dropped`() {
        shared[LEAVES_FILE] = "${testEventId("E").value}\n${testEventId("F").value} received\n".encodeToByteArray()

        assertEquals(
            PendingLeavesRead.Ok(setOf(testEventId("E"), testEventId("F"))),
            PendingLeaves(app()).outstanding(),
            "every leave owed",
        )
        assertFalse(LEAVES_FILE in shared)
    }

    // ---- the event albums ---------------------------------------------------------------------------------------

    @Test
    fun `the album map and its filled marks become one row per event and the keys go`() {
        values[ALBUM_MAP] = """{"${testEventId("E1").value}":"album-1","${testEventId("E2").value}":"album-2"}"""
        values[ALBUM_FILLED] = """["${testEventId("E2").value}"]"""

        val albums = AlbumMapService(app())

        assertEquals("album-1", albums.get(testEventId("E1")))
        assertFalse(albums.filled(testEventId("E1")))
        assertEquals("album-2", albums.get(testEventId("E2")))
        assertTrue(albums.filled(testEventId("E2")))
        assertTrue(values.isEmpty(), "both keys are gone")
    }

    @Test
    fun `album keys that do not decode copy no album`() {
        values[ALBUM_MAP] = "not json"
        values[ALBUM_FILLED] = "not json"

        assertNull(AlbumMapService(app()).get(testEventId("E1")))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `unreadable keys stay pending - the albums and the joined event's check times alike`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        values[ALBUM_MAP] = """{"${testEventId("E1").value}":"album-1"}"""
        val locked = object : app.snapsync.ports.Preferences by inMemoryPreferences(values) {
            override fun get(key: String) = app.snapsync.model.PrefRead.Unavailable("locked")
        }
        val db = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(shared = shared), locked))

        assertEquals(SharedRecordsReadiness.LegacyPending, db.readiness())
        assertEquals(mapOf(ALBUM_MAP to """{"${testEventId("E1").value}":"album-1"}"""), values, "left in place")
        val imported = db.use { it.legacyImportQueries.selectImported().executeAsList() }
        assertFalse(LegacySource.CHECKS.key in imported, "the check times wait too")
    }

    // ---- the check times ----------------------------------------------------------------------------------------

    @Test
    fun `the joined event's check times are copied and its keys go`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        val asked = NOW - 10.minutes
        values["app.snapsync.check.photos.${JOINED.eventId}"] = asked.toEpochMilliseconds().toString()
        values["app.snapsync.check.close.${JOINED.eventId}"] = "yesterday" // garbled: not copied, so due

        val checks = EventCheckRecord(app())

        assertEquals(
            CheckedAt.At(Timestamp.of(asked)),
            checks.last(EventCheck.PHOTOS, JOINED.eventId),
            "asked ten minutes ago",
        )
        assertEquals(CheckedAt.Never, checks.last(EventCheck.STATE, JOINED.eventId), "garbled: never asked")
        assertTrue(values.isEmpty())
    }

    private companion object {
        const val MANIFEST_FILE = "device-manifest/last-uploaded.json"
        const val LEAVES_FILE = "membership/pending-leaves.txt"
        const val ALBUM_MAP = "app.snapsync.album.map"
        const val ALBUM_FILLED = "app.snapsync.album.filled"

        val NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

        val JOINED = EventConfig(
            eventId = testEventId("E-joined"),
            name = "Anna's Birthday",
            minPhotoDate = CaptureCutoff(TimestampText.parse("2026-06-10T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-06-20T00:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2026-06-20T00:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2026-07-20T00:00:00Z")),
        )
    }
}
