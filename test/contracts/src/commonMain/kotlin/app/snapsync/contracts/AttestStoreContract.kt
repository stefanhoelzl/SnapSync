package app.snapsync.contracts

import app.snapsync.ports.AttestStore
import app.snapsync.model.SecureStoreUnavailable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The states an [AttestStore] can be found in. A `HOLDING` store holds [AttestStoreContract.seedToken] and
 * [AttestStoreContract.seedKeyId] for the clause being run.
 */
enum class AttestStoreState {
    /** The store cannot be read at all — a device not unlocked since boot, or an unentitled process. */
    INACCESSIBLE,

    /** Readable; this install has never attested. */
    EMPTY,

    /** Readable, holding a token and the `keyId` it was attested with. */
    HOLDING,
}

/**
 * What the store of the device token and its `keyId` promises (`docs/architecture.md` — this list IS
 * the port's specification).
 *
 * Two promises carry the weight. An unreadable store is **never** "not attested yet": minting on a forged
 * absence burns a fresh Secure-Enclave attestation, which Apple throttles. And dropping a rejected token
 * **keeps the `keyId`**, so the next refresh renews with a cheap assertion instead of re-attesting.
 */
object AttestStoreContract : Contract<AttestStoreState, AttestStore>("AttestStore") {

    fun seedToken(clauseId: String) = "token:$clauseId"

    fun seedKeyId(clauseId: String) = "key:$clauseId"

    override val clauses = clauses {

        clause("AN_UNREADABLE_TOKEN_IS_NOT_ABSENCE", AttestStoreState.INACCESSIBLE) { store ->
            assertFailsWith<SecureStoreUnavailable> { store.token() }
        }

        clause("AN_UNREADABLE_KEY_ID_IS_NOT_ABSENCE", AttestStoreState.INACCESSIBLE) { store ->
            assertFailsWith<SecureStoreUnavailable> { store.keyId() }
        }

        clause("AN_EMPTY_STORE_HOLDS_NEITHER", AttestStoreState.EMPTY) { store ->
            assertNull(store.token())
            assertNull(store.keyId())
        }

        clause("A_WRITTEN_CREDENTIAL_READS_BACK", AttestStoreState.EMPTY) { store ->
            val id = "A_WRITTEN_CREDENTIAL_READS_BACK"
            store.setKeyId(seedKeyId(id))
            store.setToken(seedToken(id))
            assertEquals(seedToken(id), store.token())
            assertEquals(seedKeyId(id), store.keyId())
        }

        clause("A_HELD_CREDENTIAL_READS_BACK", AttestStoreState.HOLDING) { store ->
            val id = "A_HELD_CREDENTIAL_READS_BACK"
            assertEquals(seedToken(id), store.token())
            assertEquals(seedKeyId(id), store.keyId())
        }

        clause("CLEARING_THE_TOKEN_KEEPS_THE_KEY_ID", AttestStoreState.HOLDING) { store ->
            store.clearToken()
            assertNull(store.token(), "a rejected token is gone")
            assertEquals(seedKeyId("CLEARING_THE_TOKEN_KEEPS_THE_KEY_ID"), store.keyId(), "the key renews it")
        }

        clause("A_NEW_TOKEN_REPLACES_THE_HELD_ONE", AttestStoreState.HOLDING) { store ->
            store.setToken("renewed:A_NEW_TOKEN_REPLACES_THE_HELD_ONE")
            assertEquals("renewed:A_NEW_TOKEN_REPLACES_THE_HELD_ONE", store.token())
        }
    }
}
