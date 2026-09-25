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

import app.snapsync.databases.freshJdbcDatabases
import app.snapsync.services.downloads.DownloadService

/** Runs the shared [DownloadStoreContract] through [DownloadService] over the real JVM `Databases` adapter. */
class DownloadServiceTest {

    /** The contract, bound on this host (JVM). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<DownloadStoreState, DownloadStore> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String) = Entered.Ready(createStore())
    }

    @Test
    fun `satisfies the DownloadStore contract`() = verify(DownloadStoreContract, binding)
    /** The service over the real JVM adapter, in a directory of its own: the contract runs through the service. */
    private fun createStore(): DownloadStore = DownloadService(freshJdbcDatabases())
}
