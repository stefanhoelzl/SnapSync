package app.snapsync.downloadstore

import app.snapsync.ports.DownloadStore
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.DownloadStoreContract
import app.snapsync.contracts.DownloadStoreState
import app.snapsync.contracts.verify
import kotlin.test.Test

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase

/** Runs the shared [DownloadStoreContract] against the real SQLDelight store over an in-memory JDBC driver. */
class SqlDelightDownloadStoreTest {

    /** The contract, bound on this host (JVM). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<DownloadStoreState, DownloadStore> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String) = Entered.Ready(createStore())
    }

    @Test
    fun `satisfies the DownloadStore contract`() = verify(DownloadStoreContract, binding)
    private fun createStore(): DownloadStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        return SqlDelightDownloadStore(DownloadDatabase(driver))
    }
}
