package app.snapsync.android.storage

import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.AttestStore
import app.snapsync.services.identity.AttestState
import java.security.KeyStore
import kotlin.test.Test

/**
 * The attestation store as Android composes it — `AttestState` over the Keystore-sealed [AndroidSecureStore] — against
 * the [AttestStoreContract] on the emulator. [AttestStoreState.INACCESSIBLE] is the store's directory with every
 * permission taken, which reads Unavailable.
 */
class AndroidAttestStoreContractTest {

    private val binding = object : Binding<AttestStoreState, AttestStore> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            AttestStoreState.INACCESSIBLE,
            AttestStoreState.EMPTY,
            AttestStoreState.HOLDING,
            AttestStoreState.HOLDING_CONTESTED,
        )

        override fun create(state: AttestStoreState, clauseId: String, log: CallLog): Entered<AttestStore> {
            deleteKey()
            val dir = newTempDirectory()
            val store = AttestState(AndroidSecureStore(dir, TEST_ALIAS))
            when (state) {
                AttestStoreState.INACCESSIBLE -> {
                    store.setToken("not the seed")
                    revokeAllAccess(dir)
                }
                AttestStoreState.HOLDING, AttestStoreState.HOLDING_CONTESTED -> {
                    store.setKeyId(AttestStoreContract.seedKeyId(clauseId))
                    store.setToken(AttestStoreContract.seedToken(clauseId))
                }
                AttestStoreState.EMPTY -> Unit
            }
            return Entered.Ready(store.recorded(log)) {
                restoreOwnerAccess(dir)
                dir.deleteRecursively()
                deleteKey()
            }
        }
    }

    private fun deleteKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)

    @Test
    fun `the Keystore-sealed store satisfies the AttestStore contract`() = verify(AttestStoreContract, binding)

    private companion object {
        const val TEST_ALIAS = "app.snapsync.contract.atteststore"
    }
}
