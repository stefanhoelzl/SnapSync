package app.snapsync.sentry

import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CrashObservation
import app.snapsync.contracts.CrashReporterContract
import app.snapsync.contracts.CrashReporterState
import app.snapsync.contracts.CrashReporterSubject
import app.snapsync.contracts.CrashRestart
import app.snapsync.contracts.DeliveredEvent
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.verify
import app.snapsync.model.CrashOptions
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import java.io.File
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The Sentry seat of `CrashReporter`, live on the Android emulator, over the REAL SDK (sentry-android, through the KMP
 * layer) — the same adapter the iOS binding runs, on the other platform's SDK.
 *
 * Every state starts the channel against a [LoopbackIngest] in this process and reads what left the process there.
 * Nothing is ever sent to the operator's instance.
 *
 * The adapter and the SDK are process-global, so every clause gets a fresh instance the only way one exists here: the
 * SDK closed, its envelope cache wiped (an undelivered envelope persists there across runs and would be delivered into
 * a later clause), the start flag forgotten, and Kermit's writers restored.
 *
 * `ACROSS_A_RESTART` is reached here: the ingest listens only once the channel is relaunched, and the relaunch closes
 * the SDK WITHOUT wiping its cache — so what was captured before is delivered by the next start, as a crash is by the
 * next launch. It restarts the channel, not the process.
 */
class SentryCrashReporterContractTest {

    private val cacheDir: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, SDK_CACHE)

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            CrashReporterState.NOT_STARTED,
            CrashReporterState.STARTED,
            CrashReporterState.ON_THE_WIRE,
            CrashReporterState.ACROSS_A_RESTART,
        )

        override fun create(state: CrashReporterState, clauseId: String): Entered<CrashReporterSubject> {
            val writers = Logger.config.logWriterList
            resetChannel(wipeCache = true)
            val ingest = LoopbackIngest()
            val restart = CrashRestart {
                resetChannel(wipeCache = false)
                ingest.open()
                SentryCrashReporter()
            }
            if (state != CrashReporterState.ACROSS_A_RESTART) ingest.open()
            val observe = object : CrashObservation {
                override fun channelRunning() = Sentry.isEnabled()

                override fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent> {
                    val deadline = Clock.System.now() + DELIVERY_DEADLINE
                    while (true) {
                        val events = ingest.events().map { it.toDelivered() }
                        if (until(events)) return events
                        if (Clock.System.now() > deadline) throw WaitExpired(DELIVERY_DEADLINE.inWholeMilliseconds)
                        Thread.sleep(POLL_MILLIS)
                    }
                }
            }
            val subject = CrashReporterSubject(
                SentryCrashReporter(),
                CrashOptions(ingest.dsn),
                observe,
                restart.takeIf { state == CrashReporterState.ACROSS_A_RESTART },
            )
            return Entered.Ready(subject) {
                resetChannel(wipeCache = true)
                ingest.stop()
                Logger.setLogWriters(writers)
            }
        }
    }

    @Test
    fun `the Sentry reporter satisfies the CrashReporter contract`() =
        verify(CrashReporterContract, binding)

    private fun resetChannel(wipeCache: Boolean) {
        Sentry.close()
        if (wipeCache) cacheDir.deleteRecursively()
        resetProcessStart()
    }

    private companion object {
        /** As on iOS: past the worst first-send stall measured there, so only a real non-delivery expires. */
        val DELIVERY_DEADLINE = 45.seconds
        const val POLL_MILLIS = 20L

        /** Where sentry-android keeps envelopes it has not yet delivered, under the app's cache directory. */
        const val SDK_CACHE = "sentry"
    }
}
