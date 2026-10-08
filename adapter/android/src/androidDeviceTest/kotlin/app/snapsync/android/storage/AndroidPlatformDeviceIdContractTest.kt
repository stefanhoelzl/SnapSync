package app.snapsync.android.storage

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PlatformDeviceIdContract
import app.snapsync.contracts.PlatformDeviceIdState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.PlatformDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

/** [PlatformDeviceIdContract] against the real [AndroidPlatformDeviceId], over the emulator's own `ANDROID_ID`. */
class AndroidPlatformDeviceIdContractTest {

    private val binding = object : Binding<PlatformDeviceIdState, PlatformDeviceId> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(PlatformDeviceIdState.ANSWERING)

        override fun create(state: PlatformDeviceIdState, clauseId: String, log: CallLog): Entered<PlatformDeviceId> =
            if (state == PlatformDeviceIdState.ANSWERING) {
                Entered.Ready(AndroidPlatformDeviceId(context).recorded(log))
            } else {
                Entered.Unreachable("the platform answers ANDROID_ID for every app")
            }
    }

    @Test
    fun `ANDROID_ID satisfies the PlatformDeviceId contract`() = verify(PlatformDeviceIdContract, binding)

    // The derivation is runtime identity: a changed one gives every Android device a new identity on its next mint.
    @Test
    fun `the derivation is pinned`() {
        assertEquals(PINNED, AndroidPlatformDeviceId.deviceIdFor("0123456789abcdef"))
    }

    private companion object {
        const val PINNED = "B96C5885-F7F0-5D73-9EE1-7E9ECE939039"
    }
}
