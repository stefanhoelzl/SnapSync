package app.snapsync.config

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.BuildInfoContract
import app.snapsync.contracts.BuildInfoState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.ports.BuildInfo
import kotlin.test.Test

/**
 * [IosBuildInfo] in the simulator's test executable: a process with no bundle, so no `Deployment.plist` — it reports
 * nowhere, is listed nowhere and has no bundle id, exactly as every non-distributed build reads.
 */
class IosBuildInfoContractTest {

    private val binding = object : Binding<BuildInfoState, BuildInfo> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches =
            setOf(BuildInfoState.UNREPORTED_AND_UNLISTED, BuildInfoState.UNBUNDLED, BuildInfoState.ON_IOS)
        override fun create(state: BuildInfoState, clauseId: String): Entered<BuildInfo> =
            if (state in reaches) {
                Entered.Ready(
                    IosBuildInfo(osSupportsOsDrivenUpload = osCarriesOsDrivenUpload(), bootLines = emptyList()),
                )
            } else {
                Entered.Unreachable("a test executable has no bundle: nothing distributed, no app on a device")
            }
    }

    @Test
    fun `the bundle-less build facts satisfy the BuildInfo contract`() = verify(BuildInfoContract, binding)
}
