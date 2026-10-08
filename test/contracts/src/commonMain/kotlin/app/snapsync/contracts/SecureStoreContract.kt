package app.snapsync.contracts

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The states a [SecureStore] can be found in, as far as a clause cares. A `HOLDING_*` store holds
 * [SecureStoreContract.seedValue] for the clause being run, filed under that protection.
 */
enum class SecureStoreState {
    /** The store cannot be read at all — a device not unlocked since boot, or an unentitled process. */
    INACCESSIBLE,

    /** Readable, and holding nothing at the addressed item. */
    EMPTY,

    /** Holding the seed value, already background-readable. */
    HOLDING_BACKGROUND_READABLE,

    /** Holding the seed value under some other protection than the background-readable one this store writes. */
    HOLDING_RESTRICTED,

    /**
     * Holding the seed value sealed under a key the store no longer has — on Android, a Keystore key deleted or
     * permanently invalidated under the item. Unreachable where the store keeps no key of its own (the Keychain).
     */
    HOLDING_UNDER_A_LOST_KEY,
}

/**
 * What every [SecureStore] promises (`docs/architecture.md` — this list IS the specification of the
 * port's obligations). The three-state read is the point of the seam: "I could not look" is never "there
 * is nothing there", because conflating the two minted a second identity on a locked device and aborted
 * the process (build 297).
 *
 * Every input is deterministic — the seed and every written value derive from the clause id — so a
 * recording taken on a device replays against the same calls in CI.
 */
object SecureStoreContract : Contract<SecureStoreState, SecureStore>("SecureStore") {

    /** The value a `HOLDING_*` state is seeded with, for [clauseId]. Bindings seed exactly this. */
    fun seedValue(clauseId: String) = "seed:$clauseId"

    /**
     * The one slot a clause addresses: derived from the clause id, in the SHARED place the production device id
     * uses — the same Keychain address the recorded device runs asked, so a recording replays against it.
     */
    fun slot(clauseId: String) = SecureSlot(service = "app.snapsync.contract", account = clauseId, shared = true)

    private fun written(clauseId: String) = "written:$clauseId"

    private fun minted(clauseId: String) = "minted:$clauseId"

