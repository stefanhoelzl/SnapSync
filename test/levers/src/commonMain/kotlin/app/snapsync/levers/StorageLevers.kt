package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The device's stores as typed services (`docs/testing.md`, "Typed services"): what a test may do to the disk, the
// databases and the Keychain the app keeps its state in. Never what they hold — the app's private files and databases
// are not observable (`docs/testing.md` §7, "The rules") — but what the device itself sees of them: which databases a
// process opened, how many files sit in the staging directory, and the storage the app takes
// (`WorldEvent.Settled.state` carries the last too).

/**
 * **The device's SQLite databases' face** — the faults a real SQLite answers, which the app must survive. The databases
 * mock's operator implements it.
 */
@Rpc
interface DatabasesLevers {
    /**
     * Every database unreadable, or — [on] `false` — readable again: a device before its first unlock since boot, when
     * iOS keeps every App-Group file closed. An open fails, and so does every statement on a database already open.
     */
    suspend fun lock(on: Boolean)

    /**
     * The next [times] (omitted: one) statements of [kind] on [table] of [database] fail, as SQLite fails a write whose
     * lock another process holds: the statement throws and changes nothing, and the database stays open. Held to the
     * `Databases` contract's `A_REFUSED_WRITE_THROWS_AND_CHANGES_NOTHING`. A database the device has not created yet, or
     * a table it does not hold, is refused. Each failure is told as [WorldEvent.DatabaseFaulted].
     */
    suspend fun fail(database: String, table: String, kind: WriteKind, times: Int? = null)

    /** What the levers above put the databases in, as it stands now. */
    suspend fun switches(): DatabaseSwitches

    /** Every database any process on the device opened, by name, in order. */
    suspend fun opened(): List<String>

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<DatabasesLevers>(
        DatabasesLevers::class,
        "databases",
        setOf(LeverNeed.DATABASES_MOCKED),
        { serviceDescriptorOf() },
    )
}

/**
 * The kinds of statement [DatabasesLevers.fail] can fail — the writes a real SQLite was shown to refuse on a database
 * already open. A read is not among them: under the write-ahead log iOS keeps, a reader is never blocked.
 */
@Serializable
enum class WriteKind {
    @SerialName("insert")
    INSERT,

    @SerialName("update")
    UPDATE,

    @SerialName("delete")
    DELETE,
}

/** A fault [DatabasesLevers.fail] armed and not yet spent: how many more statements it fails. */
@Serializable
data class DatabaseFault(val database: String, val table: String, val kind: WriteKind, val remaining: Int)

/** Every switch an operator set on the databases ([DatabasesLevers]) as it stands now. */
@Serializable
data class DatabaseSwitches(val locked: Boolean, val faults: List<DatabaseFault>)

/** **The device's files' face** — what a test may add to them, and what the device counts of them. */
@Rpc
interface FilesLevers {
    /** [text] appended to [process]'s device log — what a diagnostic dump reads back. */
    suspend fun appendLog(process: LogProcess, text: String)

    /** How many files sit in the download staging directory — received bytes not yet in the library. */
    suspend fun stagedFiles(): Int

    /** The bytes the app's files take, its diagnostics left out — what the device's storage page shows a person. */
    suspend fun appStorageBytes(): Long

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<FilesLevers>(
        FilesLevers::class,
        "files",
        setOf(LeverNeed.FILES_MOCKED),
        { serviceDescriptorOf() },
    )
}

/** A process that keeps a device log of its own. */
@Serializable
enum class LogProcess {
    @SerialName("app")
    APP,

    @SerialName("extension")
    EXTENSION,
}

/** **The device's Keychain's face** — an item gone from it. */
@Rpc
interface KeychainLevers {
    /**
     * [item] gone from the Keychain while everything else stays — what a restore onto a new phone, or a reset of the
     * phone's own key protection, leaves.
     */
    suspend fun forget(item: KeychainItem)

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<KeychainLevers>(
        KeychainLevers::class,
        "keychain",
        setOf(LeverNeed.KEYCHAIN_MOCKED),
        { serviceDescriptorOf() },
    )
}

/**
 * The Keychain items a test may take away, by what they are — the channel maps each to the address the app keeps it
 * under. Only the items a test forgets: one joins when a test needs it gone.
 */
@Serializable
enum class KeychainItem {
    /** The joined event's key, when it is encrypted. */
    @SerialName("event-key")
    EVENT_KEY,
}

/**
 * **An install upgraded from an older build** — the state an older build left on the device that no path in the
 * current app produces, written over the device's own databases, disk and Preferences, each in the format the build that
 * kept it wrote (`docs/testing.md`, "Typed services").
 */
@Rpc
interface OlderInstallLevers {
    /**
     * An install upgraded from a build that predates per-asset byte release: a confirmed import of [device]'s (omitted:
     * a fixed foreign device) [asset] (omitted: `LEGACY`) whose resource rows, with their staged paths, survive, and
     * whose files are still on disk. Answers the bytes it left on disk.
     */
    suspend fun stagedBacklog(device: String? = null, asset: String? = null): Long

    /**
     * **What the device holds now, as an install whose databases are older left it** — the moment before an update:
     * `ledger.db` [ledgerBehind] versions behind this build's and `downloads.db` [downloadsBehind] behind (omitted: at
     * this build's, as they are). Each older database is built by this build's own migrations from the oldest version
     * the chain still builds, the rows each version's build wrote added as its version is passed — so a migration added
     * later adds a case by itself. What lived outside a database at that version is put back where it lived: the shared
     * records in their legacy files and keys, a transfer an earlier build started on the address it gave it. The app's
     * processes keep what they hold until they die: an update is a relaunch after this.
     *
     * Refused for a version older than the chain builds. Answers the versions left, and what the older formats hold.
     */
    suspend fun installedAt(ledgerBehind: Int? = null, downloadsBehind: Int? = null): SeededInstall

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<OlderInstallLevers>(
        OlderInstallLevers::class,
        "older-install",
        setOf(
            LeverNeed.FILES_MOCKED,
            LeverNeed.DATABASES_MOCKED,
            LeverNeed.PREFERENCES_MOCKED,
            LeverNeed.UPLOAD_SESSION_MOCKED,
            LeverNeed.DOWNLOADS_MOCKED,
        ),
        { serviceDescriptorOf() },
    )
}

/** What [OlderInstallLevers.installedAt] left: each database's version, and what the older formats hold. */
@Serializable
data class SeededInstall(
    /** `ledger.db`'s version now, and how many older ones the migration chain still builds below it. */
    val ledger: Int,
    val olderLedgers: Int,
    /** `downloads.db`'s version now, and how many older ones the migration chain still builds below it. */
    val downloads: Int,
    val olderDownloads: Int,
    /** Whether the shared records — membership, manifest record, pending leaves, albums, checks — are in their legacy homes. */
    val legacyRecords: Boolean,
    /** The own uploads left in flight to the byte route that names no event, by upload key. */
    val uploadsWithNoEvent: List<String>,
    /** The downloads left in flight under a description that names no event, by source asset. */
    val downloadsWithNoEvent: List<String>,
)
