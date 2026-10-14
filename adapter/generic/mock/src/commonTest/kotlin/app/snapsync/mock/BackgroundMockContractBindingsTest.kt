@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.mock

import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionRegistryContract
import app.snapsync.contracts.ExtensionRegistryState
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeOs
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Running
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The operating system's background execution, as mocks, held to the contracts their real adapters pass
 * (`docs/testing.md`, "Mocks"). Where the real system acts on its own — delivering a due wake, ending a hold whose time
 * is up — the binding plays it through the mock's operator face, watching the mock's own table as the system watches
 * its.
 */
class BackgroundMockContractBindingsTest {

    private val wake = object : Binding<WakeState, ScheduledWakes> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = WakeMock.Face::class
        override val reaches =
            setOf(
                WakeState.EMPTY,
                WakeState.DELIVERING,
                WakeState.DELIVERING_LIBRARY_CHANGES,
                WakeState.NO_LIBRARY_WAKE,
                WakeState.REFUSING,
            )

        override fun create(state: WakeState, clauseId: String, log: CallLog): Entered<ScheduledWakes> {
            val mock = when (state) {
                WakeState.DELIVERING, WakeState.DELIVERING_LIBRARY_CHANGES ->
                    WakeMock(supported = setOf(WakeId.Heartbeat, WakeId.LibraryChanged))
                WakeState.REFUSING -> WakeMock(refusal = "this process may schedule no task")
                else -> WakeMock()
            }
            val port = mock.port().recorded(log)
            val pending = { mock.pending.value.count { (id, _) -> id == WakeId.Heartbeat } }
            val delivering = state == WakeState.DELIVERING || state == WakeState.DELIVERING_LIBRARY_CHANGES
            if (!delivering) return Entered.Ready(ScheduledWakes(port) { pending() })
            // The operating system: a wake due now is delivered once it is asked for; a library wake when the library changes.
            // What each delivered wake started, as the operating system holds it: what its expiry expires.
            val running = AtomicReference<List<Running>>(emptyList())
            val system = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            system.launch {
                mock.pending.collect { queue ->
                    queue.filter { (_, trigger) -> trigger is WakeTrigger.After && trigger.earliest == Duration.ZERO }
                        .forEach { (id, _) ->
                            val run = mock.fire(id, Completion.None)
                            running.update { it + run }
                        }
                }
            }
            val os = WakeOs(
                changeLibrary = {
                    mock.pending.first { WakeId.LibraryChanged in it }
                    mock.fire(WakeId.LibraryChanged, Completion.None)
                },
                endRunningWake = { running.load().forEach { it.expire() } },
            )
            return Entered.Ready(ScheduledWakes(port, os) { pending() }) { system.cancel() }
        }
    }

    private val backgroundTime = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryBackgroundTime::class
        override val reaches = setOf(BackgroundTimeState.TIME_REMAINS, BackgroundTimeState.TIME_RUNS_OUT)

        override fun create(state: BackgroundTimeState, clauseId: String, log: CallLog): Entered<BackgroundTime> {
            val mock = BackgroundTimeMock()
            if (state == BackgroundTimeState.TIME_REMAINS) return Entered.Ready(mock.port().recorded(log))
            // The operating system: every hold's time runs out once it is begun.
            val system = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            system.launch { mock.held.collect { held -> held.forEach { it.expire() } } }
            return Entered.Ready(mock.port().recorded(log)) { system.cancel() }
        }
    }

    private val registry = object : Binding<ExtensionRegistryState, ExtensionRegistry> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryExtensionRegistry::class
        override val reaches = setOf(
            ExtensionRegistryState.RECORD_ABSENT,
            ExtensionRegistryState.RECORD_PRESENT,
            ExtensionRegistryState.UNDER_PARTIAL_GRANT,
            ExtensionRegistryState.NO_MECHANISM,
        )

        override fun create(state: ExtensionRegistryState, clauseId: String, log: CallLog): Entered<ExtensionRegistry> {
            val mock = when (state) {
                ExtensionRegistryState.RECORD_ABSENT -> ExtensionRegistryMock(supported = true)
                ExtensionRegistryState.RECORD_PRESENT -> ExtensionRegistryMock(supported = true, registered = true)
                ExtensionRegistryState.UNDER_PARTIAL_GRANT -> ExtensionRegistryMock(
                    supported = true,
                    partialGrant = true,
                )
                ExtensionRegistryState.NO_MECHANISM -> ExtensionRegistryMock(supported = false)
            }
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    @Test
    fun `the wake mock satisfies the Wake contract`() = verify(WakeContract, wake)

    @Test
    fun `the background-time mock satisfies the BackgroundTime contract`() = verify(
        BackgroundTimeContract,
        backgroundTime,
    )

    @Test
    fun `the registry mock satisfies the ExtensionRegistry contract`() = verify(ExtensionRegistryContract, registry)
}
