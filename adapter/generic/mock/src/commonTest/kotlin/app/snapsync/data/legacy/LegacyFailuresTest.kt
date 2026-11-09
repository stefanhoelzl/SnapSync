package app.snapsync.data.legacy

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.SharedRecordsReadiness
import app.snapsync.data.store.Stored
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.OpenDatabase
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How the legacy copy fails (a shim, `PENDING_CLEANUPS.md`): every way a legacy home cannot be read or deleted, and a
 * database that cannot run the copy's statements. A source that fails stays pending, alone; only a database that cannot
 * say which sources it holds fails the open.
 */
class LegacyFailuresTest {

    private val databases = DatabasesMock()
    private val shared = mutableMapOf<String, ByteArray>()
    private val values = mutableMapOf<String, String>()

    /** The statement runs of an open database, numbered from 1 (the open's own); those in [failing] fail. */
    private class Faulty(private val inner: Databases, private val failing: Set<Int>) : Databases by inner {
        var runs = 0

        override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen =
            when (val opened = inner.open(name, schema, readOnly)) {
                is DbOpen.Opened -> DbOpen.Opened(FaultyDb(opened.database))
                else -> opened
            }

        private inner class FaultyDb(private val inner: OpenDatabase) : OpenDatabase {
            override fun <T> execute(statements: (SqlDriver) -> T): DbResult<T> =
                if (++runs in failing) DbResult.Failed("refused") else inner.execute(statements)
        }
    }

    /** Files whose reads and deletes of [path] answer [answer]. */
    private fun answering(failing: String, answer: FileResult<Nothing>): Files =
        object : Files by inMemoryFiles(shared = shared) {
            override fun read(area: FileArea, path: String) =
                if (path == failing) answer else inMemoryFiles(shared = shared).read(area, path)

            override fun delete(area: FileArea, path: String) =
                if (path == failing) answer else inMemoryFiles(shared = shared).delete(area, path)
        }

    private fun legacy() = LegacyRecords(inMemoryFiles(), inMemoryPreferences())

    private fun sourceOf(path: String) = when (path) {
        LegacyRecords.CONFIG_FILE_NAME -> LegacySource.MEMBERSHIP
        else -> LegacySource.MANIFEST
    }

    /** Files that read [failing] as they hold it, and answer [answer] to deleting it. */
    private fun refusingDelete(failing: String, answer: FileResult<Nothing>): Files =
        object : Files by inMemoryFiles(shared = shared) {
            override fun delete(area: FileArea, path: String) =
                if (path == failing) answer else inMemoryFiles(shared = shared).delete(area, path)
        }

    private fun imported(db: SharedDatabase): List<String> =
        assertIs<DbResult.Ok<List<String>>>(
            db.execute { it.legacyImportQueries.selectImported().executeAsList() },
        ).value

    @Test
    fun `a database that cannot say which sources it holds fails the open - and the extension reads it unavailable`() {
        val failing = Faulty(databases.port(), setOf(2))
        val db = SharedDatabase(failing, LegacyRecords(inMemoryFiles(shared = shared), inMemoryPreferences(values)))

        assertIs<SharedRecordsReadiness.Unavailable>(db.readiness())
    }

    @Test
    fun `an open that fails before the copy fails the open`() {
        val db = SharedDatabase(Faulty(databases.port(), setOf(1)), legacy())

        assertIs<SharedRecordsReadiness.Unavailable>(db.readiness())
    }

    @Test
    fun `a source whose statements fail stays pending, alone`() {
        // Runs: 1 the open, 2 which sources are held, 3 the membership's copy.
        val db = SharedDatabase(Faulty(databases.port(), setOf(3)), legacy())

        assertEquals(SharedRecordsReadiness.LegacyPending, db.readiness())
        val held = imported(db)
        assertFalse(LegacySource.MEMBERSHIP.key in held, "the failed source waits")
        assertTrue(LegacySource.MANIFEST.key in held, "the others are copied")
    }

