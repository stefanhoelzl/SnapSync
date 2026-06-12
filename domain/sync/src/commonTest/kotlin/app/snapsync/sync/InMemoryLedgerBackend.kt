package app.snapsync.sync

/**
 * Test seam double: the dumbest possible row store. Mirrors the backend contract exactly —
 * verbatim storage, last write wins, no interpretation.
 */
class InMemoryLedgerBackend : LedgerBackend {

    private val rows = mutableMapOf<String, LedgerEntry>()

    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun put(entry: LedgerEntry) {
        rows[entry.key] = entry
    }
}
