package app.snapsync.mock

import app.snapsync.levers.DatabaseFault
import app.snapsync.levers.DatabaseSwitches
import app.snapsync.levers.DatabasesLevers
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.model.FileArea
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection

// The storage mocks (`docs/testing.md`, "Mocks"): each holds what the device keeps on disk — which a relaunch of the
// app finds again — and hands a process a port-typed face over it. The operator reads and writes the disk the way an
// inspector of the device's files would.

/**
 * The device's files: the shared (App-Group) and the app's private area. Read while written — the app writes from its
 * own threads, an inspector (a rig `/device` request) reads from another — so each area is a [SnapshotMap], and so is
 * the denied set (its keys; a process is handed the live key view).
 *
 * The inspector's hand on the disk — read, write, list — is here, on the mock: plumbing for the channel and the other
 * mocks, never a lever a test pulls (the app's private files are not observable, `docs/testing.md` §7).
 */
class FileSystemMock {
    internal val shared = SnapshotMap<String, ByteArray>()
    internal val private = SnapshotMap<String, ByteArray>()
    internal val denied = SnapshotMap<Pair<FileArea, String>, Unit>()

    /**
     * Whether every area is unreachable to the app — a device not unlocked since it restarted. The channel's is
     * `DeviceLevers.lock`; an inspector's own reads below go around it.
     */
    var sealed: Boolean
        get() = seal.on
        set(on) {
            seal.on = on
        }

    private val seal = Seal()

    /** A process's face. The upload extension reaches only the shared area: pass `privateArea = false`. */
    fun port(privateArea: Boolean = true): Files = InMemoryFiles(
        mapOf(FileArea.SHARED to shared, FileArea.PRIVATE to private.takeIf { privateArea }),
        denied.keys,
        seal = seal,
    )

    /** An area's files, path → bytes — live: a write here is a write to the device's disk. */
    fun area(area: FileArea): MutableMap<String, ByteArray> = held(area)

    internal fun held(area: FileArea): SnapshotMap<String, ByteArray> = when (area) {
        FileArea.SHARED -> shared
        FileArea.PRIVATE -> private
    }

    fun read(area: FileArea, path: String): ByteArray? = held(area)[path]

    /** The bytes at [location], a platform path a process's face handed out (`Files.locate`); `null` for anything else. */
    fun located(location: String): ByteArray? = FileArea.entries.firstNotNullOfOrNull { area ->
        val prefix = "${InMemoryFiles.MEM}${area.name.lowercase()}/"
        if (location.startsWith(prefix)) read(area, location.removePrefix(prefix)) else null
    }

    /** The paths [area] holds now: one whole snapshot of it, however the app writes meanwhile. */
    fun paths(area: FileArea): List<String> = held(area).snapshot().keys.toList()

    fun write(area: FileArea, path: String, bytes: ByteArray) {
        held(area)[path] = bytes
    }

    fun remove(area: FileArea, path: String) {
        held(area).remove(path)
    }

    /** Append [text] to a file — how a process's log grows. */
    fun append(area: FileArea, path: String, text: String) {
        held(area).edit { files ->
            files + (path to (files[path]?.decodeToString().orEmpty() + text).encodeToByteArray())
        }
    }

    /** Make a present file unreadable to the app ([denied]), or readable again — a file the OS protects. */
    fun deny(area: FileArea, path: String, denied: Boolean = true) {
        if (denied) this.denied[area to path] = Unit else this.denied.remove(area to path)
    }

    fun isDenied(area: FileArea, path: String): Boolean = (area to path) in denied

    /** The bytes the files in both areas take, the paths [leftOut] names excepted. */
    fun bytes(leftOut: (FileArea, String) -> Boolean = { _, _ -> false }): Long = FileArea.entries.sumOf { area ->
        held(area).snapshot().filterKeys { !leftOut(area, it) }.values.sumOf { it.size.toLong() }
    }
}

/**
 * The device's SQLite databases: real in-memory SQLite, one per name, held for the mock's lifetime — so a relaunched
 * process opens what the last one wrote. [refusals] answers an open of that name with the refusal given. With a
 * [directory], each is a file there instead, which outlives the process — the launch-time adapters' persisted state.
 *
 * The inspector's hand — the opens it saw, the lock, deleting them all, its own reads around every fault — is here, on
 * the mock: plumbing, never a lever a test pulls.
 */
class DatabasesMock(refusals: Map<String, DbOpen> = emptyMap(), directory: String? = null) {
    private val held = InMemoryDatabases(refusals, directory)

    /**
     * A process's face: the same databases, as every process on the device opens the same files — and its own, so what
     * another process commits reaches it ([Databases.listen]). One per process: each call is a new one.
     */
    fun port(): Databases = held.process()

