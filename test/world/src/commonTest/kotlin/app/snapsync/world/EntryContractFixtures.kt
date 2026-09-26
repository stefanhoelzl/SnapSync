package app.snapsync.world

import app.snapsync.compose.extensionEntries
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionEntriesObservations
import app.snapsync.contracts.ExtensionEntriesState
import app.snapsync.contracts.ExtensionEntriesSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The world side of the inbound-port contracts' bindings (`docs/architecture.md`): enter a named state over a
 * fresh [World], and adapt that world to the contract's observation handle. The bindings themselves live in the
 * platform test source sets, because a binding states its host as a literal; both call this, so the JVM and the
 * simulator enter every state the same way.
 *
 * The world runs on its own real-time scope rather than the clause's virtual clock — its mini-edge serves on a real
 * dispatcher (see `worldTest`) — and the binding's disposal cancels it.
 */
internal object EntryContractFixtures {

    // Canonical UUIDs: the link codec refuses anything else, exactly as the edge does.
    private const val JOINED_EVENT = "22222222-2222-4222-8222-222222222222"

    suspend fun enter(state: ExtensionEntriesState): Entered<ExtensionEntriesSubject> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val w = World(scope)
        when (state) {
            ExtensionEntriesState.UNJOINED -> Unit
            ExtensionEntriesState.JOINED_WITH_A_NEW_PHOTO -> {
                w.provision(JOINED_EVENT)
                w.addOwnAsset("A")
            }
            ExtensionEntriesState.JOINED_WITH_NOTHING_NEW -> w.provision(JOINED_EVENT)
        }
        val entries = extensionEntries(ports = { w.uploadPorts }, cycle = { w.cycle })
        val observe = object : ExtensionEntriesObservations {
            override fun uploadsStarted() = w.platform.created.size
        }
        return Entered.Ready(ExtensionEntriesSubject(entries, observe)) { scope.cancel() }
    }
}
