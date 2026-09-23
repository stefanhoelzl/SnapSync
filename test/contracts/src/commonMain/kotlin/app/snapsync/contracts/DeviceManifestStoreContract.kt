package app.snapsync.contracts

import app.snapsync.ports.DeviceManifestStore
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The states a [DeviceManifestStore] can be found in, as far as a clause cares. */
enum class DeviceManifestStoreState {
    /** No storage can be reached — on iOS, a process without the App-Group container. */
    UNAVAILABLE,

    /** Readable, and holding no record: nothing uploaded yet, or the belief was cleared. */
    EMPTY,

    /** Holding [DeviceManifestStoreContract.seedJson] for the clause being run. */
    HOLDING,
}

/**
 * What the last-uploaded manifest record promises (capability `port-contracts`; the port's own KDoc
 * carries why). It is a skip-if-unchanged **cache**: `null` means "rewrite the manifest", which costs one
 * idempotent PUT, so an unreachable store degrades to `null` and to writes that go nowhere — it never
 * raises into the upload cycle. The dangerous direction is a STALE non-null, which suppressed the rewrite
 * forever, so a clear must actually make the next load `null`.
 */
object DeviceManifestStoreContract : Contract<DeviceManifestStoreState, DeviceManifestStore>("DeviceManifestStore") {

    /** The record a [DeviceManifestStoreState.HOLDING] store holds for [clauseId]. Bindings seed exactly this. */
    fun seedJson(clauseId: String) = """{"seed":"$clauseId"}"""

    private fun written(clauseId: String) = """{"written":"$clauseId"}"""

    override val clauses = clauses {

        clause("UNAVAILABLE_LOAD_IS_NULL", DeviceManifestStoreState.UNAVAILABLE) { store ->
            assertNull(store.loadLastUploaded())
        }

        clause("UNAVAILABLE_WRITES_DEGRADE_WITHOUT_RAISING", DeviceManifestStoreState.UNAVAILABLE) { store ->
            store.saveLastUploaded(written("UNAVAILABLE_WRITES_DEGRADE_WITHOUT_RAISING"))
            store.clearLastUploaded()
            assertNull(store.loadLastUploaded(), "a cache that cannot hold anything must never claim to")
        }

        clause("EMPTY_LOAD_IS_NULL", DeviceManifestStoreState.EMPTY) { store ->
            assertNull(store.loadLastUploaded())
        }

        clause("EMPTY_SAVE_THEN_LOAD", DeviceManifestStoreState.EMPTY) { store ->
            val json = written("EMPTY_SAVE_THEN_LOAD")
            store.saveLastUploaded(json)
            assertEquals(json, store.loadLastUploaded())
        }

        clause("EMPTY_CLEAR_IS_A_NOOP", DeviceManifestStoreState.EMPTY) { store ->
            store.clearLastUploaded()
            assertNull(store.loadLastUploaded())
        }

        clause("HOLDING_LOADS_THE_RECORD", DeviceManifestStoreState.HOLDING) { store ->
            assertEquals(seedJson("HOLDING_LOADS_THE_RECORD"), store.loadLastUploaded())
        }

        clause("HOLDING_SAVE_REPLACES", DeviceManifestStoreState.HOLDING) { store ->
            val json = written("HOLDING_SAVE_REPLACES")
            store.saveLastUploaded(json)
            assertEquals(json, store.loadLastUploaded())
        }

        clause("HOLDING_CLEAR_DROPS_THE_BELIEF", DeviceManifestStoreState.HOLDING) { store ->
            store.clearLastUploaded()
            assertNull(store.loadLastUploaded(), "a cleared belief that still loads suppresses the rewrite forever")
        }
    }
}
