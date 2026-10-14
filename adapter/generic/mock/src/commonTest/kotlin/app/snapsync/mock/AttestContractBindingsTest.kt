package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DeviceConditionsContract
import app.snapsync.contracts.DeviceConditionsState
import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.NetworkMonitorContract
import app.snapsync.contracts.NetworkState
import app.snapsync.contracts.ProcessInfoContract
import app.snapsync.contracts.ProcessInfoState
import app.snapsync.contracts.ThreadLabelContract
import app.snapsync.contracts.ThreadLabelState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.Availability
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.ProcessInfo
import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The integrity double, the process doubles (its facts and its log label), the network double and the device-conditions double, held to the contracts their real implementations
 * satisfy (`docs/architecture.md`). Each double is built at its DEFAULTS for the state, never with a knob
 * turned to make a clause pass: a double the contract catches lying is fixed in the double.
 */
class AttestContractBindingsTest {

    private val integrity = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryDeviceIntegrity::class
        override val reaches = setOf(
            DeviceIntegrityState.UNAVAILABLE,
            DeviceIntegrityState.AVAILABLE,
            DeviceIntegrityState.UNUSABLE_KEY,
            DeviceIntegrityState.VENDOR_UNREACHABLE,
        )

        override fun create(state: DeviceIntegrityState, clauseId: String, log: CallLog): Entered<DeviceIntegrity> =
            Entered.Ready(
                inMemoryDeviceIntegrity(
                    available = state != DeviceIntegrityState.UNAVAILABLE,
                    unusable = setOf(DeviceIntegrityContract.unusableHandle(clauseId)),
                    vendorReachable = state != DeviceIntegrityState.VENDOR_UNREACHABLE,
                ).recorded(log),
            )
    }

    @Test
    fun `the in-memory integrity satisfies the DeviceIntegrity contract`() = verify(DeviceIntegrityContract, integrity)

    private val processInfo = object : Binding<ProcessInfoState, ProcessInfo> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryProcessInfo::class
        override val reaches = setOf(
            ProcessInfoState.UNLOCKED,
            ProcessInfoState.MEMORY_ACCOUNTED,
            ProcessInfoState.MEMORY_NOT_ACCOUNTED,
            ProcessInfoState.LOCKED,
        )

        override fun create(state: ProcessInfoState, clauseId: String, log: CallLog): Entered<ProcessInfo> {
            val mock = ProcessInfoMock(
                if (state == ProcessInfoState.LOCKED) Availability.UNAVAILABLE else Availability.AVAILABLE,
            )
            if (state == ProcessInfoState.MEMORY_NOT_ACCOUNTED) mock.memory = null
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    @Test
    fun `the in-memory process info satisfies the ProcessInfo contract`() =
        verify(ProcessInfoContract, processInfo)

    private val threadLabel = object : Binding<ThreadLabelState, ThreadLabel> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = ThreadLabelMock::class
        override val reaches = setOf(ThreadLabelState.EMPTY)

        override fun create(state: ThreadLabelState, clauseId: String, log: CallLog): Entered<ThreadLabel> =
            Entered.Ready(ThreadLabelMock().recorded(log))
    }

    @Test
    fun `the label mock satisfies the ThreadLabel contract`() = verify(ThreadLabelContract, threadLabel)

    private val network = object : Binding<NetworkState, NetworkMonitor> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryNetworkMonitor::class
        override val reaches =
            setOf(NetworkState.ONLINE, NetworkState.RESTRICTED, NetworkState.OFFLINE, NetworkState.BLOCKED)

        override fun create(state: NetworkState, clauseId: String, log: CallLog): Entered<NetworkMonitor> =
            Entered.Ready(inMemoryNetworkMonitor(MutableStateFlow(accessIn(state))).recorded(log))

        private fun accessIn(state: NetworkState): NetworkAccess = when (state) {
            NetworkState.ONLINE -> NetworkAccess.Online(restricted = false)
            NetworkState.RESTRICTED -> NetworkAccess.Online(restricted = true)
            NetworkState.OFFLINE -> NetworkAccess.Offline
            NetworkState.BLOCKED -> NetworkAccess.Blocked
        }
    }

    @Test
    fun `the in-memory network satisfies the NetworkMonitor contract`() =
        verify(NetworkMonitorContract, network)

    private val deviceConditions = object : Binding<DeviceConditionsState, DeviceConditions> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = InMemoryDeviceConditions::class

        // At its defaults the double is an iPhone; Android's facts would need a knob turned, which a binding may not.
        override val reaches = setOf(DeviceConditionsState.IPHONE)

        override fun create(state: DeviceConditionsState, clauseId: String, log: CallLog): Entered<DeviceConditions> = when (state) {
            DeviceConditionsState.IPHONE -> Entered.Ready(DeviceConditionsMock().port().recorded(log))
            DeviceConditionsState.ANDROID -> Entered.Unreachable("the double opens as an iPhone")
        }
    }

    @Test
    fun `the in-memory device conditions satisfy the DeviceConditions contract`() =
        verify(DeviceConditionsContract, deviceConditions)
}
