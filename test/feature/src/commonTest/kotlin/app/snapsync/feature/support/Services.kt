@file:OptIn(app.snapsync.inbound.AppProcessOnly::class)

package app.snapsync.feature.support

import app.cash.sqldelight.db.QueryResult
import app.snapsync.data.identity.MintingDeviceIdentity
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.LedgerEntry
import app.snapsync.ports.Clock
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Files
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.config.ConfigService
import app.snapsync.services.config.saveMembership
import app.snapsync.services.config.toConfig
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.ledger.db.LedgerDatabase
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

// The feature tests' services: the REAL services, over the ports' in-memory mocks (`docs/testing.md`, "Feature tests
// compose real services over port mocks"). A feature under test sees exactly what production hands it; what a test
// observes or forces is at the PORT, never a double of the service.

/** The instant the feature tests' clock is pinned at, unless a test states another. */
val TEST_NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

/** A clock pinned at [now], in UTC. */
fun testClock(now: Instant = TEST_NOW): Clock = fixedClock(now)

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
 *
 * The membership's saves and clears are counted from the database's own change notices, so a test observes what reached
 * the store rather than what a service says it did.
 */
class SharedStore(val databases: DatabasesMock = DatabasesMock()) {
    val db: SharedDatabase = SharedDatabase(databases.port(), LegacyRecords(inMemoryFiles(), inMemoryPreferences()))

    private var saves = 0
    private var clears = 0
    private var refusesSaves = false

    init {
        db.use { }
        // SQLDelight's own change notice: once per committed save or clear that reached the store — a clear of no row
        // included — which a row-level trigger would not see.
        driver().addListener("membership") {
            if (persistedConfig() == null) {
                clears++
                onCleared()
            } else {
                saves++
            }
        }
    }

    /** Called after each committed change that left no membership — how a test orders a clear among its steps. */
    var onCleared: () -> Unit = {}

    /**
     * The membership, persisted — what the config service seeds from at its first use. Not counted as a save, and
     * written past the levers below: a test seeds the store it then locks or refuses.
     */
    fun persist(config: EventConfig?) {
        val locked = databases.operator.locked
        databases.operator.lock(false)
        if (refusesSaves) databases.operator.execute(LEDGER_DB_NAME, "DROP TRIGGER membershipRefused")
        db.use { database ->
            if (config == null) {
                database.membershipQueries.clearMembership()
            } else {
                database.membershipQueries.saveMembership(config)
            }
        }
        if (refusesSaves) refuseSaves()
        databases.operator.lock(locked)
        saves = 0
        clears = 0
    }

    /** What the membership row holds now, or `null` when there is none. */
    fun persistedConfig(): EventConfig? =
        db.use { it.membershipQueries.selectMembership().executeAsOneOrNull()?.toConfig() }

    /** The membership's writes that reached the database since it was seeded. */
    val configSaves: Int get() = saves

    /** Whether a clear of the membership reached the database (a leave, a reset). */
    val configCleared: Boolean get() = clears > 0

    /** Lever: every membership save fails at the store, as a full disk's write does. */
    fun failSaves() {
        refusesSaves = true
        refuseSaves()
    }

    /** Lever: the membership cannot be deleted — a clear fails at the store, so the device stays joined. */
    fun failClears() = databases.operator.execute(
        LEDGER_DB_NAME,
        "CREATE TRIGGER membershipKept BEFORE DELETE ON membership BEGIN SELECT RAISE(ABORT, 'undeletable'); END",
    )

    /** Lever: every database is unreadable — a device locked since boot. */
    fun membershipUnreadable() = databases.operator.lock()

    private fun refuseSaves() = databases.operator.execute(
        LEDGER_DB_NAME,
        "CREATE TRIGGER membershipRefused BEFORE INSERT ON membership BEGIN SELECT RAISE(ABORT, 'disk full'); END",
    )

    private fun driver() =
        (databases.port().open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = true) as DbOpen.Opened).driver
}

/** The shared database over [databases], its legacy homes empty — every record lives in the database from the start. */
fun testShared(databases: Databases = inMemoryDatabases()): SharedDatabase =
    SharedDatabase(databases, LegacyRecords(inMemoryFiles(), inMemoryPreferences()))

/** The real membership service over [store], seeded with [initial]. */
fun configService(
    initial: EventConfig? = null,
    store: SharedStore = SharedStore(),
    clock: Clock = testClock(),
): ConfigService {
    store.persist(initial)
    return ConfigService(store.db, clock)
}

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

/** What the photo-library grant means, over the permission port's in-memory mock and the test's own [grant] cell. */
fun galleryAccess(
    grant: MutableStateFlow<LibraryGrant> = MutableStateFlow(LibraryGrant.GRANTED),
): GalleryAccessState =
    GalleryAccessState(grant)

/** The event a [TestLedger] is joined to unless a test says otherwise. */
const val LEDGER_EVENT: String = "E-ledger"

/**
 * The real ledger over in-memory SQLite, joined to [joined] (every ledger read and write is scoped to the joined
 * event), and the reads a test needs that the app never makes: every row, and which events hold rows.
 */
class TestLedger(val databases: Databases = inMemoryDatabases(), var joined: String? = LEDGER_EVENT) {
    val service: LedgerService = LedgerService(testShared(databases)) { joined?.let(::testEventId) }

    /** Every row the joined event holds, by key. */
    suspend fun rows(): Map<String, LedgerEntry> = keys().mapNotNull { key ->
        service.get(
            key,
        )?.let { key to it }
    }.toMap()

    /** Every event that holds at least one row, joined or not. */
    fun eventsHeld(): Set<String> = column("SELECT DISTINCT eventId FROM ledgerRow").toSet()

    private fun keys(): List<String> = column("SELECT DISTINCT key FROM ledgerRow ORDER BY key")

    private fun column(sql: String): List<String> {
        val opened = databases.open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = true)
        val driver = (opened as? DbOpen.Opened)?.driver ?: return emptyList()
        return driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out += cursor.getString(0)!!
            QueryResult.Value(out)
        }, 0).value
    }
}
