package app.snapsync.keychain.contract

import app.snapsync.attest.SystemAppAttestApi
import app.snapsync.attest.contract.RecordingAppAttestApi
import app.snapsync.attest.contract.attestKeyInState
import app.snapsync.attest.contract.attestStoreInState
import app.snapsync.contracts.AttestKeyContract
import app.snapsync.contracts.AttestKeyState
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Contract
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.keychain.SystemKeychainApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore
import app.snapsync.ports.SecureStore
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSISO8601DateFormatter

/**
 * The real [app.snapsync.keychain.IosKeychain] in the entitled app, recording every `SecItem*` call and
 * iOS's answer (`docs/architecture.md`). Recorded on a device, over the rig; replayed on every CI
 * build by `IosKeychainReplayContractTest`, which reuses [keychainInState] so both make the same calls.
 */
internal class DeviceKeychainBinding(private val recorder: Recorder) : Binding<SecureStoreState, SecureStore> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(
        SecureStoreState.EMPTY,
        SecureStoreState.HOLDING_BACKGROUND_READABLE,
        SecureStoreState.HOLDING_RESTRICTED,
    )

    override fun create(state: SecureStoreState, clauseId: String): Entered<SecureStore> {
        // Opened only for a state this host presents: a block for a clause that never ran here would read,
        // to the coverage gate, as a real host having run it.
        if (state !in reaches) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE)
        recorder.open(clauseId)
        return keychainInState(RecordingKeychainApi(SystemKeychainApi, recorder), state, clauseId)
    }
}

/**
 * The real [app.snapsync.attest.IosAttestKey] in the app process, recording every App Attest call and its
 * answer — credential bytes and minted key ids masked (`AppAttestTape.kt`). Replayed on every CI build by
 * `AttestReplayContractTest`, which reuses [attestKeyInState].
 */
internal class DeviceAttestKeyBinding(private val recorder: Recorder) : Binding<AttestKeyState, AttestKey> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(AttestKeyState.SUPPORTED)

    override fun create(state: AttestKeyState, clauseId: String): Entered<AttestKey> {
        if (state !in reaches) return attestKeyInState(SystemAppAttestApi, state)
        recorder.open(clauseId)
        return attestKeyInState(RecordingAppAttestApi(SystemAppAttestApi, recorder), state)
    }
}

/**
 * The real [app.snapsync.attest.KeychainAttestStore] in the entitled app, over the recorded Keychain seam.
 * Replayed on every CI build by `AttestReplayContractTest`, which reuses [attestStoreInState].
 */
internal class DeviceAttestStoreBinding(private val recorder: Recorder) : Binding<AttestStoreState, AttestStore> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(AttestStoreState.EMPTY, AttestStoreState.HOLDING)

    override fun create(state: AttestStoreState, clauseId: String): Entered<AttestStore> {
        if (state !in reaches) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE)
        recorder.open(clauseId)
        return attestStoreInState(RecordingKeychainApi(SystemKeychainApi, recorder), state, clauseId)
    }
}

/**
 * The contracts the rig can run on a device, by name — `POST /contract/<name>` (`docs/architecture.md`,
 * "The device run is reached through the rig and contained at compile time").
 *
 * Each answers with the recording to commit verbatim at `test/contracts/recordings/<name>@IOS_DEVICE_APP.rec`:
 * a provenance header, the live outcome of every clause (as header lines, so the committed file says what the
 * device concluded when it was recorded), then one block per clause.
 */
fun deviceContracts(): List<InAppContract> = listOf(
    InAppContract(SecureStoreContract.name, Host.IOS_DEVICE_APP) {
        recordOnDevice(SecureStoreContract) { DeviceKeychainBinding(it) }
    },
    InAppContract(AttestKeyContract.name, Host.IOS_DEVICE_APP) {
        recordOnDevice(AttestKeyContract) { DeviceAttestKeyBinding(it) }
    },
    InAppContract(AttestStoreContract.name, Host.IOS_DEVICE_APP) {
        recordOnDevice(AttestStoreContract) { DeviceAttestStoreBinding(it) }
    },
)

/**
 * Runs [contract] against the real platform of THIS app through the recording binding [binding] builds, and
 * renders the recording.
 *
 * It refuses on a simulator. A simulator app is not [Host.IOS_DEVICE_APP]: its Keychain answers `-34018`
 * (`errSecMissingEntitlement`) to an explicit-group query, because `simulator.entitlements` omits
 * `keychain-access-groups` on purpose and `RuntimeIdentityTest` pins that absence (measured here
 * 2026-09-22, iOS 26.2), and it has no App Attest at all. A recording taken there would file simulator
 * answers under the device's name — the host confusion the `-25291`/`-34018` pair exists to prevent.
 */
private fun <K : Enum<K>, T> recordOnDevice(contract: Contract<K, T>, binding: (Recorder) -> Binding<K, T>): String {
    if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
        return CONTRACT_REFUSED +
            "this process is a simulator app, not ${Host.IOS_DEVICE_APP}. Its Keychain answers -34018 " +
            "(errSecMissingEntitlement) to an explicit-group query and it has no App Attest, so a recording " +
            "taken here would be filed under the wrong host. Record on an entitled device.\n"
    }
    val recorder = Recorder()
    val results = run(contract, binding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf(
        "contract" to contract.name,
        "host" to Host.IOS_DEVICE_APP.name,
        "device" to env.deviceModel,
        "os" to env.osVersion,
        "build" to env.buildNumber,
        "kotlin" to KotlinVersion.CURRENT.toString(),
        "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
    ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return recorder.recording(header).render()
}
