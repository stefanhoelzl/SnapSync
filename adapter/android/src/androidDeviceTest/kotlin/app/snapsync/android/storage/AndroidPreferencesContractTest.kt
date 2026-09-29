package app.snapsync.android.storage

import android.content.Context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PreferencesContract
import app.snapsync.contracts.PreferencesState
import app.snapsync.contracts.verify
import app.snapsync.ports.Preferences
import kotlin.test.Test

/** [PreferencesContract] against the real [AndroidPreferences], over a `SharedPreferences` file of each clause's own. */
class AndroidPreferencesContractTest {

    private val binding = object : Binding<PreferencesState, Preferences> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(PreferencesState.EMPTY, PreferencesState.HOLDING)

        override fun create(state: PreferencesState, clauseId: String): Entered<Preferences> {
            val file = "contract.$clauseId"
            val shared = context.getSharedPreferences(file, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
            val prefs = AndroidPreferences(shared)
            if (state == PreferencesState.HOLDING) {
                prefs.set(PreferencesContract.key(clauseId), PreferencesContract.seed(clauseId))
            }
            return Entered.Ready(prefs) { context.deleteSharedPreferences(file) }
        }
    }

    @Test
    fun `SharedPreferences satisfies the Preferences contract`() = verify(PreferencesContract, binding)
}
