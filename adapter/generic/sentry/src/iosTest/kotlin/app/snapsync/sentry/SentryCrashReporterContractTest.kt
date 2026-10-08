package app.snapsync.sentry

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.CrashObservation
import app.snapsync.contracts.CrashReporterContract
import app.snapsync.contracts.CrashReporterState
import app.snapsync.contracts.CrashReporterSubject
import app.snapsync.contracts.DeliveredEvent
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.CrashOptions
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The Sentry seat of `CrashReporter`, live on the simulator test executable (`docs/architecture.md`), over the REAL
 * SDK.
 *
 * Every state starts the channel against a [LoopbackIngest] in this process, and reads what left the process there.
 * The ingest decides nothing a clause asserts, so this binding is `Live`. Nothing is ever sent to the operator's
 * instance.
 *
 * The adapter and the SDK are process-global — the idempotence flag, the SDK hub, Kermit's writer list — and other
 * tests in this executable initialise Sentry too. So every clause gets a fresh instance the only way one exists
 * here: the SDK closed, its envelope cache wiped (a rejected or undelivered envelope persists there ACROSS runs and
 * blocks every later one — measured 2026-09-23), the start flag forgotten, and Kermit's writers restored.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class SentryCrashReporterContractTest {

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live

        // Not `ACROSS_A_RESTART`: its clause holds a build number given at start to the build the report was captured
        // under, and sentry-cocoa re-stamps the dist at SEND — so iOS is given none (`model/`'s `crashDist`), and the
        // crash-time build rides in the crash report itself (a belief, documented on the reporter).
        override val reaches = setOf(
            CrashReporterState.NOT_STARTED,
            CrashReporterState.STARTED,
            CrashReporterState.ON_THE_WIRE,
        )

        override fun create(state: CrashReporterState, clauseId: String, log: CallLog): Entered<CrashReporterSubject> {
            if (state == CrashReporterState.ACROSS_A_RESTART) {
                return Entered.Unreachable("sentry-cocoa re-stamps a given build number at send, so iOS is given none")
            }
            val writers = Logger.config.logWriterList
            resetChannel()
            val ingest = LoopbackIngest()
            val observe = object : CrashObservation {
                override fun channelRunning() = Sentry.isEnabled()

                override fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent> {
                    val deadline = Clock.System.now() + DELIVERY_DEADLINE
                    while (true) {
                        val events = ingest.events().map { it.toDelivered() }
                        if (until(events)) return events
                        if (Clock.System.now() > deadline) throw WaitExpired(DELIVERY_DEADLINE.inWholeMilliseconds)
                        NSThread.sleepForTimeInterval(POLL_SECONDS)
                    }
                }
            }
            return Entered.Ready(
                CrashReporterSubject(SentryCrashReporter().recorded(log), CrashOptions(ingest.dsn), observe),
            ) {
                resetChannel()
                ingest.stop()
                Logger.setLogWriters(writers)
            }
        }
    }

    @Test
    fun `the Sentry reporter satisfies the CrashReporter contract`() =
        verify(CrashReporterContract, binding)

    private fun resetChannel() {
        Sentry.close()
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        NSFileManager.defaultManager.removeItemAtPath("$caches/$SDK_CACHE", null)
        resetProcessStart()
    }

    private companion object {
        /**
         * Past the worst first-send stall measured (26 s), so only a real non-delivery expires — and inside
         * `runTest`'s own one-minute timeout, so an expiry reads as the contract's outcome, not the test harness's.
         */
        val DELIVERY_DEADLINE = 45.seconds
        const val POLL_SECONDS = 0.02

        /** Where sentry-cocoa keeps envelopes it has not yet delivered, under the process's Caches directory. */
        const val SDK_CACHE = "io.sentry"
    }
}
