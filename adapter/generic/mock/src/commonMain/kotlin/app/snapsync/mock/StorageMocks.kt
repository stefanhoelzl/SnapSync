package app.snapsync.mock

import app.snapsync.model.FileArea
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore

// The storage mocks (`docs/testing.md`, "Mocks"): each holds what the device keeps on disk — which a relaunch of the
// app finds again — and hands a process a port-typed face over it. The operator reads and writes the disk the way an
// inspector of the device's files would.

/**
 * The device's files: the shared (App-Group) and the app's private area. Read while written — the app writes from its
 * own threads, an inspector (a rig `/device` request) reads from another — so each area is a [SnapshotMap], and so is
 * the denied set (its keys; a process is handed the live key view).
 */
class FileSystemMock {
    internal val shared = SnapshotMap<String, ByteArray>()
    internal val private = SnapshotMap<String, ByteArray>()
    internal val denied = SnapshotMap<Pair<FileArea, String>, Unit>()

    /** A process's face. The upload extension reaches only the shared area: pass `privateArea = false`. */
    fun port(privateArea: Boolean = true): Files =
        InMemoryFiles(mapOf(FileArea.SHARED to shared, FileArea.PRIVATE to private.takeIf { privateArea }), denied.keys)

    val operator: FileSystemOperator = FileSystemOperator(this)
}

/** The device's files as an inspector with the device open sees and edits them. */
class FileSystemOperator internal constructor(private val disk: FileSystemMock) {

    /** An area's files, path → bytes — live: a write here is a write to the device's disk. */
    fun area(area: FileArea): MutableMap<String, ByteArray> = held(area)

    private fun held(area: FileArea): SnapshotMap<String, ByteArray> = when (area) {
        FileArea.SHARED -> disk.shared
        FileArea.PRIVATE -> disk.private
    }

    fun read(area: FileArea, path: String): ByteArray? = area(area)[path]

    /** The paths [area] holds now: one whole snapshot of it, however the app writes meanwhile. */
    fun paths(area: FileArea): List<String> = held(area).snapshot().keys.toList()

    fun write(area: FileArea, path: String, bytes: ByteArray) {
        area(area)[path] = bytes
    }

    /** Append [text] to a file — how a process's log grows. */
    fun append(area: FileArea, path: String, text: String) {
        held(
            area,
        ).edit { files -> files + (path to (files[path]?.decodeToString().orEmpty() + text).encodeToByteArray()) }
    }

    fun remove(area: FileArea, path: String) {
        area(area).remove(path)
    }

    /** Make a present file unreadable to the app ([denied]), or readable again — a file the OS protects. */
    fun deny(area: FileArea, path: String, denied: Boolean = true) {
        if (denied) disk.denied[area to path] = Unit else disk.denied.remove(area to path)
    }

    fun isDenied(area: FileArea, path: String): Boolean = (area to path) in disk.denied
}

/**
 * The device's SQLite databases: real in-memory SQLite, one per name, held for the mock's lifetime — so a relaunched
 * process opens what the last one wrote. [refusals] answers an open of that name with the refusal given. With a
 * [directory], each is a file there instead, which outlives the process — the launch-time adapters' persisted state.
 */
class DatabasesMock(refusals: Map<String, DbOpen> = emptyMap(), directory: String? = null) {
    private val held = InMemoryDatabases(refusals, directory)

    /** A process's face: the same databases, as every process on the device opens the same files. */
    fun port(): Databases = held

    val operator: DatabasesOperator = DatabasesOperator(held)
}

class DatabasesOperator internal constructor(private val held: InMemoryDatabases) {
    /** Every open the device saw, by name, in order — a process's opens and every later one's. */
    val opened: List<String> get() = held.opened

    /** Delete every database, as deleting the app does (in-memory databases only). */
    fun deleteAll() = held.deleteAll()
}

/** The device's user defaults — read while the app writes them, so a [SnapshotMap]. */
class PreferencesMock {
    internal val values = SnapshotMap<String, String>()

    fun port(): Preferences = InMemoryPreferences(values)
}

/**
 * The device's Keychain: one value per slot, [seed] already present — the device id of an app that launched before.
 * Both processes write it and an inspector reads it, so a [SnapshotMap].
 */
class SecureStoreMock(seed: Map<SecureSlot, String> = emptyMap()) {
    internal val items = SnapshotMap<SecureSlot, SecureStoreRead.Found>().apply {
        putAll(seed.mapValues { SecureStoreRead.Found(it.value, StoredProtection.BACKGROUND_READABLE) })
    }

    /** A process's face; [unavailable] is one that cannot read the Keychain at all (locked since boot). */
    fun port(unavailable: Boolean = false): SecureStore = InMemorySecureStore(items, unavailable)
}
