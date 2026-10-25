@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import app.snapsync.contracts.FatalRoute
import app.snapsync.contracts.Host
import app.snapsync.contracts.ProcessDeath
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.model.CrashOptions
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * `CrashReporter`'s [CrashReporterState.AFTER_A_PROCESS_DEATH], live on the simulator test executable over the REAL SDK
 * and the REAL roads to the end of a process: the runtime's (`:domain:scheduling`) and `objcBoundary`'s.
 *
 * Three roles of this one executable ([runSelf]): the test as Gradle runs it starts the NEXT LAUNCH — a fresh process
 * whose SDK was never started, since sentry-cocoa reads its crash reports once per process — and fails unless it passes.
 * The next launch runs the clause, whose [ProcessDeath] runs a CRASH child per road: it starts the channel against the
 * clause's ingest under the production handlers, throws an `InvalidProgramState` down that road and dies. The children
 * share this process's Caches directory, where the reports wait for the next start.
 */
class SentryProcessDeathContractTest {

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = SentryCrashReporter::class
        override val reaches = setOf(CrashReporterState.AFTER_A_PROCESS_DEATH)

        override fun create(state: CrashReporterState, clauseId: String, log: CallLog): Entered<CrashReporterSubject> {
            if (state !in reaches) return Entered.Unreachable("SentryCrashReporterContractTest's, in one process")
            wipeCaches()
            val ingest = LoopbackIngest()
            val death = object : ProcessDeath {
                override val routes = FatalRoute.entries

                override fun endOn(route: FatalRoute) {
                    val end = runSelf(CRASH_TEST, mapOf(ROLE to CRASH, ROUTE to route.name, DSN to ingest.dsn))
                    assertTrue(end.signal != 0, "the process that threw down $route ended by a signal, not $end")
                }
            }
            val observe = object : CrashObservation {
                override fun channelRunning() = io.sentry.kotlin.multiplatform.Sentry.isEnabled()

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
            val subject = CrashReporterSubject(
                SentryCrashReporter().recorded(log),
                CrashOptions(ingest.dsn),
                observe,
                death = death,
            )
            return Entered.Ready(subject) { ingest.stop() }
        }
    }

    /** As Gradle runs it: the next launch, in a process of its own. As the next launch: the contract. */
    @Test
    fun `the Sentry reporter delivers what a process that ended on an invalid program state left`() {
        when (childRole(ROLE)) {
            NEXT -> verify(CrashReporterContract, binding)
            null -> {
                val end = runSelf(NEXT_TEST, mapOf(ROLE to NEXT))
                assertTrue(end.passed, "the next launch, which runs the clause, failed ($end): its log is above")
            }
        }
    }

    /** A crash child: dies on an `InvalidProgramState` down the road its parent named. A no-op in any other role. */
    @Test
    fun crash() {
        if (childRole(ROLE) != CRASH) return
        SentryCrashReporter().apply {
            listen(CrashReporterContract.PRODUCTION)
            start(CrashOptions(checkNotNull(childRole(DSN))))
        }
        when (FatalRoute.valueOf(checkNotNull(childRole(ROUTE)))) {
            FatalRoute.UNAWAITED_WORK -> endOnUnawaitedWork()
            FatalRoute.PLATFORM_DELIVERY -> endOnPlatformDelivery()
            FatalRoute.OBJC_CALLBACK -> dispatch_async(
                dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
            ) {
                objcBoundary(Logger.withTag("ContractDeath"), "the contract's callback") {
                    throw InvalidProgramState(CrashReporterContract.becauseOf(FatalRoute.OBJC_CALLBACK))
                }
            }
        }
        NSThread.sleepForTimeInterval(
            OUTLIVED_SECONDS,
        ) // the process ends long before; living through it fails the parent
    }

    /** Every report, envelope and crash state an earlier run left, so the next launch delivers only this run's. */
    private fun wipeCaches() {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val files = NSFileManager.defaultManager
        listOf(SDK_CACHE, CRASH_REPORTS).forEach { files.removeItemAtPath("$caches/$it", null) }
    }

    private companion object {
        const val ROLE = "SNAPSYNC_CONTRACT_DEATH_ROLE"
        const val ROUTE = "SNAPSYNC_CONTRACT_DEATH_ROUTE"
        const val DSN = "SNAPSYNC_CONTRACT_DEATH_DSN"
        const val NEXT = "next"
        const val CRASH = "crash"
        const val NEXT_TEST = "app.snapsync.sentry.SentryProcessDeathContractTest.the*"
        const val CRASH_TEST = "app.snapsync.sentry.SentryProcessDeathContractTest.crash"

        val DELIVERY_DEADLINE = 45.seconds
        const val POLL_SECONDS = 0.02
        const val OUTLIVED_SECONDS = 30.0

        /** Where sentry-cocoa keeps envelopes, and where SentryCrash keeps the reports a crash writes (measured). */
        const val SDK_CACHE = "io.sentry"
        const val CRASH_REPORTS = "SentryCrash"
    }
}
