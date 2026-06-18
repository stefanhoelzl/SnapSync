package app.snapsync.engine

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

/** The App Group shared by the host app and the background-upload extension. */
const val LEDGER_APP_GROUP: String = "group.app.snapsync"

/**
 * The iOS [LedgerBackend]: the shared [SqlDelightLedgerBackend] over a native SQLite driver,
 * persisting **on disk in the [LEDGER_APP_GROUP] container** so the ledger is shared between the
 * host app (which reads it) and the background-upload extension (the single writer), and survives
 * process death. WAL is the native driver's default journal mode — one cross-process writer plus
 * concurrent readers.
 *
 * This factory is the single site that names the database location. It wraps the backend in
 * [DarwinCrossProcessLedgerBackend] so a `put` in the extension process dings a watcher in the app
 * process (the in-process [SqlDelightLedgerBackend.changes] ding never crosses the process line).
 */
@OptIn(ExperimentalForeignApi::class)
fun iosLedgerBackend(): LedgerBackend {
    val basePath = appGroupContainerPath(LEDGER_APP_GROUP)
    val driver = NativeSqliteDriver(
        schema = LedgerDatabase.Schema,
        name = "ledger.db",
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
            )
        },
    )
    return DarwinCrossProcessLedgerBackend(SqlDelightLedgerBackend(LedgerDatabase(driver)))
}

/**
 * The filesystem path of the App-Group shared container — the single place both the app and the
 * extension agree the ledger lives. The container requires the `application-groups` entitlement;
 * with it provisioned this always returns the shared path. Without it there is no shared ledger and
 * the whole feature is broken, so we **fail fast** (a sandbox-local ledger would silently not be
 * shared with the extension — strictly worse than a loud crash that names the misconfiguration).
 */
@OptIn(ExperimentalForeignApi::class)
private fun appGroupContainerPath(appGroup: String): String =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroup)
        ?.path
        ?: error("App Group container '$appGroup' unavailable — the application-groups entitlement is missing or unprovisioned")
