package app.snapsync.config

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
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test

/**
 * [IosBuildInfo] in the simulator's test executable: a process with no bundle, so no `Deployment.plist` — it reports
 * nowhere, is listed nowhere and has no bundle id, exactly as every non-distributed build reads.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBuildInfoContractTest {

    private val binding = object : Binding<BuildInfoState, BuildInfo> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosBuildInfo::class
        override val reaches =
            setOf(BuildInfoState.UNREPORTED_AND_UNLISTED, BuildInfoState.UNBUNDLED, BuildInfoState.ON_IOS)
        override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> =
            if (state in reaches) {
                Entered.Ready(IosBuildInfo().recorded(log))
            } else {
                Entered.Unreachable("a test executable has no bundle: nothing distributed, no app on a device")
            }
    }

    @Test
    fun `the bundle-less build facts satisfy the BuildInfo contract`() = verify(BuildInfoContract, binding)

    /** A bundle built here, carrying a distributed build's `Deployment.plist` — what the resolver renders for TestFlight. */
    private val distributed = object : Binding<BuildInfoState, BuildInfo> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosBuildInfo::class
        override val reaches = setOf(BuildInfoState.REPORTING_AND_LISTED)
        override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> {
            if (state !in reaches) return Entered.Unreachable("this binding holds a distributed build's bundle only")
            val dir = NSTemporaryDirectory() + "distributed-${NSUUID().UUIDString}"
            NSFileManager.defaultManager.createDirectoryAtPath(
                dir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            val values = mapOf(
                "sentryDsn" to "https://key@reports.contract.invalid/1",
                "appStoreUrl" to "https://apps.apple.com/de/app/snapsync/id0",
                "uploadBase" to "https://contract.invalid/api/v2",
                "apnsEnv" to "production",
            )
            val plist = values.entries.joinToString(
                "",
                PLIST_HEAD,
                PLIST_TAIL,
            ) { (k, v) -> "<key>$k</key><string>$v</string>" }

            @Suppress("CAST_NEVER_SUCCEEDS") // a Kotlin String is an NSString on Kotlin/Native
            val written = (plist as NSString).writeToFile("$dir/Deployment.plist", true, NSUTF8StringEncoding, null)
            check(written) { "the bundle's Deployment.plist could not be written" }
            val bundle = checkNotNull(NSBundle.bundleWithPath(dir)) { "no bundle at $dir" }
            return Entered.Ready(IosBuildInfo(bundle).recorded(log)) {
                NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
            }
        }
    }

    private companion object {
        const val PLIST_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
        const val PLIST_TAIL = "</dict></plist>"
    }

    @Test
    fun `a distributed build's bundle satisfies the BuildInfo contract`() = verify(BuildInfoContract, distributed)
}
