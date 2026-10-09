package app.snapsync.android.buildinfo

import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.BuildInfoContract
import app.snapsync.contracts.BuildInfoState
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.BuildInfo
import kotlin.test.Test

/**
 * [AndroidBuildInfo] over the build constants the root hands it, as a distributed build's (a DSN and a Play page) and as
 * every other build's (both blank), with the OS and device it reads itself.
 */
class AndroidBuildInfoContractTest {

    private fun build(distributed: Boolean) = AndroidBuildInfo(
        appVersion = "1.2",
        buildNumber = "2140",
        uploadHost = "https://snapsync.example/api/v2",
        bootLines = listOf("=== app process start ==="),
        apnsEnvironment = "contract-project",
        playStoreUrl = if (distributed) "https://play.google.com/store/apps/details?id=app.snapsync" else "",
        processId = context.packageName,
        dsn = if (distributed) "https://key@bugsink.example/1" else "",
        reporterEnvironment = "development",
    )

    private val binding = object : Binding<BuildInfoState, BuildInfo> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            BuildInfoState.REPORTING_AND_LISTED,
            BuildInfoState.BUNDLED,
            BuildInfoState.UNREPORTED_AND_UNLISTED,
            BuildInfoState.ON_ANDROID,
        )
        override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> =
            if (state in reaches) {
                Entered.Ready(build(distributed = state == BuildInfoState.REPORTING_AND_LISTED).recorded(log))
            } else {
                Entered.Unreachable("an Android process has its package for a bundle, and no OS-driven uploader")
            }
    }

    @Test
    fun `the Android build facts satisfy the BuildInfo contract`() = verify(BuildInfoContract, binding)
}
