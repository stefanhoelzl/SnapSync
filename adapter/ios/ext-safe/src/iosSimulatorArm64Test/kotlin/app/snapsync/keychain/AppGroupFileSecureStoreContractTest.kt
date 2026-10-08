package app.snapsync.keychain

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.SecureStore
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.writeTextFile
import kotlin.test.Test

/**
 * The simulator target's file-backed [SecureStore], live (`docs/architecture.md`), addressed at the contract's
 * slot. The directory is injected, so a clause gets a fresh one: present for the readable states, absent — the
 * unavailable container — for [SecureStoreState.INACCESSIBLE].
 *
 * It cannot hold a legacy-protected item: the file is always written background-readable, and that is
 * what a read honestly reports.
 */
class AppGroupFileSecureStoreContractTest {

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            SecureStoreState.INACCESSIBLE,
            SecureStoreState.EMPTY,
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
        )

        override fun create(state: SecureStoreState, clauseId: String, log: CallLog): Entered<SecureStore> {
            if (state == SecureStoreState.INACCESSIBLE) {
                return Entered.Ready(AppGroupFileSecureStore(directory = null).recorded(log))
            }
            if (state == SecureStoreState.HOLDING_RESTRICTED) {
                return Entered.Unreachable("a file store is always written background-readable")
            }
            if (state == SecureStoreState.HOLDING_UNDER_A_LOST_KEY) {
                return Entered.Unreachable("a file store seals nothing under a key")
            }
            val dir = newTempDirectory()
            if (state == SecureStoreState.HOLDING_BACKGROUND_READABLE) {
                // Seeded as a raw file, not through the store under test: one file per slot, named for it.
                val slot = SecureStoreContract.slot(clauseId)
                writeTextFile(
                    "$dir/${slot.service}.${slot.account}.simulator.json",
                    SecureStoreContract.seedValue(clauseId),
                )
            }
            return Entered.Ready(AppGroupFileSecureStore(dir).recorded(log)) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the App-Group file store satisfies the SecureStore contract`() = verify(SecureStoreContract, binding)
}
