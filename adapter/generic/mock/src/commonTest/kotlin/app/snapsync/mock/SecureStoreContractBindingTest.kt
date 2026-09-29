package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import kotlin.test.Test

/**
 * The honest [SecureStore], held to the contract every real store satisfies. It reaches every state but a lost key: it
 * seals nothing under one.
 */
class SecureStoreContractBindingTest {

    private val binding = object : Binding<SecureStoreState, SecureStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            SecureStoreState.INACCESSIBLE,
            SecureStoreState.EMPTY,
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            SecureStoreState.HOLDING_RESTRICTED,
        )

        override fun create(state: SecureStoreState, clauseId: String): Entered<SecureStore> {
            val slot = SecureStoreContract.slot(clauseId)
            val seed = SecureStoreContract.seedValue(clauseId)
            if (state == SecureStoreState.HOLDING_UNDER_A_LOST_KEY) return Entered.Unreachable("the in-memory store seals nothing under a key")
            return Entered.Ready(
                when (state) {
                    SecureStoreState.INACCESSIBLE -> inMemorySecureStore(unavailable = true)
                    SecureStoreState.EMPTY -> inMemorySecureStore()
                    SecureStoreState.HOLDING_BACKGROUND_READABLE ->
                        inMemorySecureStore(mutableMapOf(slot to SecureStoreRead.Found(seed, StoredProtection.BACKGROUND_READABLE)))
                    SecureStoreState.HOLDING_RESTRICTED ->
                        inMemorySecureStore(mutableMapOf(slot to SecureStoreRead.Found(seed, StoredProtection.RESTRICTED)))
                    SecureStoreState.HOLDING_UNDER_A_LOST_KEY -> error("answered above")
                },
            )
        }
    }

    @Test
    fun `the in-memory store satisfies the SecureStore contract`() = verify(SecureStoreContract, binding)
}
