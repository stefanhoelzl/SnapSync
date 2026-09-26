package app.snapsync.feature.support

import app.snapsync.fake.fixedClock
import app.snapsync.fake.inMemoryDatabases
import app.snapsync.fake.inMemoryFiles
import app.snapsync.fake.inMemoryPhotoAccess
import app.snapsync.fake.inMemorySecureStore
import app.snapsync.model.ConfigFileDecode
import app.snapsync.model.DeviceIdentityRole
import app.snapsync.model.EventConfig
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.GalleryAccess
import app.snapsync.model.LedgerEntry
import app.snapsync.model.decodeConfigFile
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.Clock
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Files
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.config.ConfigService
import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.services.identity.PersistedDeviceIdentity
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.ledger.db.LedgerDatabase
import app.cash.sqldelight.db.QueryResult
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

    /** Called with each operation as it happens — how a test interleaves the port's calls with its own record. */
    var onOperation: (String) -> Unit = {}

    fun writes(path: String): Int = operations.count { it == "write $path" }
    fun deleted(path: String): Boolean = "delete $path" in operations

    override fun read(area: FileArea, path: String): FileResult<ByteArray> = inner.read(area, path)
    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = inner.readTail(area, path, maxBytes)
    override fun exists(area: FileArea, path: String): FileResult<Boolean> = inner.exists(area, path)
    override fun locate(area: FileArea, path: String): FileResult<String> = inner.locate(area, path)
    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = inner.move(area, from, to)
    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = inner.adopt(osPath, area, to)

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> {
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

/** The membership file, persisted — what the config service seeds from at construction. */
fun RecordingFiles.persist(config: EventConfig?) {
    if (config == null) shared.remove(CONFIG_FILE_NAME) else shared[CONFIG_FILE_NAME] = encodeConfigFile(config).encodeToByteArray()
}

/** What the membership file holds now, or `null` when there is none. */
fun RecordingFiles.persistedConfig(): EventConfig? =
    (shared[CONFIG_FILE_NAME]?.decodeToString()?.let(::decodeConfigFile) as? ConfigFileDecode.Valid)?.config

/** The membership's writes that reached the file. */
val RecordingFiles.configSaves: Int get() = writes(CONFIG_FILE_NAME)

/** Whether the membership file was deleted (a leave, a reset). */
val RecordingFiles.configCleared: Boolean get() = deleted(CONFIG_FILE_NAME)

/** Lever: the membership file cannot be read or written — a device locked since boot. */
fun RecordingFiles.membershipUnreadable() {
    denied += FileArea.SHARED to CONFIG_FILE_NAME
}

/** The real membership service over [files], seeded with [initial]. */
fun configService(
    initial: EventConfig? = null,
    files: RecordingFiles = RecordingFiles(),
    clock: Clock = testClock(),
): ConfigService {
    files.persist(initial)
    return ConfigService(files, clock)
}

/** A device identity that resolves to [id] — the app's, minting the platform id over an empty secure store. */
fun testIdentity(id: String): PersistedDeviceIdentity =
    PersistedDeviceIdentity(DeviceIdentityRole.MINTING, inMemorySecureStore(), PlatformDeviceId { id })

/** A device identity that cannot be read — the secure store answers as a locked device's does. */
fun unreadableIdentity(): PersistedDeviceIdentity =
    PersistedDeviceIdentity(DeviceIdentityRole.MINTING, inMemorySecureStore(unavailable = true), PlatformDeviceId { "unread" })

/** What the photo-library grant means, over the permission port's in-memory mock and the test's own [grant] cell. */
fun galleryAccess(grant: MutableStateFlow<GalleryAccess> = MutableStateFlow(GalleryAccess.GRANTED)): GalleryAccessState =
    GalleryAccessState(inMemoryPhotoAccess(grant))

/** The real ledger over in-memory SQLite, and the read a test needs that the app never makes: every row. */
class TestLedger(val databases: Databases = inMemoryDatabases()) {
    val service: LedgerService = LedgerService(databases)

    /** Every row the ledger holds, by key. */
    suspend fun rows(): Map<String, LedgerEntry> = keys().mapNotNull { key -> service.get(key)?.let { key to it } }.toMap()

    private fun keys(): List<String> {
        val opened = databases.open(LEDGER_DB_NAME, LedgerDatabase.Schema, readOnly = true)
        val driver = (opened as? DbOpen.Opened)?.driver ?: return emptyList()
        return driver.executeQuery(null, "SELECT key FROM ledgerRow ORDER BY key", { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out += cursor.getString(0)!!
            QueryResult.Value(out)
        }, 0).value
    }
}
