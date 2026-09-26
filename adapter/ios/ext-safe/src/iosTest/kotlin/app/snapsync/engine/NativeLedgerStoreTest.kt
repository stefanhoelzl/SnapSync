package app.snapsync.engine

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LedgerStoreContract
import app.snapsync.contracts.LedgerStoreState
import app.snapsync.contracts.verify
import app.snapsync.databases.IosDatabases
import app.snapsync.services.ledger.LedgerService
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * Runs the shared [LedgerStoreContract] through [LedgerService] over the real [IosDatabases] — the gap CI's
 * `ios-test` job exists for: the native driver, schema creation, and the enum column adapter on Kotlin/Native.
 * Each clause gets a directory of its own.
 */
class NativeLedgerStoreTest {

    /** The contract, bound on this host (IOS_SIM_KEXE). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<LedgerStoreState, LedgerService> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(LedgerStoreState.EMPTY)
        override fun create(state: LedgerStoreState, clauseId: String): Entered<LedgerService> {
            val dir = newTempDirectory()
            return Entered.Ready(LedgerService(IosDatabases(dir))) { removeDirectory(dir) }
        }
    }

    @Test
    fun `satisfies the LedgerService contract`() = verify(LedgerStoreContract, binding)
}
