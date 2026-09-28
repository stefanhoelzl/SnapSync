package app.snapsync.mock

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
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

/** The device's files: the shared (App-Group) and the app's private area. */
class FileSystemMock {
    internal val shared: MutableMap<String, ByteArray> = mutableMapOf()
    internal val private: MutableMap<String, ByteArray> = mutableMapOf()
    internal val denied: MutableSet<Pair<FileArea, String>> = mutableSetOf()

    /** A process's face. The upload extension reaches only the shared area: pass `privateArea = false`. */
    fun port(privateArea: Boolean = true): Files =
        InMemoryFiles(mapOf(FileArea.SHARED to shared, FileArea.PRIVATE to private.takeIf { privateArea }), denied)

    val operator: FileSystemOperator = FileSystemOperator(this)
}

/** The device's files as an inspector with the device open sees and edits them. */
class FileSystemOperator internal constructor(private val disk: FileSystemMock) {

    /** An area's files, path → bytes — live: a write here is a write to the device's disk. */
    fun area(area: FileArea): MutableMap<String, ByteArray> = when (area) {
        FileArea.SHARED -> disk.shared
        FileArea.PRIVATE -> disk.private
    }

    fun read(area: FileArea, path: String): ByteArray? = area(area)[path]

    fun write(area: FileArea, path: String, bytes: ByteArray) {
        area(area)[path] = bytes
    }

    /** Append [text] to a file — how a process's log grows. */
    fun append(area: FileArea, path: String, text: String) {
        val files = area(area)
        files[path] = (files[path]?.decodeToString().orEmpty() + text).encodeToByteArray()
    }

    fun remove(area: FileArea, path: String) {
        area(area).remove(path)
    }

    /** Make a present file unreadable to the app ([denied]), or readable again — a file the OS protects. */
    fun deny(area: FileArea, path: String, denied: Boolean = true) {
        if (denied) disk.denied += area to path else disk.denied -= area to path
    }

    fun isDenied(area: FileArea, path: String): Boolean = (area to path) in disk.denied

    /**
     * A temporary file the operating system leaves for a finished transfer, in the private area at [path]: answers the
     * platform path the transfer's finish hands the app, which the app adopts from.
     */
    fun leaveTemporaryFile(path: String, bytes: ByteArray): String {
        disk.private[path] = bytes
        return (disk.port().locate(FileArea.PRIVATE, path) as FileResult.Ok).value
    }
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
    val opened: List<String> get() = held.opened.toList()
}

/** The device's user defaults. */
class PreferencesMock {
    internal val values: MutableMap<String, String> = mutableMapOf()

    fun port(): Preferences = InMemoryPreferences(values)
}

/** The device's Keychain: one value per slot, [seed] already present — the device id of an app that launched before. */
class SecureStoreMock(seed: Map<SecureSlot, String> = emptyMap()) {
    internal val items: MutableMap<SecureSlot, SecureStoreRead.Found> =
        seed.mapValuesTo(mutableMapOf()) { SecureStoreRead.Found(it.value, StoredProtection.BACKGROUND_READABLE) }

    /** A process's face; [unavailable] is one that cannot read the Keychain at all (locked since boot). */
    fun port(unavailable: Boolean = false): SecureStore = InMemorySecureStore(items, unavailable)
}
