package app.snapsync.contracts

import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import kotlin.test.assertEquals

/** The states the clause's key can be found in. */
enum class PreferencesState {
    /** [PreferencesContract.key] holds nothing. */
    EMPTY,

    /** [PreferencesContract.key] holds [PreferencesContract.seed]. */
    HOLDING,
}

/**
 * What `Preferences` promises (`docs/architecture.md`; the port's KDoc carries why): a written value reads back,
 * a missing key reads as absent — never as an empty string — and removing is idempotent. Keys derive from the
 * clause id, so clauses sharing one real suite never see each other's values.
 */
object PreferencesContract : Contract<PreferencesState, Preferences>("Preferences") {

    fun key(clauseId: String) = "app.snapsync.contract.$clauseId"

    fun seed(clauseId: String) = "value of $clauseId"

    override val clauses = clauses {

        clause("EMPTY_GET_IS_ABSENT", PreferencesState.EMPTY) { prefs ->
            assertEquals(PrefRead.Absent, prefs.get(key("EMPTY_GET_IS_ABSENT")))
        }

        clause("EMPTY_SET_THEN_GET", PreferencesState.EMPTY) { prefs ->
            val k = key("EMPTY_SET_THEN_GET")
            assertEquals(WriteOutcome.Ok, prefs.set(k, "v"))
            assertEquals(PrefRead.Value("v"), prefs.get(k))
            prefs.remove(k)
        }

        clause("EMPTY_REMOVE_IS_OK", PreferencesState.EMPTY) { prefs ->
            assertEquals(WriteOutcome.Ok, prefs.remove(key("EMPTY_REMOVE_IS_OK")))
        }

        clause("HOLDING_GET_IS_THE_VALUE", PreferencesState.HOLDING) { prefs ->
            assertEquals(PrefRead.Value(seed("HOLDING_GET_IS_THE_VALUE")), prefs.get(key("HOLDING_GET_IS_THE_VALUE")))
        }

        clause("HOLDING_SET_REPLACES", PreferencesState.HOLDING) { prefs ->
            val k = key("HOLDING_SET_REPLACES")
            prefs.set(k, "replaced")
            assertEquals(PrefRead.Value("replaced"), prefs.get(k))
        }

        clause("HOLDING_REMOVE_MAKES_IT_ABSENT", PreferencesState.HOLDING) { prefs ->
            val k = key("HOLDING_REMOVE_MAKES_IT_ABSENT")
            assertEquals(WriteOutcome.Ok, prefs.remove(k))
            assertEquals(PrefRead.Absent, prefs.get(k))
        }
    }
}
