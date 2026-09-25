package app.snapsync.contracts

import app.snapsync.ports.PushRegistrationRecord
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The states a [PushRegistrationRecord] can be found in, as far as a clause cares. */
enum class PushRegistrationRecordState {
    /** No storage can be reached — on iOS, a process without the App-Group container. */
    UNAVAILABLE,

    /** Readable, and holding no record: nothing registered yet. */
    EMPTY,

    /** Holding [PushRegistrationRecordContract.seed] for the clause being run. */
    HOLDING,
}

/**
 * What the last-registered push record promises (`docs/architecture.md`; the port's own KDoc carries why). A
 * `null` load publishes, which costs one idempotent `PUT`, so an unreachable store degrades to `null` and to writes
 * that go nowhere — it never raises into the registration. A save must be what the next load answers, or an
 * unchanged token would be re-published at every app entry, the cost the record exists to remove.
 */
object PushRegistrationRecordContract :
    Contract<PushRegistrationRecordState, PushRegistrationRecord>("PushRegistrationRecord") {

    /** The record a [PushRegistrationRecordState.HOLDING] store holds for [clauseId]. Bindings seed exactly this. */
    fun seed(clauseId: String) = "sandbox\nseed-device\n$clauseId"

    private fun written(clauseId: String) = "production\nwritten-device\n$clauseId"

    override val clauses = clauses {

        clause("UNAVAILABLE_LOAD_IS_NULL", PushRegistrationRecordState.UNAVAILABLE) { record ->
            assertNull(record.loadLastRegistered())
        }

        clause("UNAVAILABLE_WRITES_DEGRADE_WITHOUT_RAISING", PushRegistrationRecordState.UNAVAILABLE) { record ->
            record.saveLastRegistered(written("UNAVAILABLE_WRITES_DEGRADE_WITHOUT_RAISING"))
            assertNull(record.loadLastRegistered(), "a record that cannot hold anything must never claim to")
        }

        clause("EMPTY_LOAD_IS_NULL", PushRegistrationRecordState.EMPTY) { record ->
            assertNull(record.loadLastRegistered())
        }

        clause("EMPTY_SAVE_THEN_LOAD", PushRegistrationRecordState.EMPTY) { record ->
            val value = written("EMPTY_SAVE_THEN_LOAD")
            record.saveLastRegistered(value)
            assertEquals(value, record.loadLastRegistered(), "a multi-line value comes back byte for byte")
        }

        clause("HOLDING_LOADS_THE_RECORD", PushRegistrationRecordState.HOLDING) { record ->
            assertEquals(seed("HOLDING_LOADS_THE_RECORD"), record.loadLastRegistered())
        }

        clause("HOLDING_SAVE_REPLACES", PushRegistrationRecordState.HOLDING) { record ->
            val value = written("HOLDING_SAVE_REPLACES")
            record.saveLastRegistered(value)
            assertEquals(value, record.loadLastRegistered())
        }
    }
}
