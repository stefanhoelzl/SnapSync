package app.snapsync.world

import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.fake.InMemoryLedgerStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore

/** The honest `:adapter:fake` ledger store satisfies the storage-seam contract. */
class InMemoryLedgerStoreTest : LedgerStoreContract() {
    override fun createBackend(): LedgerStore = InMemoryLedgerStore()
}

/** The honest `:adapter:fake` download store satisfies the contract (also the harness/integration impl). */
class InMemoryDownloadStoreTest : DownloadStoreContract() {
    override fun createStore(): DownloadStore = InMemoryDownloadStore()
}
