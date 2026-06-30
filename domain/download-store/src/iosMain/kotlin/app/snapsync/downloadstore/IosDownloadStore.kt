package app.snapsync.downloadstore

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/** The App Group shared by the host app and the background-upload extension (same as the ledger's). */
private const val DOWNLOAD_APP_GROUP: String = "group.app.snapsync"

/** The download store's own DB file — distinct from the extension-written `ledger.db`. */
private const val DOWNLOAD_DB_NAME: String = "downloads.db"

/**
 * The iOS [DownloadStore]: the shared [SqlDelightDownloadStore] over a native SQLite driver in the
 * App-Group container, so the **app** (sole writer) and the **upload extension** (read-only reader of
 * the suppression projection) see one store. A distinct file from `ledger.db`, so each store keeps a
 * single writer per file (the ledger is extension-written; this is app-written) — WAL allows the
 * extension's concurrent read.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosDownloadStore(): SqlDelightDownloadStore {
    val basePath = appGroupContainerPath(DOWNLOAD_APP_GROUP)
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
