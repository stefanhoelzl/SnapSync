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
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore
import kotlin.test.Test

/**
 * The honest storage fakes, held to the same contracts as the SQLDelight stores (capability
 * `port-contracts`). Bound here because only this module's test source set can construct an `internal`
 * fake; `commonTest`, so they run on the JVM and on the simulator.
 */
class StoreContractBindingsTest {

    private val ledger = object : Binding<LedgerStoreState, LedgerStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(LedgerStoreState.EMPTY)
        override fun create(state: LedgerStoreState, clauseId: String) = Entered.Ready(inMemoryLedgerStore())
    }

    private val download = object : Binding<DownloadStoreState, DownloadStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String) = Entered.Ready(inMemoryDownloadStore())
    }

    @Test
    fun `the in-memory ledger satisfies the LedgerStore contract`() = verify(LedgerStoreContract, ledger)

    @Test
    fun `the in-memory download store satisfies the DownloadStore contract`() = verify(DownloadStoreContract, download)
}
