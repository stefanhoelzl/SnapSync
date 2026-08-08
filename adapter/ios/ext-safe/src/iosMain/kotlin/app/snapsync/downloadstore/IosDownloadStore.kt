package app.snapsync.downloadstore

import app.snapsync.ports.SuppressionSource

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase
import app.snapsync.engine.LEDGER_APP_GROUP
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/** The download store's own DB file — distinct from the extension-written `ledger.db`. */
private const val DOWNLOAD_DB_NAME: String = "downloads.db"

/**
 * The iOS [DownloadStore]: the shared [SqlDelightDownloadStore] over a native SQLite driver in the
 * App-Group container, so the **app** (sole writer) and the **upload extension** (read-only reader of
 * the suppression projection) see one store. A distinct file from `ledger.db`, so each store keeps a
 * single writer per file (the ledger is extension-written; this is app-written) — WAL allows the
 * extension's concurrent read.
 *
 * [basePath] is a parameter for the same reason as [app.snapsync.engine.iosLedgerStore]'s: the
 * container's location belongs to the composition, and injecting it is what lets a test open a real
 * database at all (a bundle-less test binary can never resolve the App Group).
 */
@OptIn(ExperimentalForeignApi::class)
fun iosDownloadStore(basePath: String = appGroupContainerPath(LEDGER_APP_GROUP)): SqlDelightDownloadStore {
    val driver = NativeSqliteDriver(
        schema = DownloadDatabase.Schema,
        name = DOWNLOAD_DB_NAME,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
            )
        },
    )
    protectDownloadFiles(basePath)
    return SqlDelightDownloadStore(DownloadDatabase(driver))
}

/**
 * The extension's **read-only** view of the download store: the suppression projection only
 * ([SuppressionSource.suppressedLocalIds]), never the full app-side [DownloadStore] surface. The
 * upload extension's composition root wires this so the extension is **compile-prevented** from writing
 * or reading anything beyond the suppression set (capability `download-store`). Backed by the same
 * App-Group store the app writes; the extension reads it over WAL.
 */
fun iosSuppressionSource(basePath: String = appGroupContainerPath(LEDGER_APP_GROUP)): SuppressionSource =
    iosDownloadStore(basePath)

@OptIn(ExperimentalForeignApi::class)
private fun protectDownloadFiles(basePath: String) {
    val attributes = mapOf<Any?, Any?>(
        NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication,
    )
    for (suffix in listOf("", "-wal", "-shm")) {
        NSFileManager.defaultManager.setAttributes(attributes, "$basePath/$DOWNLOAD_DB_NAME$suffix", error = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun appGroupContainerPath(appGroup: String): String =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroup)
        ?.path
        ?: error("App Group container '$appGroup' unavailable — the application-groups entitlement is missing or unprovisioned")
