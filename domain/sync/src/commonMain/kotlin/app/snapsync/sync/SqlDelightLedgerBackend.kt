package app.snapsync.sync

import app.snapsync.sync.db.LedgerDatabase

/**
 * [LedgerBackend] over the SQLDelight [LedgerDatabase] (schema: `Ledger.sq` — one table, key
 * primary key, no timestamps, no further indexes). [put] is the single `INSERT OR REPLACE`
 * statement, atomic on its own; the driver decides where the database lives (JVM sqlite for
 * tests today; native driver with an App-Group path is the iOS slice's).
 */
class SqlDelightLedgerBackend(database: LedgerDatabase) : LedgerBackend {

    private val queries = database.ledgerQueries

    override suspend fun get(key: String): LedgerEntry? =
        queries.get(key) { _, state, attempt, version ->
            LedgerEntry(key, LedgerState.valueOf(state), attempt.toInt(), version)
        }.executeAsOneOrNull()

    override suspend fun put(entry: LedgerEntry) {
        queries.put(entry.key, entry.state.name, entry.attempt.toLong(), entry.version)
    }
}
