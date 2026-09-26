package app.snapsync.fake

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DownloadStoreContract
import app.snapsync.contracts.DownloadStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.LedgerStoreContract
import app.snapsync.contracts.LedgerStoreState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import kotlin.test.Test

/**
 * The two database services' contracts over [inMemoryDatabases] — the in-memory SQLite every feature test and the world
 * stand on, held to the same clauses the file-backed databases are (`:adapter:generic:app`'s JDBC binding).
 */
class StoreContractBindingsTest {

    private val ledger = object : Binding<LedgerStoreState, LedgerService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(LedgerStoreState.EMPTY)
        override fun create(state: LedgerStoreState, clauseId: String) = Entered.Ready(LedgerService(inMemoryDatabases()))
    }

    private val download = object : Binding<DownloadStoreState, DownloadService> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String) = Entered.Ready(DownloadService(inMemoryDatabases()))
    }

    @Test
    fun `the ledger service over in-memory databases satisfies the LedgerStore contract`() = verify(LedgerStoreContract, ledger)

    @Test
    fun `the download service over in-memory databases satisfies the DownloadStore contract`() =
        verify(DownloadStoreContract, download)
}
