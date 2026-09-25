package app.snapsync.contracts

import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreRead
import app.snapsync.ports.SecureStoreUnavailable
import app.snapsync.model.StoredProtection
import app.snapsync.ports.resolveOrMint
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /** Holding the seed value under some other protection — a legacy item awaiting the in-place upgrade. */
    HOLDING_RESTRICTED,
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

    private fun written(clauseId: String) = "written:$clauseId"

    private fun minted(clauseId: String) = "minted:$clauseId"

    override val clauses = clauses {

        clause("INACCESSIBLE_READ_IS_UNAVAILABLE", SecureStoreState.INACCESSIBLE) { store ->
            val read = store.read()
            assertIs<SecureStoreRead.Unavailable>(read, "an unreadable store must say so, never answer Absent")
            assertTrue(read.detail.isNotBlank(), "an unavailable read carries the adapter's diagnostic")
        }

        clause("INACCESSIBLE_WRITE_REFUSES", SecureStoreState.INACCESSIBLE) { store ->
            assertFailsWith<SecureStoreUnavailable> { store.write(written("INACCESSIBLE_WRITE_REFUSES")) }
            assertIs<SecureStoreRead.Unavailable>(store.read(), "a refused write changes nothing")
        }

        clause("INACCESSIBLE_RESOLVE_NEVER_MINTS", SecureStoreState.INACCESSIBLE) { store ->
            var generated = false
            assertFailsWith<SecureStoreUnavailable> {
                resolveOrMint(store) {
                    generated = true
                    minted("INACCESSIBLE_RESOLVE_NEVER_MINTS")
                }
            }
            assertFalse(generated, "minting on an unreadable store is the build-297 identity split")
        }

        clause("EMPTY_READ_IS_ABSENT", SecureStoreState.EMPTY) { store ->
            assertEquals(SecureStoreRead.Absent, store.read())
        }

        clause("EMPTY_WRITE_THEN_READ", SecureStoreState.EMPTY) { store ->
            val value = written("EMPTY_WRITE_THEN_READ")
            store.write(value)
            assertEquals(SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE), store.read())
        }

        clause("EMPTY_DELETE_IS_A_NOOP", SecureStoreState.EMPTY) { store ->
            store.delete()
            assertEquals(SecureStoreRead.Absent, store.read())
        }

        clause("EMPTY_RESOLVE_MINTS_EXACTLY_ONCE", SecureStoreState.EMPTY) { store ->
            var generations = 0
            val mint = {
                generations++
                minted("EMPTY_RESOLVE_MINTS_EXACTLY_ONCE")
            }
            val first = resolveOrMint(store, generate = mint)
            val second = resolveOrMint(store, generate = mint)
            assertEquals(1, generations, "the second resolve reads the minted value back")
            assertEquals(first, second)
            assertEquals(SecureStoreRead.Found(first, StoredProtection.BACKGROUND_READABLE), store.read())
        }

        clause("HOLDING_READS_BACK", SecureStoreState.HOLDING_BACKGROUND_READABLE) { store ->
            assertEquals(
                SecureStoreRead.Found(seedValue("HOLDING_READS_BACK"), StoredProtection.BACKGROUND_READABLE),
                store.read(),
            )
        }

        clause("HOLDING_WRITE_REPLACES", SecureStoreState.HOLDING_BACKGROUND_READABLE) { store ->
            val value = written("HOLDING_WRITE_REPLACES")
            store.write(value)
            assertEquals(SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE), store.read())
        }

        clause("HOLDING_DELETE_REMOVES", SecureStoreState.HOLDING_BACKGROUND_READABLE) { store ->
            store.delete()
            assertEquals(SecureStoreRead.Absent, store.read())
        }

        clause("RESTRICTED_READS_AS_NOT_BACKGROUND_READABLE", SecureStoreState.HOLDING_RESTRICTED) { store ->
            val read = assertIs<SecureStoreRead.Found>(store.read())
            assertEquals(seedValue("RESTRICTED_READS_AS_NOT_BACKGROUND_READABLE"), read.value)
            assertTrue(
                read.protection != StoredProtection.BACKGROUND_READABLE,
                "a legacy item must report that it needs the upgrade, not hide it",
            )
        }

        clause("RESTRICTED_MIGRATE_PRESERVES_VALUE", SecureStoreState.HOLDING_RESTRICTED) { store ->
            store.migrateProtection()
            assertEquals(
                SecureStoreRead.Found(
                    seedValue("RESTRICTED_MIGRATE_PRESERVES_VALUE"),
                    StoredProtection.BACKGROUND_READABLE,
                ),
                store.read(),
                "the upgrade changes the protection and nothing else — a changed id orphans the device",
            )
        }

        clause("RESTRICTED_RESOLVE_UPGRADES_IN_PLACE", SecureStoreState.HOLDING_RESTRICTED) { store ->
            val resolved = resolveOrMint(store) { error("a held value must never be re-minted") }
            assertEquals(seedValue("RESTRICTED_RESOLVE_UPGRADES_IN_PLACE"), resolved)
            assertEquals(
                SecureStoreRead.Found(resolved, StoredProtection.BACKGROUND_READABLE),
                store.read(),
            )
        }
    }
}
