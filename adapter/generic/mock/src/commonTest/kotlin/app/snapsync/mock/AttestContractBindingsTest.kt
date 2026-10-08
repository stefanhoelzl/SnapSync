package app.snapsync.mock

import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
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
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.Availability
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.AttestStore
import app.snapsync.ports.DeviceConditions
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.NetworkMonitor
import app.snapsync.ports.ProcessInfo
import app.snapsync.services.trust.CachedAttestStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The integrity and attest-store doubles, the protected-storage double, the network double and the device-conditions double, held to the contracts their real implementations
 * satisfy (`docs/architecture.md`). Each double is built at its DEFAULTS for the state, never with a knob
 * turned to make a clause pass: a double the contract catches lying is fixed in the double.
 */
class AttestContractBindingsTest {

    private val integrity = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceIntegrityState.UNAVAILABLE, DeviceIntegrityState.AVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String, log: CallLog): Entered<DeviceIntegrity> =
            Entered.Ready(inMemoryDeviceIntegrity(available = state == DeviceIntegrityState.AVAILABLE).recorded(log))
    }

    @Test
    fun `the in-memory integrity satisfies the DeviceIntegrity contract`() = verify(DeviceIntegrityContract, integrity)

    private val store = object : Binding<AttestStoreState, AttestStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            AttestStoreState.EMPTY,
            AttestStoreState.HOLDING,
            AttestStoreState.HOLDING_CONTESTED,
        )

        override fun create(state: AttestStoreState, clauseId: String, log: CallLog): Entered<AttestStore> = when (state) {
            AttestStoreState.INACCESSIBLE -> Entered.Unreachable("the in-memory store models no unreadable Keychain")
            AttestStoreState.EMPTY -> Entered.Ready(inMemoryAttestStore().recorded(log))
            AttestStoreState.HOLDING, AttestStoreState.HOLDING_CONTESTED -> Entered.Ready(
                inMemoryAttestStore(AttestStoreContract.seedToken(clauseId), AttestStoreContract.seedKeyId(clauseId))
                    .recorded(log),
            )
        }
    }

    @Test
    fun `the in-memory store satisfies the AttestStore contract`() = verify(AttestStoreContract, store)

    /**
     * Both processes compose the store behind the in-memory token copy ([CachedAttestStore]), so the copy must
     * keep every promise the store makes — a write reads back, a clear keeps the keyId. The real Keychain's
     * clauses stay bound to the bare adapter: the copy adds no platform call, and binding it there would change
     * the recorded call sequence the replay matches.
     */
    private val cachedStore = object : Binding<AttestStoreState, AttestStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            AttestStoreState.EMPTY,
            AttestStoreState.HOLDING,
            AttestStoreState.HOLDING_CONTESTED,
        )

        override fun create(state: AttestStoreState, clauseId: String, log: CallLog): Entered<AttestStore> =
            // The copy is the subject under contract, so it is the one recorded; the store behind it records nowhere.
            when (val entered = store.create(state, clauseId, CallLog())) {
                is Entered.Ready -> Entered.Ready(CachedAttestStore(entered.subject).recorded(log), entered.dispose)
                else -> entered
            }
    }

    @Test
    fun `the in-memory token copy keeps the AttestStore contract`() = verify(AttestStoreContract, cachedStore)

    private val processInfo = object : Binding<ProcessInfoState, ProcessInfo> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(ProcessInfoState.UNLOCKED, ProcessInfoState.MEMORY_ACCOUNTED)

        override fun create(state: ProcessInfoState, clauseId: String, log: CallLog): Entered<ProcessInfo> =
            if (state in reaches) {
                Entered.Ready(inMemoryProcessInfo(MutableStateFlow(Availability.AVAILABLE)).recorded(log))
            } else {
                Entered.Unreachable("the double accounts a footprint and is held available")
            }
    }

    @Test
    fun `the in-memory process info satisfies the ProcessInfo contract`() =
        verify(ProcessInfoContract, processInfo)

    private val network = object : Binding<NetworkState, NetworkMonitor> {
        override val host = currentHost
        override val kind = BindingKind.Fake
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
