package app.snapsync.sentry

import android.app.Service
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
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
import app.snapsync.model.CrashOptions
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * `CrashReporter`'s [CrashReporterState.AFTER_A_PROCESS_DEATH], live on the emulator over the REAL SDK and the REAL
 * roads to the end of a process (the runtime's, `:domain:scheduling`).
 *
 * The app's process is [ContractDeathService], in a process of its own (`:contractdeath`): it starts the channel against
 * the clause's ingest under the production handlers, throws an `InvalidProgramState` down the road it was asked for and
 * dies, its report written to the app's cache. The test process is the next launch: sentry-android delivers what its
 * cache holds whenever it starts — as `ACROSS_A_RESTART` relies on — so the clause's start is the next one.
 */
class SentryProcessDeathContractTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val binding = object : Binding<CrashReporterState, CrashReporterSubject> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = SentryCrashReporter::class
        override val reaches = setOf(CrashReporterState.AFTER_A_PROCESS_DEATH)

        override fun create(state: CrashReporterState, clauseId: String, log: CallLog): Entered<CrashReporterSubject> {
            if (state !in reaches) return Entered.Unreachable("SentryCrashReporterContractTest's, in one process")
            val writers = Logger.config.logWriterList
            resetChannel()
            val ingest = LoopbackIngest().also { it.open() }
            val death = object : ProcessDeath {
                override val routes = listOf(FatalRoute.UNAWAITED_WORK, FatalRoute.PLATFORM_DELIVERY)

                override fun endOn(route: FatalRoute) = endProcess(route, ingest.dsn)
            }
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
                SentryCrashReporter().recorded(log),
                CrashOptions(ingest.dsn),
                observe,
                death = death,
            )
            return Entered.Ready(subject) {
                resetChannel()
                ingest.stop()
                Logger.setLogWriters(writers)
            }
        }
    }

    @Test
    fun `the Sentry reporter delivers what a process that ended on an invalid program state left`() =
        verify(CrashReporterContract, binding)

    /**
     * Runs the app's process down [route] and waits for it to die. The bind is let go the moment it connects: a service
     * still bound when its process crashes is restarted by the system, and the restarted process would answer the next
     * bind. Its death is still told, through the binder it answered.
     */
    private fun endProcess(route: FatalRoute, dsn: String) {
        val died = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    service.linkToDeath({ died.countDown() }, 0)
                } catch (_: DeadObjectException) {
                    died.countDown() // it died before we could watch it
                }
                context.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        // One intent per road: a bind is matched on everything but its extras, so the roads' intents differ in action.
        val intent = Intent(context, ContractDeathService::class.java)
            .setAction(route.name)
            .putExtra(ContractDeathService.ROUTE, route.name)
            .putExtra(ContractDeathService.DSN, dsn)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE), "the app's process starts")
        assertTrue(died.await(DEATH_SECONDS, TimeUnit.SECONDS), "the app's process ended on the throw down $route")
    }

    private fun resetChannel() {
        Sentry.close()
        File(context.cacheDir, SDK_CACHE).deleteRecursively()
        resetProcessStart()
    }

    private companion object {
        val DELIVERY_DEADLINE = 45.seconds
        const val POLL_MILLIS = 20L
        const val DEATH_SECONDS = 60L

        /** Where sentry-android keeps envelopes it has not yet delivered, under the app's cache directory. */
        const val SDK_CACHE = "sentry"
    }
}

/**
 * The app's process for the process-death clause: bound to, it starts the channel and — once it has answered the bind —
 * throws an `InvalidProgramState` down the road it was asked for. It never lives to be unbound.
 */
class ContractDeathService : Service() {
    override fun onBind(intent: Intent): IBinder {
        // sentry-kmp takes its context from a provider that only the app's default process creates — Kotlin-internal, so
        // made by name, as the manifest makes it.
        (Class.forName(SENTRY_CONTEXT_PROVIDER).getDeclaredConstructor().newInstance() as ContentProvider)
            .attachInfo(applicationContext, ProviderInfo())
        SentryCrashReporter().apply {
            listen(CrashReporterContract.PRODUCTION)
            start(CrashOptions(checkNotNull(intent.getStringExtra(DSN))))
        }
        val route = FatalRoute.valueOf(checkNotNull(intent.getStringExtra(ROUTE)))
        // After the bind has been answered and let go (`endProcess`), so the system does not restart it.
        val down = Runnable {
            when (route) {
                FatalRoute.UNAWAITED_WORK -> endOnUnawaitedWork()
                FatalRoute.PLATFORM_DELIVERY -> endOnPlatformDelivery()
                FatalRoute.OBJC_CALLBACK -> error("no Objective-C on Android")
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(down, THROW_DELAY_MILLIS)
        return Binder()
    }

    companion object {
        const val ROUTE = "route"
        const val DSN = "dsn"
        private const val THROW_DELAY_MILLIS = 1_000L
        private const val SENTRY_CONTEXT_PROVIDER = "io.sentry.kotlin.multiplatform.SentryContextProvider"
    }
}
