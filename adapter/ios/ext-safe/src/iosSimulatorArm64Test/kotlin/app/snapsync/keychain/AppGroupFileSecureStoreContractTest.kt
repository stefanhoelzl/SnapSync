package app.snapsync.keychain

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.verify
import app.snapsync.ports.SecureStore
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.writeTextFile
import kotlin.test.Test

/**
 * The simulator target's file-backed [SecureStore], live (capability `port-contracts`). The directory is
 * injected, so a clause gets a fresh one: present for the readable states, absent — the unavailable
 * container — for [SecureStoreState.INACCESSIBLE].
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

        override fun create(state: SecureStoreState, clauseId: String): Entered<SecureStore> {
            if (state == SecureStoreState.INACCESSIBLE) {
                return Entered.Ready(AppGroupFileSecureStore(FILE) { null })
            }
            if (state == SecureStoreState.HOLDING_RESTRICTED) {
                return Entered.Unreachable("a file store is always written background-readable")
            }
            val dir = newTempDirectory()
            if (state == SecureStoreState.HOLDING_BACKGROUND_READABLE) {
                writeTextFile("$dir/$FILE", SecureStoreContract.seedValue(clauseId))
            }
            return Entered.Ready(AppGroupFileSecureStore(FILE) { dir }) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the App-Group file store satisfies the SecureStore contract`() = verify(SecureStoreContract, binding)

    private companion object {
        const val FILE = "deviceid.contract.json"
    }
}
