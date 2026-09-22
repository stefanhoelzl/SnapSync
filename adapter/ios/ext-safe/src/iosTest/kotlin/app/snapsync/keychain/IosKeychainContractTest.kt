package app.snapsync.keychain

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.verify
import app.snapsync.ports.SecureStore
import kotlin.test.Test

/**
 * The real [IosKeychain], live, in the simulator's Kotlin/Native test executable (capability
 * `port-contracts`). That host is unentitled: `securityd` refuses it every `SecItem*` call with
 * `-25291` (`errSecNotAvailable`), so the only state it can present is [SecureStoreState.INACCESSIBLE] —
 * which is also the state the build-297 crash lived in, run here against the real API on every build.
 *
 * Every other state is reached on the entitled device, recorded there and replayed in CI
 * (`IosKeychainReplayContractTest`).
 */
class IosKeychainContractTest {

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(SecureStoreState.INACCESSIBLE)

        override fun create(state: SecureStoreState, clauseId: String): Entered<SecureStore> =
            if (state == SecureStoreState.INACCESSIBLE) {
                Entered.Ready(IosKeychain(service = "app.snapsync.contract", account = clauseId))
            } else {
                Entered.Unreachable("unentitled test executable: securityd refuses every Keychain call (-25291)")
            }
    }

    @Test
    fun `the Keychain satisfies the SecureStore contract on this host`() = verify(SecureStoreContract, binding)
}
