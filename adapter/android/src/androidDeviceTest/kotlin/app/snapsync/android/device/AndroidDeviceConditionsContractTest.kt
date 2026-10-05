package app.snapsync.android.device

import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceConditionsContract
import app.snapsync.contracts.DeviceConditionsState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.DeviceConditions
import kotlin.test.Test

/**
 * [DeviceConditionsContract] against the real [AndroidDeviceConditions] on the emulator, which is an Android device as
 * the contract means it: Battery Saver, a standby bucket, an exemption and a thermal status are all read, and the
 * emulator's virtual battery answers a level and a state.
 */
class AndroidDeviceConditionsContractTest {

    private val binding = object : Binding<DeviceConditionsState, DeviceConditions> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(DeviceConditionsState.ANDROID)

        override fun create(state: DeviceConditionsState, clauseId: String): Entered<DeviceConditions> = when (state) {
            DeviceConditionsState.ANDROID -> Entered.Ready(AndroidDeviceConditions(context))
            DeviceConditionsState.IPHONE -> Entered.Unreachable("Android is not an iPhone")
        }
    }

    @Test
    fun `the platform power and battery reads satisfy the DeviceConditions contract`() =
        verify(DeviceConditionsContract, binding)
}