    /** The operator's own face over the same databases: what it reads is never a statement a fault was armed for. */
    fun inspectorPort(): Databases = held.process(faulted = false)

    val operator: DatabasesOperator = DatabasesOperator(this)

    /** Every open the device saw, by name, in order — a process's opens and every later one's. */
    val opened: List<String> get() = held.opened

    /** Whether the databases are locked ([lock]). */
    val locked: Boolean get() = held.locked

    /**
     * Make every database unreadable, or readable again — a device before its first unlock since it restarted, when iOS
     * keeps every App-Group file closed: an open fails, and so does every statement on a database a process already
     * holds (a state no phone is in — nothing can have opened one before the first unlock — kept so a test that locks
     * mid-process reads nothing). The channel's is `DeviceLevers.lock`, which seals the other stores with it.
     */
    fun lock(on: Boolean = true) {
        held.locked = on
    }

    /** The next [times] statements of [kind] on [table] of [database] fail ([DatabasesLevers.fail]). */
    fun fail(database: String, table: String, kind: WriteKind, times: Int = 1) = held.arm(database, table, kind, times)

    /** Told each fault as the app's statement spends it. */
    fun watch(listener: DatabasesWatcher) {
        held.faultWatcher = listener
    }

    /** Delete every database, as deleting the app does (in-memory databases only). */
    fun deleteAll() = held.deleteAll()

    /**
     * Delete database [name] alone (in-memory databases only): its next open creates it anew — how the inspector puts a
     * database an older build left in its place. A process that holds it keeps the copy it holds until it dies.
     */
    fun delete(name: String) = held.delete(name)

    internal val armed: List<DatabaseFault> get() = held.armed
}

/** The faults a real SQLite answers, which a test plays on the device's databases ([DatabasesLevers]). */
class DatabasesOperator internal constructor(private val mock: DatabasesMock) : DatabasesLevers {
    override suspend fun fail(database: String, table: String, kind: WriteKind, times: Int?) =
        mock.fail(database, table, kind, times ?: 1)

    override suspend fun switches(): DatabaseSwitches = DatabaseSwitches(mock.armed)

    override suspend fun opened(): List<String> = mock.opened
}

/** The device's user defaults — read while the app writes them, so a [SnapshotMap]. */
class PreferencesMock {
    internal val values = SnapshotMap<String, String>()

    private val faults = ArmedFaults<String>()

    fun port(): Preferences = InMemoryPreferences(values, StoreRefusals(faults = faults))

    /**
     * The next [times] calls of [kind] on [key] refused — the inspector's hand behind the channel's
     * `PreferencesLevers.fail`, which names the item and the [told] event; a contract binding arms one with none.
     */
    fun fail(key: String, kind: StoreFault, times: Int = 1, told: WorldEvent? = null) =
        faults.arm(key, kind, times, told)

    /** Every key the device holds now: one snapshot, however the app writes meanwhile — the inspector's read. */
    fun keys(): Set<String> = values.snapshot().keys

    /** Told each refusal an armed fault names, as the app's call spends it. */
    fun watch(listener: StoreWatcher) {
        faults.watcher = listener
    }
}

/**
 * The device's Keychain: one value per slot, [seed] already present — the device id of an app that launched before.
 * Both processes write it and an inspector reads it, so a [SnapshotMap].
 */
class SecureStoreMock(seed: Map<SecureSlot, String> = emptyMap()) {
    internal val items = SnapshotMap<SecureSlot, SecureStoreRead.Found>().apply {
        putAll(seed.mapValues { SecureStoreRead.Found(it.value, StoredProtection.BACKGROUND_READABLE) })
    }

    private val faults = ArmedFaults<SecureSlot>()

    /**
     * Whether every call is refused — a device not unlocked since it restarted. The channel's is `DeviceLevers.lock`;
     * the inspector's own hand below goes around it.
     */
    var sealed: Boolean
        get() = seal.on
        set(on) {
            seal.on = on
        }

    private val seal = Seal()

    /** A process's face. */
    fun port(): SecureStore = InMemorySecureStore(items, StoreRefusals(seal, faults))

    /**
     * The next [times] calls of [kind] on [slot] refused — the inspector's hand behind the channel's
     * `KeychainLevers.fail`, which names the item and the [told] event.
     */
    fun fail(slot: SecureSlot, kind: StoreFault, times: Int = 1, told: WorldEvent? = null) =
        faults.arm(slot, kind, times, told)

    /** Told each refusal an armed fault names, as the app's call spends it. */
    fun watch(listener: StoreWatcher) {
        faults.watcher = listener
    }

    /** [slot]'s item gone, everything else kept — the inspector's hand behind the channel's `KeychainLevers.forget`. */
    fun forget(slot: SecureSlot) {
        items.remove(slot)
    }
}
