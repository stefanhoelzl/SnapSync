package app.snapsync.keychain.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.SecureStoreContract
import app.snapsync.contracts.SecureStoreState
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.keychain.SystemKeychainApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.ports.SecureStore
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

/**
 * The real [app.snapsync.keychain.IosKeychain] in the entitled app, recording every `SecItem*` call and
 * iOS's answer (capability `port-contracts`). Recorded on a device, over the rig; replayed on every CI
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
 * The contracts the rig can run on a device, by name — `POST /contract/<name>` (capability `port-contracts`,
 * "The device run is reached through the rig and contained at compile time").
 *
 * Each answers with the recording to commit verbatim at `test/contracts/recordings/<name>@IOS_DEVICE_APP.rec`:
 * a provenance header, the live outcome of every clause (as header lines, so the committed file says what the
 * device concluded when it was recorded), then one block per clause.
 */
fun deviceContracts(): Map<String, () -> String> = mapOf(
    SecureStoreContract.name to {
        val recorder = Recorder()
        val results = run(SecureStoreContract, DeviceKeychainBinding(recorder))
        val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
        val header = listOf(
            "contract" to SecureStoreContract.name,
            "host" to Host.IOS_DEVICE_APP.name,
            "device" to env.deviceModel,
            "os" to env.osVersion,
            "build" to env.buildNumber,
            "kotlin" to KotlinVersion.CURRENT.toString(),
            "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
        ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
        recorder.recording(header).render()
    },
)
