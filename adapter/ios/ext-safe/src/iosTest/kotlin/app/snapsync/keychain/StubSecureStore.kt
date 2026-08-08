package app.snapsync.keychain

import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection

/**
 * An in-memory [SecureStore] that also **records what was asked of it**.
 *
 * The recording is the point, not the storage. Everything this module's Keychain logic can get
 * catastrophically wrong is a question of *which item was consulted and whether anything was
 * written* — the 2026-07-20 split identity was two successful reads of two different items, and the
 * build-297 crash was a write that should never have been attempted. Neither is visible in a
 * return value, so the counters below are what the assertions are actually about.
 *
 * `write` updates the stored answer, so a resolve-then-read sequence behaves like the real thing:
 * a minted or adopted value is readable afterwards, which is how "the id is written exactly once"
 * can be asserted rather than assumed.
 */
internal class StubSecureStore(private var answer: SecureStoreRead = SecureStoreRead.Absent) : SecureStore {

    var reads: Int = 0
        private set

    var migrations: Int = 0
        private set

    var deletes: Int = 0
        private set

    val writes: MutableList<String> = mutableListOf()

    override fun read(): SecureStoreRead {
        reads++
        return answer
    }

    override fun write(value: String) {
        writes += value
        answer = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
    }

    override fun migrateProtection() {
        migrations++
        val found = answer as? SecureStoreRead.Found ?: return
        // In place, value preserved — the real adapter's SecItemUpdate supplies no value either.
        answer = SecureStoreRead.Found(found.value, StoredProtection.BACKGROUND_READABLE)
    }

    override fun delete() {
        deletes++
        answer = SecureStoreRead.Absent
    }

    /** True when nothing was ever persisted through this item — the never-mint invariant's oracle. */
    fun untouched(): Boolean = writes.isEmpty() && deletes == 0 && migrations == 0
}
