package app.snapsync.data.store

/**
 * A database could not be opened or a statement on it failed: "I could not look" (`docs/architecture.md`, "Absence is
 * never silent") — MIGRATING, what [OwnedDatabase.use] throws to the old zones, whose callers already handle a store
 * that cannot be reached (the cycle's gate reads the ledger under a guard; a locked device's read fails the same way
 * it always did).
 */
class DatabaseUnavailable(val name: String, val detail: String) :
    IllegalStateException("database $name unavailable: $detail")
