package app.snapsync.device

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DeviceConditionsContract
import app.snapsync.contracts.DeviceConditionsState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.DeviceConditions
import kotlin.test.Test

/**
 * [DeviceConditionsContract] against the real [IosProcessConditions] in the test executable — an iPhone process that,
 * like the upload extension, holds no `UIApplication`: it presents [DeviceConditionsState.IPHONE_PROCESS] and nothing
 * else. `NSProcessInfo` answers Low Power Mode and the thermal state in any process, a simulator's included.
 */
class IosProcessConditionsContractTest {

    private val binding = object : Binding<DeviceConditionsState, DeviceConditions> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosProcessConditions::class
        override val reaches = setOf(DeviceConditionsState.IPHONE_PROCESS)

        override fun create(state: DeviceConditionsState, clauseId: String, log: CallLog): Entered<DeviceConditions> =
            when (state) {
                DeviceConditionsState.IPHONE_PROCESS -> Entered.Ready(IosProcessConditions().recorded(log))
                DeviceConditionsState.IPHONE -> Entered.Unreachable("a test executable has no UIApplication")
                DeviceConditionsState.ANDROID -> Entered.Unreachable("an iPhone is not Android")
            }
    }

    @Test
    fun the_process_conditions_keep_the_contract() = verify(DeviceConditionsContract, binding)
}
