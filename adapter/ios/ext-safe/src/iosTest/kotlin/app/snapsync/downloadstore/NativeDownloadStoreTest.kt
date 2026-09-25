package app.snapsync.downloadstore

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DownloadStoreContract
import app.snapsync.contracts.DownloadStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.databases.IosDatabases
import app.snapsync.ports.DownloadStore
import app.snapsync.services.downloads.DownloadService
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * Runs the shared [DownloadStoreContract] through [DownloadService] over the real [IosDatabases] — the download
 * half of the gap CI's `ios-test` job exists for: the native driver, schema creation, the migrations, and the
 * `DownloadState` enum column adapter on Kotlin/Native. Each clause gets a directory of its own, so no row leaks
 * between clauses.
 */
class NativeDownloadStoreTest {

    /** The contract, bound on this host (IOS_SIM_KEXE). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<DownloadStoreState, DownloadStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(DownloadStoreState.EMPTY)
        override fun create(state: DownloadStoreState, clauseId: String): Entered<DownloadStore> {
            val dir = newTempDirectory()
            return Entered.Ready(DownloadService(IosDatabases(dir))) { removeDirectory(dir) }
        }
    }

    @Test
    fun `satisfies the DownloadStore contract`() = verify(DownloadStoreContract, binding)
}
