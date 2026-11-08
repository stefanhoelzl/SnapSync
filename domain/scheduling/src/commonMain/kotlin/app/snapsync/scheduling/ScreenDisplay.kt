package app.snapsync.scheduling

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.Ui
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * **The screen shown every state it is handed**, for the life of the process: the one coroutine that hands a status
 * host's states to the platform's [ui]. Showing only hands a state over — the adapter renders it on its own main
 * thread — so it is no entry point's work: no label, no background time, no answer.
 */
class ScreenDisplay<S>(private val runtime: Runtime, private val ui: Ui<S, *>) {
    @CompositionOnly
    fun show(states: Flow<S>) {
        runtime.scope.launch { states.collect(ui::show) }
    }
}
