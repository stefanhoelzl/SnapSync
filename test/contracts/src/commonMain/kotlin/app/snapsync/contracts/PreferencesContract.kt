package app.snapsync.contracts

import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The states the clause's key can be found in. */
enum class PreferencesState {
    /** [PreferencesContract.key] holds nothing. */
    EMPTY,

    /** [PreferencesContract.key] holds [PreferencesContract.seed]. */
    HOLDING,

    /** [PreferencesContract.key] holds a value of a kind this port never writes — a number another writer left. */
    FOREIGN,

    /** [PreferencesContract.key] holds [PreferencesContract.seed], and the store refuses every write. */
    UNWRITABLE,
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

        clause(
            "FOREIGN_GET_IS_UNAVAILABLE_NEVER_ABSENT",
            PreferencesState.FOREIGN,
            covers = cells { on<Preferences>().answers(Preferences::get).with(PrefRead.Unavailable::class) },
        ) { prefs ->
            assertIs<PrefRead.Unavailable>(
                prefs.get(key("FOREIGN_GET_IS_UNAVAILABLE_NEVER_ABSENT")),
                "a value it cannot read is there, so never absent — and never a value it did not write",
            )
        }

        clause(
            "UNWRITABLE_A_REFUSED_WRITE_SAYS_SO",
            PreferencesState.UNWRITABLE,
            covers = cells {
                on<Preferences> {
                    answers(Preferences::set).with(WriteOutcome.Failed::class)
                    answers(Preferences::remove).with(WriteOutcome.Failed::class)
                }
            },
        ) { prefs ->
            val k = key("UNWRITABLE_A_REFUSED_WRITE_SAYS_SO")
            assertIs<WriteOutcome.Failed>(prefs.set(k, "changed"), "a write the store refused is not a write")
            assertIs<WriteOutcome.Failed>(prefs.remove(k), "nor is a removal")
        }

        clause(
            "EMPTY_GET_IS_ABSENT",
            PreferencesState.EMPTY,
            covers = cells { on<Preferences>().answers(Preferences::get).with(PrefRead.Absent::class) },
        ) { prefs ->
            assertEquals(PrefRead.Absent, prefs.get(key("EMPTY_GET_IS_ABSENT")))
        }

        clause(
            "EMPTY_SET_THEN_GET",
            PreferencesState.EMPTY,
            covers = cells {
                on<Preferences> {
                    answers(Preferences::set).with(WriteOutcome.Ok::class)
                    answers(Preferences::get).with(PrefRead.Value::class)
                }
            },
        ) { prefs ->
            val k = key("EMPTY_SET_THEN_GET")
            assertEquals(WriteOutcome.Ok, prefs.set(k, "v"))
            assertEquals(PrefRead.Value("v"), prefs.get(k))
            prefs.remove(k)
        }

        clause(
            "EMPTY_REMOVE_IS_OK",
            PreferencesState.EMPTY,
            covers = cells { on<Preferences>().answers(Preferences::remove).with(WriteOutcome.Ok::class) },
        ) { prefs ->
            assertEquals(WriteOutcome.Ok, prefs.remove(key("EMPTY_REMOVE_IS_OK")))
        }

        clause(
            "HOLDING_GET_IS_THE_VALUE",
            PreferencesState.HOLDING,
            covers = cells { on<Preferences>().answers(Preferences::get).with(PrefRead.Value::class) },
        ) { prefs ->
            assertEquals(PrefRead.Value(seed("HOLDING_GET_IS_THE_VALUE")), prefs.get(key("HOLDING_GET_IS_THE_VALUE")))
        }

        clause(
            "HOLDING_SET_REPLACES",
            PreferencesState.HOLDING,
            covers = cells { on<Preferences>().answers(Preferences::get).with(PrefRead.Value::class) },
        ) { prefs ->
            val k = key("HOLDING_SET_REPLACES")
            prefs.set(k, "replaced")
            assertEquals(PrefRead.Value("replaced"), prefs.get(k))
        }

        clause(
            "HOLDING_REMOVE_MAKES_IT_ABSENT",
            PreferencesState.HOLDING,
            covers = cells {
                on<Preferences> {
                    answers(Preferences::remove).with(WriteOutcome.Ok::class)
                    answers(Preferences::get).with(PrefRead.Absent::class)
                }
            },
        ) { prefs ->
            val k = key("HOLDING_REMOVE_MAKES_IT_ABSENT")
            assertEquals(WriteOutcome.Ok, prefs.remove(k))
            assertEquals(PrefRead.Absent, prefs.get(k))
        }
    }
}
