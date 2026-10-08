package app.snapsync.android.storage

import android.content.Context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PreferencesContract
import app.snapsync.contracts.PreferencesState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.Preferences
import java.io.File
import kotlin.test.Test

/** [PreferencesContract] against the real [AndroidPreferences], over a `SharedPreferences` file of each clause's own. */
class AndroidPreferencesContractTest {

    private val binding = object : Binding<PreferencesState, Preferences> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            PreferencesState.EMPTY,
            PreferencesState.HOLDING,
            PreferencesState.FOREIGN,
            PreferencesState.UNWRITABLE,
        )

        override fun create(state: PreferencesState, clauseId: String, log: CallLog): Entered<Preferences> {
            val file = "contract.$clauseId"
            val shared = context.getSharedPreferences(file, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
            val bare = AndroidPreferences(shared)
            val key = PreferencesContract.key(clauseId)
            when (state) {
                PreferencesState.HOLDING, PreferencesState.UNWRITABLE -> bare.set(
                    key,
                    PreferencesContract.seed(clauseId),
                )
                PreferencesState.FOREIGN -> shared.edit().putInt(key, FOREIGN_NUMBER).commit()
                PreferencesState.EMPTY -> Unit
            }
            // A directory this process may read but not write: `commit()` cannot replace the file, and answers false.
            val directory = File(context.dataDir, "shared_prefs")
            if (state == PreferencesState.UNWRITABLE) directory.setWritable(false, false)
            return Entered.Ready(bare.recorded(log)) {
                directory.setWritable(true, true)
                context.deleteSharedPreferences(file)
            }
        }
    }

    @Test
    fun `SharedPreferences satisfies the Preferences contract`() = verify(PreferencesContract, binding)

    private companion object {
        const val FOREIGN_NUMBER = 7
    }
}
