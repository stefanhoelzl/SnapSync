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

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase

/**
 * Runs the shared [DownloadStoreContract] against the real native-driver-backed
 * [SqlDelightDownloadStore] — the download half of the gap CI's `ios-test` job exists for: the
 * native driver, schema creation, the two migrations, and the `DownloadState` enum column adapter
 * on Kotlin/Native.
 *
 * The ledger has had this since `NativeLedgerStoreTest`; this store did not, so its row semantics
 * were asserted on the JVM only. That asymmetry was invisible by construction: `IosDownloadStoreTest`
 * (`:adapter:ios:ext-safe`) covers **placement** and explicitly defers row semantics to "the shared
 * `SqlDelightDownloadStore` and its storage contract" — a deferral with nothing on this target to
 * land on. Both stores carry the same `EnumColumnAdapter`, so the encoding one of them proves on
 * Native the other was only assuming.
 *
 * In-memory for test isolation; the on-disk path is exercised by the manual app run.
 */
class NativeDownloadStoreTest {

    /** The contract, bound on this host (IOS_SIM_KEXE). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<DownloadStoreState, DownloadStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String) = Entered.Ready(createStore())
    }

    @Test
    fun `satisfies the DownloadStore contract`() = verify(DownloadStoreContract, binding)

    private fun createStore(): DownloadStore {
        // A UNIQUE name per store, for the reason `NativeLedgerStoreTest` records: an in-memory
        // NativeSqliteDriver keyed by a fixed name uses a shared-cache `:memory:` db, so every
        // createStore() would reuse one database and leak rows across tests — unlike
        // JdbcSqliteDriver.IN_MEMORY, which is private per driver, which is why the JVM contract
        // passes with a constant. The counter is process-global (companion) because kotlin.test
        // builds a fresh test-class instance per @Test.
        val driver = NativeSqliteDriver(
            DownloadDatabase.Schema,
            "download-test-${nextDb++}.db",
            onConfiguration = { it.copy(inMemory = true) },
        )
        return SqlDelightDownloadStore(DownloadDatabase(driver))
    }

    private companion object {
        var nextDb = 0
    }
}
