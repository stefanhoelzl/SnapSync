package app.snapsync.android.storage

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import java.security.KeyStore
import kotlin.test.Test

/**
 * [SecureStoreContract] against the real [AndroidSecureStore] on the emulator's Keystore, under a key alias of this
 * test's own so the production key is never touched.
 *
 * - [SecureStoreState.INACCESSIBLE]: the store's directory stripped of every permission — a store this process cannot
 *   look into, which reads Unavailable and refuses writes.
 * - [SecureStoreState.HOLDING_UNDER_A_LOST_KEY]: the seed written through the store, then the test's key deleted from
 *   the Keystore — what a Keystore that lost or invalidated the key leaves behind.
 * - [SecureStoreState.HOLDING_RESTRICTED] cannot arise: every item is written background-readable.
 */
class AndroidSecureStoreContractTest {

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            SecureStoreState.INACCESSIBLE,
            SecureStoreState.EMPTY,
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            SecureStoreState.HOLDING_UNDER_A_LOST_KEY,
        )

        override fun create(state: SecureStoreState, clauseId: String, log: CallLog): Entered<SecureStore> {
            if (state == SecureStoreState.HOLDING_RESTRICTED) {
                return Entered.Unreachable(
                    "every item is written background-readable",
                )
            }
            deleteKey()
            val dir = newTempDirectory()
            val store = AndroidSecureStore(dir, TEST_ALIAS).recorded(log)
            val slot = SecureStoreContract.slot(clauseId)
            when (state) {
                SecureStoreState.INACCESSIBLE -> {
                    check(store.write(slot, "not the seed") == WriteOutcome.Ok)
                    revokeAllAccess(dir)
                }
                SecureStoreState.HOLDING_BACKGROUND_READABLE ->
                    check(store.write(slot, SecureStoreContract.seedValue(clauseId)) == WriteOutcome.Ok)
                SecureStoreState.HOLDING_UNDER_A_LOST_KEY -> {
                    check(store.write(slot, SecureStoreContract.seedValue(clauseId)) == WriteOutcome.Ok)
                    deleteKey()
                }
                SecureStoreState.EMPTY, SecureStoreState.HOLDING_RESTRICTED -> Unit
            }
            return Entered.Ready(store) {
                restoreOwnerAccess(dir)
                dir.deleteRecursively()
                deleteKey()
            }
        }
    }

    private fun deleteKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)

    @Test
    fun `the Keystore-sealed store satisfies the SecureStore contract`() = verify(SecureStoreContract, binding)

    private companion object {
        const val TEST_ALIAS = "app.snapsync.contract.securestore"
    }
}
