package app.snapsync.extension

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionHostContract
import app.snapsync.contracts.ExtensionHostState
import app.snapsync.contracts.ExtensionHostUnderTest
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The upload extension's entry ([IosExtensionHost]) in the simulator's test executable, invoked and ended through the
 * two entries the Swift principal class forwards `process()` and `notifyTermination()` to — the calls the operating
 * system's own invocation makes. What the OS does around them (the ~60 s bound, the kill) is the device's.
 */
class IosExtensionHostContractTest {

    private val binding = object : Binding<ExtensionHostState, ExtensionHostUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(ExtensionHostState.INVOKABLE)
        override fun create(state: ExtensionHostState, clauseId: String): Entered<ExtensionHostUnderTest> {
            val host = IosExtensionHost(Logger.withTag("contract"))
            return Entered.Ready(
                ExtensionHostUnderTest(
                    host,
                    host::deliverProcess,
                    host::deliverTerminate,
                ) { it.processingResultRawValue() },
            )
        }
    }

    @Test
    fun `the extension's entry satisfies the ExtensionHost contract`() = verify(ExtensionHostContract, binding)
}
