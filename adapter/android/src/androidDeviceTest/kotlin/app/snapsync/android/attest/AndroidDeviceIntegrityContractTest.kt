package app.snapsync.android.attest

import android.util.Base64
import android.util.Log
import app.snapsync.android.network.EmulatorNetwork
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceIntegrityContract
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.model.ProofFormat
import app.snapsync.ports.DeviceIntegrity
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DeviceIntegrityContract] against the real [AndroidDeviceIntegrity] on the emulator's Keystore — which attests in
 * SOFTWARE, so this proves the adapter (a key is made, attested, named, and signs; an unknown one refuses), never the
 * hardware. [DeviceIntegrityState.UNAVAILABLE] cannot arise: key attestation exists in the one process Android has.
 *
 * It also RECORDS the proof the api's tests replay (`api/test/fixtures/android-emulator-proof.ts`): an attestation over
 * [RECORDED_CHALLENGE] and a renewal signature over [RECORDED_RENEWAL], logged under the tag [RECORDING_TAG]. The
 * challenges are what the api's test harness key mints at the recording's instant, so the recorded proof goes through
 * the real routes there. To re-record (after changing how the adapter encodes a proof):
 *
 *     ./gradlew :adapter:android:connectedAndroidDeviceTest
 *     adb logcat -d -s snapsync-recording:I     # the lines this test logged, copied into the fixture unedited
 *
 * The emulator's KeyMint attests only with a key Remote Key Provisioning fetched over the network, and these tests run
 * first: on a boot whose network came up late, a fresh attestation fails "Failed to generate key pair". So each waits
 * for a network first, and fails naming it when there is none.
 */
class AndroidDeviceIntegrityContractTest {

    private val binding = object : Binding<DeviceIntegrityState, DeviceIntegrity> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(DeviceIntegrityState.AVAILABLE)

        override fun create(state: DeviceIntegrityState, clauseId: String): Entered<DeviceIntegrity> =
            if (state == DeviceIntegrityState.AVAILABLE) {
                Entered.Ready(AndroidDeviceIntegrity())
            } else {
                Entered.Unreachable("key attestation exists in the one process Android has")
            }
    }

    @BeforeTest
    fun `the Keystore can provision an attestation key`() =
        EmulatorNetwork.requireInternet("the Keystore provisions attestation keys over the network (RKP)")

    @Test
    fun `the Keystore satisfies the DeviceIntegrity contract`() = verify(DeviceIntegrityContract, binding)

    @Test
    fun `it records a proof for the api to replay`() = runTest {
        val integrity = AndroidDeviceIntegrity()
        val fresh = integrity.prove(RECORDED_CHALLENGE)
        assertEquals(ProofFormat.ANDROID_KEY, fresh.format)
        val renewal = integrity.prove(RECORDED_RENEWAL, fresh.handle)
        Log.i(RECORDING_TAG, "attestation=" + Base64.encodeToString(fresh.bytes, Base64.NO_WRAP))
        Log.i(RECORDING_TAG, "renewal=" + Base64.encodeToString(renewal.bytes, Base64.NO_WRAP))
    }

    companion object {
        /** The tag the recording is logged under. */
        const val RECORDING_TAG = "snapsync-recording"

        /**
         * `mintChallenge(CONFIG, RECORDED_AT)` with `api/test/support/harness.ts`'s CONFIG, RECORDED_AT = 2026-09-29T12:00Z:
         * deterministic, and minted inside the recorded chain's validity (its intermediate lives ~2 weeks). A re-recording
         * moves RECORDED_AT to its own day, here and in the fixture.
         */
        const val RECORDED_CHALLENGE = "1790683500.j0wI27UuMG300pHe0sHkDIm3llkluFrUUqWI56G3eow="

        /** `mintChallenge(CONFIG, RECORDED_AT + 1000)`: a second challenge, so the renewal signs what the attestation did not. */
        const val RECORDED_RENEWAL = "1790683501.8L0y1d92llo2DEEyPE4DaHVWKQ+KAcXUzuFrY7yW59o="
    }
}
