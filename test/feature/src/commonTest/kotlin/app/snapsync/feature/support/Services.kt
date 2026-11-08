@file:OptIn(app.snapsync.inbound.AppProcessOnly::class)

package app.snapsync.feature.support

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.data.legacy.LegacyRecords
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.data.store.db.MembershipQueries
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowsRead
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.LegacyMembership
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.UploadKeyText
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.ports.Clock
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileLocation
import app.snapsync.ports.FileResult
import app.snapsync.ports.FileTail
import app.snapsync.ports.Files
import app.snapsync.ports.OpenDatabase
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

// The feature tests' services: the REAL services, over the ports' in-memory mocks (`docs/testing.md`, "Feature tests
// compose real services over port mocks"). A feature under test sees exactly what production hands it; what a test
// observes or forces is at the PORT, never a double of the service.

/** The instant the feature tests' clock is pinned at, unless a test states another. */
val TEST_NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

/** A clock pinned at [now], in UTC. */
fun testClock(now: Instant = TEST_NOW): Clock = fixedClock(now)

/** A clock reading [now] at each call — a test that moves time. */
fun clockOf(now: () -> Instant): Clock = object : Clock {
    override fun now(): Instant = now()
    override fun timeZone(): TimeZone = TimeZone.UTC
}

/**
 * [Files] over in-memory areas that records what was written and deleted, in order, and fails on demand — the
 * observation and the levers a test needs at the port. [shared] and [private] are the areas' cells; [denied] makes a
 * path unreadable and unwritable, as a locked device's protected file is.
 */
class RecordingFiles(
    val shared: MutableMap<String, ByteArray> = mutableMapOf(),
    val private: MutableMap<String, ByteArray> = mutableMapOf(),
    val denied: MutableSet<Pair<FileArea, String>> = mutableSetOf(),
) : Files {
    private val inner: Files = inMemoryFiles(shared, private, denied)

    /** Every write and delete that reached this port, in order, as `"write <path>"` / `"delete <path>"`. */
    val operations: MutableList<String> = mutableListOf()

    /** Lever: every write answers `Failed` (nothing is written) while set. */
    var failWrites: Boolean = false

    /** Lever: every delete answers `Failed` (nothing is deleted) while set. */
    var failDeletes: Boolean = false

    /** Lever: these paths cannot be located in any area — answered `AreaUnavailable`, as a missing container is. */
    val unlocatable: MutableSet<String> = mutableSetOf()

    /** Called with each operation as it happens — how a test interleaves the port's calls with its own record. */
    var onOperation: (String) -> Unit = {}

    fun writes(path: String): Int = operations.count { it == "write $path" }
    fun deleted(path: String): Boolean = "delete $path" in operations

    override fun read(area: FileArea, path: String): FileResult<ByteArray> = inner.read(area, path)
    override fun readTail(
        area: FileArea,
        path: String,
        maxBytes: Int,
    ): FileResult<FileTail> = inner.readTail(area, path, maxBytes)
    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> =
        inner.readRange(area, path, offset, maxBytes)
    override fun append(
        area: FileArea,
        path: String,
        bytes: ByteArray,
    ): FileAccess<Unit> = inner.append(area, path, bytes)
    override fun exists(area: FileArea, path: String): FileAccess<Boolean> = inner.exists(area, path)
    override fun locate(area: FileArea, path: String): FileLocation<String> =
        if (path in unlocatable) FileResult.AreaUnavailable else inner.locate(area, path)
    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = inner.move(area, from, to)
    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = inner.adopt(osPath, area, to)

    override fun list(area: FileArea, directory: String): FileAccess<List<String>> = inner.list(area, directory)

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> {
        record("write $path")
        return if (failWrites) FileResult.Failed("write failed on demand") else inner.write(area, path, bytes)
    }

    override fun delete(area: FileArea, path: String): FileResult<Unit> {
        record("delete $path")
        return if (failDeletes) FileResult.Failed("delete failed on demand") else inner.delete(area, path)
    }

    private fun record(operation: String) {
        operations += operation
        onOperation(operation)
    }
}

/**
 * The shared database (`ledger.db`) over in-memory SQLite — where the membership, the ledger and the other shared records
 * are kept — with the reads and levers a test needs at the `Databases` port. Its legacy homes are empty, so every record
 * lives here from the first open, as on a fresh install.
 */
class SharedStore(val databases: DatabasesMock = DatabasesMock()) {
    val db: SharedDatabase = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences()))

    /** The same database through the operator's own hand, which no armed fault reaches: how a test seeds and reads. */
    private val inspected = SharedDatabase(
        databases.inspectorPort(),
        LegacyRecords(inMemoryFiles(), inMemoryPreferences()),
    )

    init {
        db.use { }
    }

    /**
     * The membership, persisted — what the repository reads at its first use. Written past the levers below: a test
     * seeds the store it then locks or refuses.
     */
    fun persist(config: LegacyMembership?) {
        val locked = databases.locked
        databases.lock(false)
        inspected.use { database ->
            if (config == null) {
                database.membershipQueries.selectMembership().executeAsOneOrNull()
                    ?.let { database.membershipQueries.deleteMembership(it.eventId) }
            } else {
                database.membershipQueries.saveRow(config)
            }
        }
        databases.lock(locked)
    }

    /** Lever: the next membership save fails at the store, as a write SQLite cannot make does. */
    fun failSaves() = databases.fail(LEDGER_DB_NAME, "membership", WriteKind.INSERT)

    /** Lever: the next clear of the membership fails at the store, so the device stays joined. */
    fun failClears() = databases.fail(LEDGER_DB_NAME, "membership", WriteKind.DELETE)

    /** Lever: every database is unreadable — a device locked since boot. */
    fun membershipUnreadable() = databases.lock()
}