    @Test
    fun `the extension, which never copies, opens the database as it is`() {
        assertEquals(SharedRecordsReadiness.LegacyPending, SharedDatabase(databases.port(), legacy = null).readiness())
    }

    @Test
    fun `a legacy file the platform cannot read stays pending - every way it cannot`() {
        val unreadable = listOf(FileResult.AreaUnavailable, FileResult.Denied("no"), FileResult.Failed("io"))
        for (path in listOf(LegacyRecords.CONFIG_FILE_NAME, MANIFEST_FILE)) {
            for (answer in unreadable) {
                val legacy = LegacyRecords(answering(path, answer), inMemoryPreferences())
                val db = SharedDatabase(DatabasesMock().port(), legacy)
                val source = sourceOf(path)
                assertFalse(source.key in imported(db), "$path answering $answer")
            }
        }
    }

    @Test
    fun `a copied home the platform will not delete is left for a later open - its mark outranks it`() {
        for (answer in listOf(FileResult.AreaUnavailable, FileResult.Denied("no"), FileResult.Failed("io"))) {
            shared[LEAVES_FILE] = "${testEventId("E1").value}\n".encodeToByteArray()
            val db = SharedDatabase(
                DatabasesMock().port(),
                LegacyRecords(refusingDelete(LEAVES_FILE, answer), inMemoryPreferences()),
            )
            assertTrue(LegacySource.PENDING_LEAVES.key in imported(db), "$answer")
        }
    }

    @Test
    fun `a membership file that reads but is unusable is left in place`() {
        shared[LegacyRecords.CONFIG_FILE_NAME] = """{"v":1}""".encodeToByteArray()
        val db = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(shared = shared), inMemoryPreferences()))

        assertFalse(LegacySource.MEMBERSHIP.key in imported(db))
        assertTrue(LegacyRecords.CONFIG_FILE_NAME in shared)
    }

    @Test
    fun `a manifest record without its assets is dropped`() {
        shared[MANIFEST_FILE] = """${testEventId("E1").value} {"version":3}""".encodeToByteArray()
        val db = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(shared = shared), inMemoryPreferences()))

        assertTrue(LegacySource.MANIFEST.key in imported(db))
        assertFalse(MANIFEST_FILE in shared)
    }

    @Test
    fun `check times wait while the membership row names no event - and nothing is forgotten for it`() {
        val first = SharedDatabase(databases.port(), legacy = null)
        first.execute { db ->
            db.membershipQueries.saveMembership(
                eventId = Stored.Empty,
                name = "n",
                minPhotoDate = CaptureCutoff(AT),
                startsAt = EventStart(AT),
                endsAt = EventEnd(AT),
                maxPhotoDate = CaptureCeiling(AT),
                deletesAt = DeletesAt(AT),
                direction = Direction.Both,
                saveToAlbum = AlbumChoice.LibraryOnly,
                membersActive = null,
                membersSettled = null,
                membersWaiting = null,
                keyId = null,
                openUntil = null,
                deleted = false,
            )
            db.legacyImportQueries.markImported(LegacySource.MEMBERSHIP.key)
        }

        val app = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences(values)))
        assertFalse(LegacySource.CHECKS.key in imported(app), "the times wait")

        app.execute { it.legacyImportQueries.markImported(LegacySource.CHECKS.key) }
        values["app.snapsync.check.photos.garbled"] = "1"
        val again = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences(values)))
        assertEquals(SharedRecordsReadiness.Ready, again.readiness())
        assertEquals(1, values.size, "no event, so no key of one is forgotten")
    }

    @Test
    fun `a device with no membership marks its check times copied and forgets none`() {
        val app = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences(values)))
        assertTrue(LegacySource.CHECKS.key in imported(app))

        val again = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences(values)))
        assertEquals(SharedRecordsReadiness.Ready, again.readiness())
    }

    private companion object {
        const val MANIFEST_FILE = "device-manifest/last-uploaded.json"
        const val LEAVES_FILE = "membership/pending-leaves.txt"
        val AT = TimestampText.parse("2026-06-10T00:00:00Z")
    }
}
