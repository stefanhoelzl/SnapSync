package app.snapsync.world

import app.snapsync.fake.inMemoryDownloadStore
import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore

/** The honest `:adapter:generic:fake` ledger store satisfies the storage-seam contract. */
class InMemoryLedgerStoreTest : LedgerStoreContract() {
    override fun createBackend(): LedgerStore = inMemoryLedgerStore()
}

/** The honest `:adapter:generic:fake` download store satisfies the contract (also the harness/integration impl). */
class InMemoryDownloadStoreTest : DownloadStoreContract() {
    override fun createStore(): DownloadStore = inMemoryDownloadStore()
}
