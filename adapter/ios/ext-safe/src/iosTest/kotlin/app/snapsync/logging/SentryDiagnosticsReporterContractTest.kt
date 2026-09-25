package app.snapsync.logging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeliveredEvent
import app.snapsync.contracts.DiagnosticsObservation
import app.snapsync.contracts.DiagnosticsReporterContract
import app.snapsync.contracts.DiagnosticsReporterState
import app.snapsync.contracts.DiagnosticsReporterSubject
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.WaitExpired
import app.snapsync.contracts.verify
import app.snapsync.model.NON_REDACTED_TAG
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask

/**
 * The Sentry seat of `DiagnosticsReporter`, live on the simulator test executable (`docs/architecture.md`),
 * over the REAL SDK.
 *
 * - `UNCONFIGURED` is the **production default**: this executable carries no `Deployment.plist`, so the adapter's
 *   own bundle lookup answers "no DSN" — the host's answer, not one this binding passed.
 * - The configured states inject a DSN (the adapter's `internal` constructor) that points the channel at a
 *   [LoopbackIngest] in this process, and read what left the process there. The ingest decides nothing a clause
 *   asserts, so this binding is `Live`. Nothing is ever sent to the operator's instance.
 *
 * The adapter and the SDK are process-global — the idempotence flag, the SDK hub, Kermit's writer list — and other
 * tests in this executable initialise Sentry too. So every clause gets a fresh instance the only way one exists
 * here: the SDK closed, its envelope cache wiped (a rejected or undelivered envelope persists there ACROSS runs and
 * blocks every later one — measured 2026-09-23), the start flag forgotten, and Kermit's writers restored.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class SentryDiagnosticsReporterContractTest {

    private val binding = object : Binding<DiagnosticsReporterState, DiagnosticsReporterSubject> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DiagnosticsReporterState.UNCONFIGURED,
            DiagnosticsReporterState.CONFIGURED,
            DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE,
        )

        override fun create(state: DiagnosticsReporterState, clauseId: String): Entered<DiagnosticsReporterSubject> {
            val writers = Logger.config.logWriterList
            resetChannel()
            val ingest = if (state == DiagnosticsReporterState.UNCONFIGURED) null else LoopbackIngest()
            val reporter = if (ingest == null) SentryDiagnosticsReporter() else SentryDiagnosticsReporter(ingest.dsn)
            val observe = object : DiagnosticsObservation {
                override fun channelRunning() = Sentry.isEnabled()

                override fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent> {
                    val deadline = Clock.System.now() + DELIVERY_DEADLINE
                    while (true) {
                        val events = ingest?.events().orEmpty().map { it.toDelivered() }
                        if (until(events)) return events
                        if (Clock.System.now() > deadline) throw WaitExpired(DELIVERY_DEADLINE.inWholeMilliseconds)
                        NSThread.sleepForTimeInterval(POLL_SECONDS)
                    }
                }
            }
            return Entered.Ready(DiagnosticsReporterSubject(reporter, observe)) {
                resetChannel()
                ingest?.stop()
                Logger.setLogWriters(writers)
            }
        }
    }

    @Test
    fun `the Sentry reporter satisfies the DiagnosticsReporter contract`() =
        verify(DiagnosticsReporterContract, binding)


    private fun resetChannel() {
        Sentry.close()
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        NSFileManager.defaultManager.removeItemAtPath("$caches/$SDK_CACHE", null)
        resetProcessStart()
    }

    /** The wire format onto the contract's vocabulary: this is the one place that knows Sentry's event shape. */
    private fun JsonObject.toDelivered(): DeliveredEvent {
        val message = this["message"]?.jsonObject?.let { m ->
            (m["formatted"] ?: m["message"])?.jsonPrimitive?.content
        }
        // An event's breadcrumbs arrive as a bare array (measured); the protocol also allows `{ "values": [...] }`.
        val crumbs = when (val raw = this["breadcrumbs"]) {
            is JsonArray -> raw
            is JsonObject -> raw["values"] as? JsonArray
            else -> null
        }.orEmpty().mapNotNull { (it as? JsonObject)?.get("message")?.jsonPrimitive?.content }
        val tags = (this["tags"] as? JsonObject).orEmpty()
        return DeliveredEvent(
            message = message,
            breadcrumbs = crumbs,
            installId = (this["user"] as? JsonObject)?.get("id")?.jsonPrimitive?.content,
            processAccount = ((this["contexts"] as? JsonObject)?.get(PROCESS_METRIC_CONTEXT) as? JsonObject)
                ?.mapValues { (_, v) -> (v as JsonPrimitive).content },
            isDump = (tags[NON_REDACTED_TAG] as? JsonPrimitive)?.content == "1",
        )
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