/** The shared database over [databases], its legacy homes empty — every record lives in the database from the start. */
fun testShared(databases: Databases = inMemoryDatabases()): SharedDatabase =
    SharedDatabase(databases, LegacyRecords(inMemoryFiles(), inMemoryPreferences()))

/** The real membership repository over [store], seeded with [initial]. */
fun membershipIn(initial: LegacyMembership? = null, store: SharedStore = SharedStore()): MembershipRepository {
    store.persist(initial)
    return MembershipRepository(store.db)
}

/** [config] as the one membership row, written raw — how a test seeds what a join would have written. */
internal fun MembershipQueries.saveRow(config: LegacyMembership) = saveMembership(
    eventId = Stored.Valid(config.eventId),
    name = config.name,
    minPhotoDate = config.minPhotoDate,
    startsAt = config.startsAt,
    endsAt = config.endsAt,
    maxPhotoDate = config.maxPhotoDate,
    deletesAt = config.deletesAt,
    direction = config.direction,
    saveToAlbum = if (config.saveToAlbum) AlbumChoice.SaveToAlbum else AlbumChoice.LibraryOnly,
    membersActive = config.members?.active?.toLong(),
    membersSettled = config.members?.settled?.toLong(),
    membersWaiting = config.members?.waiting?.toLong(),
    keyId = config.keyId,
    openUntil = config.openUntil,
    deleted = config.deleted,
)

/**
 * A device identity that resolves to the fixture device [id] stands for ([testDeviceId]) — the app's, minting the
 * platform id over an empty secure store.
 */
fun testIdentity(id: String): MintingDeviceIdentity =
    MintingDeviceIdentity(
        inMemorySecureStore(),
        PlatformDeviceId { testDeviceId(id).value },
    )

/** A device identity that cannot be read — the secure store answers as a locked device's does. */
fun unreadableIdentity(): MintingDeviceIdentity =
    MintingDeviceIdentity(
        inMemorySecureStore(unavailable = true),
        PlatformDeviceId { "unread" },
    )

/** The event a [TestLedger]'s rows belong to unless a test says otherwise. */
const val LEDGER_EVENT: String = "E-ledger"

/**
 * The real upload rows over in-memory SQLite — read through [rows], reset through [reset] — and what a test needs that
 * the app never does: a row of any state written raw, every row an event holds, and which events hold rows.
 */
class TestLedger(val databases: Databases = inMemoryDatabases()) {
    val shared: SharedDatabase = testShared(databases)
    val rows: OwnUploadRows = OwnUploadRows(shared)
    val reset: UploadRowsReset = UploadRowsReset(shared)

    /** [key] written raw as [event]'s row in [state], bare unless [captured] dates it. */
    fun put(event: String, key: String, state: LedgerState, captured: String = "") {
        val parsed = checkNotNull(UploadKeyText.parse(key)) { key }
        shared.execute {
            it.ledgerQueries.insert(
                testEventId(event).value, key, Stored.Valid(parsed.asset), state, captured,
                ResourceRoleText.of(parsed.role), "image/heic", "IMG.HEIC", null,
            )
        }
    }

    /** [event]'s row [key] deleted raw, as a walk withdraws a photo that left the library. */
    fun delete(event: String, key: String) {
        shared.execute { it.ledgerQueries.deleteKeys(testEventId(event).value, listOf(key)) }
    }

    /** Every row [event] holds, by key. */
    fun rowsOf(event: String = LEDGER_EVENT): Map<String, UploadRow> =
        when (val read = rows.rows(testEventId(event))) {
            is RowsRead.Read -> read.rows.associateBy { UploadKeyText.of(it.key) }
            is RowsRead.Unavailable -> emptyMap()
        }

    /** Every event that holds at least one row. */
    fun eventsHeld(): Set<String> = column("SELECT DISTINCT eventId FROM ledgerRow").toSet()

    private fun column(sql: String): List<String> {
        val opened = databases.open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = true)
        val driver = (opened as? DbOpen.Opened)?.database?.driver() ?: return emptyList()
        return driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out += cursor.getString(0)!!
            QueryResult.Value(out)
        }, 0).value
    }
}

/** The open database's driver, held outside an execute — a test's own reach, for what no query exposes. */
fun OpenDatabase.driver(): SqlDriver = (execute { it } as DbResult.Ok).value

/** [statements]' answer on this shared database, set up to succeed. */
private fun <T> SharedDatabase.use(statements: (LedgerDatabase) -> T): T =
    (execute(statements) as DbResult.Ok<T>).value
