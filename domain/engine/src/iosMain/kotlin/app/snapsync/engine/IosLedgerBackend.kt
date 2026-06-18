package app.snapsync.engine

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.db.LedgerDatabase

/**
 * The iOS [LedgerBackend]: the shared [SqlDelightLedgerBackend] over a native SQLite driver,
 * persisting **on disk in the app sandbox** (the driver's default location) so the ledger
 * survives process death — the iOS UI is a projection of state written while the app was dead.
 *
 * This factory is the single site that names the database location. The App-Group container path
 * (so the background upload extension, a separate process, can write the same ledger) arrives with
 * the extension slice; migrating sandbox → App-Group is a one-line change here.
 */
fun iosLedgerBackend(): LedgerBackend =
    SqlDelightLedgerBackend(LedgerDatabase(NativeSqliteDriver(LedgerDatabase.Schema, "ledger.db")))
