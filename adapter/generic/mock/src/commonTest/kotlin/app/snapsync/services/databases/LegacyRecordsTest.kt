package app.snapsync.services.databases

import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.SharedRecordsReadiness
import app.snapsync.model.UploadedManifest
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.Files
import app.snapsync.services.album.AlbumMapService
import app.snapsync.services.backend.LeaveNotifier
import app.snapsync.services.config.ConfigService
import app.snapsync.services.leave.PendingLeaves
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
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

    private fun membershipOf(db: SharedDatabase) = ConfigService(db, fixedClock(NOW))

    // ---- the whole copy -----------------------------------------------------------------------------------------

    @Test
    fun `a device with no legacy home is ready at the app's first open`() {
        assertEquals(SharedRecordsReadiness.Ready, app().readiness())
        assertEquals(ConfigRead.None, membershipOf(app()).read(), "nothing copied is definitively not joined")
    }

    @Test
    fun `the extension pauses until the app has copied - and reads nothing meanwhile`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()

        assertEquals(SharedRecordsReadiness.LegacyPending, extension().readiness())
        assertIs<ConfigRead.Unavailable>(membershipOf(extension()).read(), "never read as not joined")

        app().readiness()
        assertEquals(SharedRecordsReadiness.Ready, extension().readiness())
        assertEquals(ConfigRead.Joined(JOINED), membershipOf(extension()).read())
    }

    @Test
    fun `a locked database is unavailable - not pending`() {
        databases.operator.lock()
        assertIs<SharedRecordsReadiness.Unavailable>(app().readiness())
    }

    // ---- the membership -----------------------------------------------------------------------------------------

    @Test
    fun `the membership file becomes the membership row and is deleted`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()

        assertEquals(ConfigRead.Joined(JOINED), membershipOf(app()).read())
        assertFalse(CONFIG_FILE_NAME in shared, "the legacy file is gone")
    }

    @Test
    fun `an unreadable membership file stays pending and reads as unreadable until it can be copied`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        denied += FileArea.SHARED to CONFIG_FILE_NAME

        val read = membershipOf(app()).read()
        assertIs<ConfigRead.Unavailable>(read, "a membership that could not be copied is never a leave")
        assertEquals(SharedRecordsReadiness.LegacyPending, app().readiness())

        denied.clear()
        assertEquals(ConfigRead.Joined(JOINED), membershipOf(app()).read(), "the next open copies it")
    }

    @Test
    fun `a membership file that does not decode is left in place and never read as a leave`() {
        shared[CONFIG_FILE_NAME] = """{"v":2,"payload":{"eventId":"from-a-later-build"}}""".encodeToByteArray()

        assertIs<ConfigRead.Unavailable>(membershipOf(app()).read())
        assertTrue(CONFIG_FILE_NAME in shared)
    }

    @Test
    fun `a copied source is never copied again - its leftover is deleted at the next open`() = runTest {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        membershipOf(app()).clear() // copied, then left
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray() // a delete the crash never made
        values[ALBUM_MAP] = "{}" // and a key the same crash left

        assertEquals(ConfigRead.None, membershipOf(app()).read(), "the mark outranks the leftover")
        assertFalse(CONFIG_FILE_NAME in shared, "the leftover file is gone")
        assertTrue(values.isEmpty(), "the leftover key is gone")
    }

    @Test
    fun `a membership file that is not text stays pending`() {
        shared[CONFIG_FILE_NAME] = byteArrayOf(0xC3.toByte(), 0x28)

        assertIs<ConfigRead.Unavailable>(membershipOf(app()).read())
        assertTrue(CONFIG_FILE_NAME in shared)
    }

    @Test
    fun `a copied file that cannot be deleted is still copied - its mark outranks it`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        val keeping = object : Files by inMemoryFiles(shared = shared) {
            override fun delete(area: FileArea, path: String): FileResult<Unit> = FileResult.Failed("busy")
        }
        val db = SharedDatabase(databases.port(), LegacyRecords(keeping, inMemoryPreferences(values)))

        assertEquals(ConfigRead.Joined(JOINED), membershipOf(db).read())
        assertTrue(CONFIG_FILE_NAME in shared, "left behind, for a later open to delete")
    }

    // ---- the manifest record ------------------------------------------------------------------------------------

    @Test
    fun `the manifest record keeps its event - version - json`() {
        val json = """{"deviceId":"D","assets":[],"version":7}"""
        shared[MANIFEST_FILE] = "E $json".encodeToByteArray()

        assertEquals(UploadedManifest("E", 7, json), DeviceManifestService(app()).loadLastUploaded())
        assertFalse(MANIFEST_FILE in shared)
    }

    @Test
    fun `a manifest record that does not parse is dropped - one republish`() {
        shared[MANIFEST_FILE] = "E not-json".encodeToByteArray()

        assertNull(DeviceManifestService(app()).loadLastUploaded())
        assertEquals(SharedRecordsReadiness.Ready, app().readiness())
    }

    @Test
    fun `a manifest record with no version is dropped`() {
        shared[MANIFEST_FILE] = "E {\"deviceId\":\"D\"}".encodeToByteArray()

        assertNull(DeviceManifestService(app()).loadLastUploaded())
    }

    @Test
    fun `an unreadable manifest record stays pending`() {
        shared[MANIFEST_FILE] = "E {}".encodeToByteArray()
        denied += FileArea.SHARED to MANIFEST_FILE

        assertEquals(SharedRecordsReadiness.LegacyPending, app().readiness())
    }

    // ---- the pending leaves -------------------------------------------------------------------------------------

    @Test
    fun `every pending leave is copied with whether it received everything`() = runTest {
        shared[LEAVES_FILE] = "E\nF received\n".encodeToByteArray()
        val answers = mutableListOf<Pair<String, Boolean>>()
        val leaves = PendingLeaves(
            app(),
            LeaveNotifier { id, received -> Result.success(Unit).also { answers += id to received } },
        )

        leaves.deliverAll()

        assertEquals(listOf("E" to false, "F" to true), answers, "a line without the marker did not receive")
        assertFalse(LEAVES_FILE in shared)
    }

    // ---- the event albums ---------------------------------------------------------------------------------------

    @Test
    fun `the album map and its filled marks become one row per event and the keys go`() {
        values[ALBUM_MAP] = """{"E1":"album-1","E2":"album-2"}"""
        values[ALBUM_FILLED] = """["E2"]"""

        val albums = AlbumMapService(app())

        assertEquals("album-1", albums.get("E1"))
        assertFalse(albums.filled("E1"))
        assertEquals("album-2", albums.get("E2"))
        assertTrue(albums.filled("E2"))
        assertTrue(values.isEmpty(), "both keys are gone")
    }

    @Test
    fun `album keys that do not decode copy no album`() {
        values[ALBUM_MAP] = "not json"
        values[ALBUM_FILLED] = "not json"

        assertNull(AlbumMapService(app()).get("E1"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `unreadable keys stay pending - the albums and the joined event's check times alike`() {
        shared[CONFIG_FILE_NAME] = encodeConfigFile(JOINED).encodeToByteArray()
        values[ALBUM_MAP] = """{"E1":"album-1"}"""
        val locked = object : app.snapsync.ports.Preferences by inMemoryPreferences(values) {
            override fun get(key: String) = app.snapsync.model.PrefRead.Unavailable("locked")
        }
        val db = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(shared = shared), locked))

        assertEquals(SharedRecordsReadiness.LegacyPending, db.readiness())
        assertEquals(mapOf(ALBUM_MAP to """{"E1":"album-1"}"""), values, "left in place")
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

        val checks = EventChecks(app(), now = { NOW })

        assertFalse(checks.due(EventCheck.PHOTOS, JOINED.eventId), "asked ten minutes ago: not due")
        assertTrue(checks.due(EventCheck.CLOSE, JOINED.eventId), "never asked: due")
        assertTrue(values.isEmpty())
    }

    private companion object {
        const val MANIFEST_FILE = "device-manifest/last-uploaded.json"
        const val LEAVES_FILE = "membership/pending-leaves.txt"
        const val ALBUM_MAP = "app.snapsync.album.map"
        const val ALBUM_FILLED = "app.snapsync.album.filled"

        val NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

        val JOINED = EventConfig(
            eventId = "E-joined",
            name = "Anna's Birthday",
            minPhotoDate = CaptureCutoff(CaptureDate("2026-06-10T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(CaptureDate("2026-06-20T00:00:00Z")),
            endsAt = EventEnd(CaptureDate("2026-06-20T00:00:00Z")),
            deletesAt = deletesAt("2026-07-20T00:00:00Z"),
        )
    }
}
