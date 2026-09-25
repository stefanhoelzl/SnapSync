package app.snapsync.preferences

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PreferencesContract
import app.snapsync.contracts.PreferencesState
import app.snapsync.contracts.verify
import app.snapsync.ports.Preferences
import platform.Foundation.NSUserDefaults
import kotlin.test.Test

/** [PreferencesContract] against the real [IosPreferences], over a `UserDefaults` suite of each clause's own. */
class IosPreferencesContractTest {

    private val binding = object : Binding<PreferencesState, Preferences> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(PreferencesState.EMPTY, PreferencesState.HOLDING)

        override fun create(state: PreferencesState, clauseId: String): Entered<Preferences> {
            val suite = "contract.preferences.$clauseId"
            NSUserDefaults(suiteName = suite).removePersistentDomainForName(suite)
            val prefs = IosPreferences(suite)
            if (state == PreferencesState.HOLDING) {
                prefs.set(PreferencesContract.key(clauseId), PreferencesContract.seed(clauseId))
            }
            return Entered.Ready(prefs) { NSUserDefaults(suiteName = suite).removePersistentDomainForName(suite) }
        }
    }

    @Test
    fun `the App-Group suite satisfies the Preferences contract`() = verify(PreferencesContract, binding)
}