    override val clauses = clauses {

        clause(
            "INACCESSIBLE_READ_IS_UNAVAILABLE",
            SecureStoreState.INACCESSIBLE,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Unavailable::class)
            },
        ) { store ->
            val read = store.read(slot("INACCESSIBLE_READ_IS_UNAVAILABLE"))
            assertIs<SecureStoreRead.Unavailable>(read, "an unreadable store must say so, never answer Absent")
            assertTrue(read.detail.isNotBlank(), "an unavailable read carries the adapter's diagnostic")
        }

        clause(
            "INACCESSIBLE_WRITE_REFUSES",
            SecureStoreState.INACCESSIBLE,
            covers = cells {
                on<SecureStore>().answers(SecureStore::write).with(WriteOutcome.Failed::class)
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Unavailable::class)
            },
        ) { store ->
            assertIs<WriteOutcome.Failed>(
                store.write(slot("INACCESSIBLE_WRITE_REFUSES"), written("INACCESSIBLE_WRITE_REFUSES")),
                "a refused write answers so — the old store threw",
            )
            assertIs<SecureStoreRead.Unavailable>(
                store.read(slot("INACCESSIBLE_WRITE_REFUSES")),
                "a refused write changes nothing",
            )
        }

        clause(
            "EMPTY_READ_IS_ABSENT",
            SecureStoreState.EMPTY,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            },
        ) { store ->
            assertEquals(SecureStoreRead.Absent, store.read(slot("EMPTY_READ_IS_ABSENT")))
        }

        clause(
            "EMPTY_WRITE_THEN_READ",
            SecureStoreState.EMPTY,
            covers = cells {
                on<SecureStore>().answers(SecureStore::write).with(WriteOutcome.Ok::class)
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Found::class)
            },
        ) { store ->
            val value = written("EMPTY_WRITE_THEN_READ")
            assertEquals(WriteOutcome.Ok, store.write(slot("EMPTY_WRITE_THEN_READ"), value))
            assertEquals(
                SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE),
                store.read(slot("EMPTY_WRITE_THEN_READ")),
            )
        }

        clause(
            "EMPTY_DELETE_IS_A_NOOP",
            SecureStoreState.EMPTY,
            covers = cells {
                on<SecureStore>().answers(SecureStore::delete).with(WriteOutcome.Ok::class)
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            },
        ) { store ->
            assertEquals(
                WriteOutcome.Ok,
                store.delete(slot("EMPTY_DELETE_IS_A_NOOP")),
                "deleting nothing is not an error",
            )
            assertEquals(SecureStoreRead.Absent, store.read(slot("EMPTY_DELETE_IS_A_NOOP")))
        }

        clause(
            "HOLDING_READS_BACK",
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Found::class)
            },
        ) { store ->
            assertEquals(
                SecureStoreRead.Found(seedValue("HOLDING_READS_BACK"), StoredProtection.BACKGROUND_READABLE),
                store.read(slot("HOLDING_READS_BACK")),
            )
        }

        clause(
            "HOLDING_WRITE_REPLACES",
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Found::class)
            },
        ) { store ->
            val value = written("HOLDING_WRITE_REPLACES")
            store.write(slot("HOLDING_WRITE_REPLACES"), value)
            assertEquals(
                SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE),
                store.read(slot("HOLDING_WRITE_REPLACES")),
            )
        }

        clause(
            "HOLDING_DELETE_REMOVES",
            SecureStoreState.HOLDING_BACKGROUND_READABLE,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            },
        ) { store ->
            store.delete(slot("HOLDING_DELETE_REMOVES"))
            assertEquals(SecureStoreRead.Absent, store.read(slot("HOLDING_DELETE_REMOVES")))
        }

        // A value nobody can ever decrypt again is gone, not unreadable: answering Unavailable would leave the device
        // without an identity forever, because nothing mints over an unavailable read.
        clause(
            "LOST_KEY_READS_AS_ABSENT",
            SecureStoreState.HOLDING_UNDER_A_LOST_KEY,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Absent::class)
            },
        ) { store ->
            assertEquals(SecureStoreRead.Absent, store.read(slot("LOST_KEY_READS_AS_ABSENT")))
            assertEquals(SecureStoreRead.Absent, store.read(slot("LOST_KEY_READS_AS_ABSENT")), "and stays absent")
        }

        clause(
            "LOST_KEY_WRITE_THEN_READ",
            SecureStoreState.HOLDING_UNDER_A_LOST_KEY,
            covers = cells {
                on<SecureStore>().answers(SecureStore::write).with(WriteOutcome.Ok::class)
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Found::class)
            },
        ) { store ->
            // What lets a fresh identity be minted over the lost one: the store seals under a fresh key.
            assertEquals(
                WriteOutcome.Ok,
                store.write(slot("LOST_KEY_WRITE_THEN_READ"), minted("LOST_KEY_WRITE_THEN_READ")),
            )
            assertEquals(
                SecureStoreRead.Found(minted("LOST_KEY_WRITE_THEN_READ"), StoredProtection.BACKGROUND_READABLE),
                store.read(slot("LOST_KEY_WRITE_THEN_READ")),
                "the store seals under a fresh key and reads it back",
            )
        }

        clause(
            "RESTRICTED_READS_AS_NOT_BACKGROUND_READABLE",
            SecureStoreState.HOLDING_RESTRICTED,
            covers = cells {
                on<SecureStore>().answers(SecureStore::read).with(SecureStoreRead.Found::class)
            },
        ) { store ->
            val read = assertIs<SecureStoreRead.Found>(store.read(slot("RESTRICTED_READS_AS_NOT_BACKGROUND_READABLE")))
            assertEquals(seedValue("RESTRICTED_READS_AS_NOT_BACKGROUND_READABLE"), read.value)
            assertTrue(
                read.protection != StoredProtection.BACKGROUND_READABLE,
                "an item filed under another protection must say so truthfully, not read as background-readable",
            )
        }
    }
}
