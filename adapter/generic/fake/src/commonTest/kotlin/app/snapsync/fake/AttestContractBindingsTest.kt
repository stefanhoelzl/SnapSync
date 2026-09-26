package app.snapsync.fake

import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ProtectedStorageContract
import app.snapsync.contracts.ProtectedStorageState
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.services.trust.CachedAttestStore
import app.snapsync.ports.AttestStore
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.ProtectedStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The integrity and attest-store doubles and the protected-storage double, held to the contracts their real implementations
 * satisfy (`docs/architecture.md`). Each double is built at its DEFAULTS for the state, never with a knob
 * turned to make a clause pass: a double the contract catches lying is fixed in the double.
 */
class AttestContractBindingsTest {

    private val integrity = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DeviceIntegrityState.UNAVAILABLE, DeviceIntegrityState.AVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String): Entered<DeviceIntegrity> =
            Entered.Ready(inMemoryDeviceIntegrity(available = state == DeviceIntegrityState.AVAILABLE))
    }

    @Test
    fun `the in-memory integrity satisfies the DeviceIntegrity contract`() = verify(DeviceIntegrityContract, integrity)

    private val store = object : Binding<AttestStoreState, AttestStore> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(AttestStoreState.EMPTY, AttestStoreState.HOLDING)

        override fun create(state: AttestStoreState, clauseId: String): Entered<AttestStore> = when (state) {
            AttestStoreState.INACCESSIBLE -> Entered.Unreachable("the in-memory store models no unreadable Keychain")
            AttestStoreState.EMPTY -> Entered.Ready(inMemoryAttestStore())
            AttestStoreState.HOLDING -> Entered.Ready(
                inMemoryAttestStore(AttestStoreContract.seedToken(clauseId), AttestStoreContract.seedKeyId(clauseId)),
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
        override val reaches = setOf(AttestStoreState.EMPTY, AttestStoreState.HOLDING)

        override fun create(state: AttestStoreState, clauseId: String): Entered<AttestStore> =
            when (val entered = store.create(state, clauseId)) {
                is Entered.Ready -> Entered.Ready(CachedAttestStore(entered.subject), entered.dispose)
                else -> entered
            }
    }

    @Test
    fun `the in-memory token copy keeps the AttestStore contract`() = verify(AttestStoreContract, cachedStore)

    private val protectedStorage = object : Binding<ProtectedStorageState, ProtectedStorage> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(ProtectedStorageState.UNLOCKED)

        override fun create(state: ProtectedStorageState, clauseId: String): Entered<ProtectedStorage> =
            Entered.Ready(inMemoryProtectedStorage(MutableStateFlow(true)))
    }

    @Test
    fun `the in-memory protected storage satisfies the ProtectedStorage contract`() =
        verify(ProtectedStorageContract, protectedStorage)
}
