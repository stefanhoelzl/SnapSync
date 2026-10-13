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
 * The simulator's secure store ([SimulatorSecureStore]) — what the simulator app composes — against the
 * [SecureStoreContract]: the router itself, over the real Keychain and a file store in a fresh temp directory. The
 * contract addresses a shared slot, which the router hands to its file store, so it enters the states that store does:
 * readable for the readable states, no container for [SecureStoreState.INACCESSIBLE].
 */
class SimulatorSecureStoreContractTest {

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = SimulatorSecureStore::class
        override val reaches = setOf(
            SecureStoreState.INACCESSIBLE,
            SecureStoreState.EMPTY,
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
        )

        override fun create(state: SecureStoreState, clauseId: String, log: CallLog): Entered<SecureStore> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "a shared slot is a file, always written background-readable",
                )
            }
            if (state == SecureStoreState.INACCESSIBLE) {
                return Entered.Ready(
                    SimulatorSecureStore(IosSecureStore(), AppGroupFileSecureStore(directory = null)).recorded(log),
                )
            }
            val dir = newTempDirectory()
            if (state == SecureStoreState.HOLDING_BACKGROUND_READABLE) {
                val slot = SecureStoreContract.slot(clauseId)
                writeTextFile(
                    "$dir/${slot.service}.${slot.account}.simulator.json",
                    SecureStoreContract.seedValue(clauseId),
                )
            }
            return Entered.Ready(SimulatorSecureStore(IosSecureStore(), AppGroupFileSecureStore(dir)).recorded(log)) {
                removeDirectory(dir)
            }
        }
    }

    @Test
    fun `the simulator's routing secure store satisfies the SecureStore contract`() = verify(
        SecureStoreContract,
        binding,
    )
}
