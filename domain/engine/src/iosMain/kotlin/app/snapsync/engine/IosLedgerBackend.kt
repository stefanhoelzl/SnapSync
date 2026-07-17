package app.snapsync.engine

import app.snapsync.model.LedgerBackend

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/** The App Group shared by the host app and the background-upload extension. */
const val LEDGER_APP_GROUP: String = "group.app.snapsync"

/**
 * The App-Group `NSUserDefaults` key the extension persists its discovery change-token cursor under.
 * Shared here so the extension (which writes and clears it across its re-join reconciliation) cannot
 * drift on the key name.
 */
const val DISCOVERY_TOKEN_KEY: String = "discovery.changeToken"

/**
 * The App-Group `NSUserDefaults` key the extension persists its `joinedEventId` re-join marker under —
 * the last event it reconciled (capability `event-rejoin-reconciliation`). It is the join signal in
 * the extension's short-lived process, where ledger-emptiness cannot be (a zero-row join would never
 * settle). Lives beside [DISCOVERY_TOKEN_KEY] so the extension's keys stay in one place.
 */
const val JOINED_EVENT_KEY: String = "rejoin.joinedEventId"

/** The extension-written ledger's DB filename inside the [LEDGER_APP_GROUP] container. */
private const val LEDGER_DB_NAME: String = "ledger.db"

/**
 * The iOS [LedgerBackend]: the shared [SqlDelightLedgerBackend] over a native SQLite driver,
 * persisting **on disk in the [LEDGER_APP_GROUP] container** so the ledger is shared between the
 * host app and the background-upload extension (the single writer), and survives process death. WAL
 * is the native driver's default journal mode — one cross-process writer plus concurrent readers.
 *
 * This factory is the single site that names the database location. The ledger is the extension's
 * private upload memory — the app no longer watches it for status (status derives from storage
 * truth) — so there is no cross-process change notification: the in-process [LedgerBackend.changes]
 * ding the extension's own cycle uses is all that is needed.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosLedgerBackend(): LedgerBackend {
    val basePath = appGroupContainerPath(LEDGER_APP_GROUP)
    val driver = NativeSqliteDriver(
        schema = LedgerDatabase.Schema,
        name = LEDGER_DB_NAME,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
            )
        },
    )
    protectLedgerFiles(basePath)
    return SqlDelightLedgerBackend(LedgerDatabase(driver))
}

/**
 * Mark the ledger DB files `NSFileProtectionCompleteUntilFirstUserAuthentication` (data-protection
 * Class C) so the background-upload extension can open the ledger when the system runs it on a
 * **locked** device (Class C files are readable after the first unlock since boot). This is the iOS
 * default today, but set it explicitly so the locked-device guarantee does not silently depend on
 * the absence of a `default-data-protection = NSFileProtectionComplete` entitlement — which would
 * flip the files to Class A (unreadable while locked) and break the extension. WAL mode means three
 * files; protect each (best-effort: `-wal`/`-shm` may not exist until the first write, and a missing
 * file is a harmless no-op).
 */
@OptIn(ExperimentalForeignApi::class)
private fun protectLedgerFiles(basePath: String) {
    val attributes = mapOf<Any?, Any?>(
        NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication,
    )
    for (suffix in listOf("", "-wal", "-shm")) {
        NSFileManager.defaultManager.setAttributes(attributes, "$basePath/$LEDGER_DB_NAME$suffix", error = null)
    }
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
