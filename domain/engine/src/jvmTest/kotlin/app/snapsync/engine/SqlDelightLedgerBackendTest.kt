package app.snapsync.engine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SqlDelightLedgerBackendTest : LedgerBackendContract() {

    override fun createBackend(): LedgerBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerBackend(LedgerDatabase(driver))
    }

    @Test
    fun `retainKeys over a keep-set past the bind-variable limit deletes the complement`() = runTest {
        val backend = createBackend()
        // Far past sqlite's single-statement bind-variable limit (32766): a naive
        // `WHERE key NOT IN (…)` would throw. retainKeys must never bind the keep-set into one
        // statement — it diffs in Kotlin and deletes the (small) complement key-by-key.
        val t0 = Instant.fromEpochMilliseconds(1_000_000)
        val keep = (0 until 40_000).mapTo(mutableSetOf()) { "k$it" }
        keep.forEach { backend.put(LedgerEntry(it, LedgerState.REQUESTED, 0, "v1", t0)) }
        backend.put(LedgerEntry("straggler", LedgerState.REQUESTED, 0, "v1", t0))

        backend.retainKeys(keep)

        assertNull(backend.get("straggler"))
        assertEquals(keep.size, backend.aggregates().pending)
    }
}
