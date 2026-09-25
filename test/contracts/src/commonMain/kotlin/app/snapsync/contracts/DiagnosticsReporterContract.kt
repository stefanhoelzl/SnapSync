package app.snapsync.contracts

import app.snapsync.model.BREADCRUMB_TEXT_BYTES
import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.MAX_BREADCRUMBS
import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.DiagnosticsReporter
import co.touchlab.kermit.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states the reporting channel behind a [DiagnosticsReporter] can be found in, as far as a clause cares. */
enum class DiagnosticsReporterState {
    /** The build carries no reporting configuration — every dev, sideload and simulator build. */
    UNCONFIGURED,

    /** The build carries a reporting destination. Clauses read the port's surface and whether the channel runs. */
    CONFIGURED,

    /**
     * The same system as [CONFIGURED]; clauses read what actually **left the process**. Only a binding that drives
     * the real channel reaches it: automatic capture, the scrub and the process account riding later events are
     * the reporting SDK's and the adapter's, and a double that imitated them would be licensed only by imitating.
     */
    CONFIGURED_ON_THE_WIRE,
}

/**
 * One event as it left the process, in the contract's own vocabulary — the binding maps the channel's wire format
 * onto it, so no clause names the reporting SDK.
 */
class DeliveredEvent(
    /** The event's message text, as transmitted. */
    val message: String?,
    /** The log lines that rode the event as breadcrumbs, as transmitted. */
    val breadcrumbs: List<String>,
    /** The reporting SDK's own per-install identifier, as transmitted. */
    val installId: String?,
    /** The process account [DiagnosticsReporter.describeProcess] attached, as transmitted; `null` if none rode. */
    val processAccount: Map<String, String>?,
    /** Whether this is an operator-initiated dump rather than an automatic event. */
    val isDump: Boolean,
)

/**
 * What a clause reads beyond the port (`docs/architecture.md`: "an outcome that leaves the process"): whether
 * the channel is running, and what has been delivered. Outcomes only — never which reporting call ran.
 */
interface DiagnosticsObservation {
    /** Whether the reporting channel is running in this process. */
    fun channelRunning(): Boolean

    /**
     * Everything delivered so far, in order, once [until] holds of it. Delivery takes an unbounded time, so this
     * waits in real time and throws [WaitExpired] (→ `NotWithin`) past a bounded deadline. A binding whose
     * deliveries are already complete when asked answers at once or throws at once.
     */
    fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent>
}

/** What a clause is handed: the port under contract and the handle it observes outcomes through. */
class DiagnosticsReporterSubject(val reporter: DiagnosticsReporter, val observe: DiagnosticsObservation)

/**
 * What reporting a process's diagnostics off-device promises (`docs/architecture.md` — this list IS the
 * specification of the port's obligations; capability `privacy-security` carries why each exists).
 *
 * - An unconfigured build is inert on every member: nothing starts, nothing is sent — even through
 *   [DiagnosticsReporter.describeProcess], whose guarantee to start the channel does not override that rule.
 * - [DiagnosticsReporter.start] is idempotent — the app process composes it twice, and a second start that
 *   re-registered the log writer would double every event.
 * - [DiagnosticsReporter.describeProcess] starts the channel itself: the first real attribution event went out
 *   with no process tag because it outran the composition that normally starts it (`SNAPSYNC-38`).
 * - Automatic capture rides the logging seam `start` installs: an `Error` line is one event, a `Warn` line is a
 *   breadcrumb on a later one, and every UUID-shaped token in either is redacted — except the SDK's own
 *   per-install id, the deliberate exception.
 * - The operator's dump is delivered verbatim, identifiers included.
 * - The latest process account supersedes the earlier one on every later event.
 * - Every event stays below the ingest's maximum size, so a refused one never blocks the queue: the worst-case dump
 *   (full log budget, a full set of over-long breadcrumbs) still arrives, and a later event arrives after it.
 *
 * **Not clauses**, each for want of a host (both stay documented on the adapter):
 * - that the account rides a crash delivered on a LATER launch — it needs a fatal event and a relaunch inside one
 *   clause;
 * - that an event reported after an early `describeProcess` names its process — the adapter reads the identity
 *   from the bundle, and the only host that runs this contract live, the simulator test executable, has none
 *   (measured 2026-09-23). A running channel is what `CONFIGURED_DESCRIBE_STARTS_CHANNEL` asserts, and `start`
 *   sets the identity before anything else can report.
 *
 * Negative outcomes ("exactly one", "nothing") are judged against a **sentinel**: after the stimulus the clause
 * logs a sentinel error and waits for it, then reads only what was delivered before it. Waiting a fixed time and
 * reading silence as absence is what this refuses — delivery was measured to stall up to 26 s.
 */
object DiagnosticsReporterContract :
    Contract<DiagnosticsReporterState, DiagnosticsReporterSubject>("DiagnosticsReporter") {

    /** A UUID-shaped token derived from [clauseId], so every input is deterministic. */
    fun uuidFor(clauseId: String): String {
        val hex = clauseId.encodeToByteArray().fold(0x811c9dc5.toInt()) { h, b -> (h xor b.toInt()) * 0x01000193 }
            .toUInt().toString(16).padStart(8, '0')
        return "$hex-${hex.take(4)}-4${hex.drop(1).take(3)}-8${hex.drop(4).take(3)}-$hex${hex.take(4)}"
    }

    fun reportFor(clauseId: String, generation: String): ProcessMetricReport =
        ProcessMetricReport(mapOf("clause" to clauseId, "generation" to generation, "only-in-$generation" to "1"))

    fun dumpFor(clauseId: String): DiagnosticDump = DiagnosticDump(
        note = "$clauseId: upload of ${uuidFor(clauseId)} never finished",
        state = mapOf("clause" to clauseId),
        ledger = mapOf("pending" to "1"),
        appLog = "$clauseId app log\n",
        extensionLog = "$clauseId extension log\n",
    )

    /**
     * The largest dump the app can compose: log tails filling [DIAGNOSTIC_LOG_BUDGET_BYTES] between them, a 100-char
     * non-ASCII event name (the backend's `MAX_EVENT_NAME_LENGTH`) and a long note. The log lines are escape-heavy
     * (quotes and backslashes, as request and path lines carry), so JSON escaping is measured rather than assumed.
     */
    fun worstCaseDumpFor(clauseId: String): DiagnosticDump {
        val line = "12:00:00.000 [process] GET https://edge/events/\"a\"/devices?x=\"\\\" → 200 in 12 ms\n"
        fun tail(bytes: Int): String {
            val whole = line.repeat(bytes / line.encodeToByteArray().size)
            return whole + "x".repeat(bytes - whole.encodeToByteArray().size)
        }
        return DiagnosticDump(
            note = "$clauseId: " + "the upload never finished and nothing on screen said why. ".repeat(4),
            state = mapOf("clause" to clauseId, "event_name" to "ü".repeat(100)),
            ledger = mapOf("pending" to "1"),
            appLog = tail(DIAGNOSTIC_LOG_BUDGET_BYTES / 2),
            extensionLog = tail(DIAGNOSTIC_LOG_BUDGET_BYTES / 2),
        )
    }

    private fun sentinel(clauseId: String) = "sentinel $clauseId"

    /** Logs the sentinel, waits for it, and answers everything delivered strictly before it. */
    private fun DiagnosticsObservation.deliveredBefore(sentinel: String): List<DeliveredEvent> {
        Logger.e(sentinel)
        val all = delivered { events -> events.any { it.message == sentinel } }
        return all.takeWhile { it.message != sentinel }
    }

    private fun DiagnosticsObservation.sentinelEvent(sentinel: String): DeliveredEvent =
        delivered { events -> events.any { it.message == sentinel } }.first { it.message == sentinel }

    override val clauses = clauses {

        // ---- UNCONFIGURED: inert on every member -------------------------------------------------------------

        clause("UNCONFIGURED_IS_NOT_CONFIGURED", DiagnosticsReporterState.UNCONFIGURED) { s ->
            assertFalse(s.reporter.isConfigured, "a build with no reporting configuration says so")
        }

        clause("UNCONFIGURED_START_IS_INERT", DiagnosticsReporterState.UNCONFIGURED) { s ->
            s.reporter.start()
            s.reporter.start()
            assertFalse(s.observe.channelRunning(), "an unconfigured start runs no channel")
        }

        clause("UNCONFIGURED_SEND_IS_INERT", DiagnosticsReporterState.UNCONFIGURED) { s ->
            s.reporter.send(dumpFor("UNCONFIGURED_SEND_IS_INERT"))
            assertFalse(s.observe.channelRunning(), "an unconfigured send runs no channel")
        }

        clause("UNCONFIGURED_DESCRIBE_IS_INERT", DiagnosticsReporterState.UNCONFIGURED) { s ->
            s.reporter.describeProcess(reportFor("UNCONFIGURED_DESCRIBE_IS_INERT", "first"))
            assertFalse(
                s.observe.channelRunning(),
                "describing starts the channel only where there is one — the no-op rule wins",
            )
        }

        // ---- CONFIGURED: the port's surface and whether the channel runs -------------------------------------

        clause("CONFIGURED_IS_CONFIGURED", DiagnosticsReporterState.CONFIGURED) { s ->
            assertTrue(s.reporter.isConfigured, "a build with a destination says so")
            assertFalse(s.observe.channelRunning(), "and nothing runs until something asks")
        }

        clause("CONFIGURED_START_RUNS_CHANNEL", DiagnosticsReporterState.CONFIGURED) { s ->
            s.reporter.start()
            s.reporter.start()
            assertTrue(s.observe.channelRunning(), "a configured start runs the channel, and a second keeps it")
        }

        clause("CONFIGURED_DESCRIBE_STARTS_CHANNEL", DiagnosticsReporterState.CONFIGURED) { s ->
            s.reporter.describeProcess(reportFor("CONFIGURED_DESCRIBE_STARTS_CHANNEL", "first"))
            assertTrue(
                s.observe.channelRunning(),
                "describing must start the channel itself — a caller that outran the composition would otherwise " +
                    "reach it before start had run (SNAPSYNC-38)",
            )
        }

        clause("CONFIGURED_DUMP_IS_DELIVERED_VERBATIM", DiagnosticsReporterState.CONFIGURED) { s ->
            val dump = dumpFor("CONFIGURED_DUMP_IS_DELIVERED_VERBATIM")
            s.reporter.start()
            s.reporter.send(dump)
            val delivered = s.observe.delivered { events -> events.any { it.isDump } }.filter { it.isDump }
            assertEquals(1, delivered.size, "one dump sent, one delivered")
            val message = assertNotNull(delivered.single().message)
            assertTrue(
                dump.note in message,
                "the operator's note — and the identifier it quotes — arrives unredacted: '$message'",
            )
        }

        // ---- CONFIGURED_ON_THE_WIRE: what actually left the process ------------------------------------------

        clause("WIRE_ERROR_LOG_IS_ONE_EVENT", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            val line = "WIRE_ERROR_LOG_IS_ONE_EVENT failed"
            s.reporter.start()
            s.reporter.start()
            Logger.e(line)
            val before = s.observe.deliveredBefore(sentinel("WIRE_ERROR_LOG_IS_ONE_EVENT"))
            assertEquals(
                1,
                before.count { it.message == line },
                "one error line is one event — a second start that re-registered the writer would double it",
            )
        }

        clause("WIRE_WARNING_IS_NOT_AN_EVENT", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            val line = "WIRE_WARNING_IS_NOT_AN_EVENT retrying"
            s.reporter.start()
            Logger.w(line)
            val sentinel = sentinel("WIRE_WARNING_IS_NOT_AN_EVENT")
            assertTrue(s.observe.deliveredBefore(sentinel).isEmpty(), "a warning alone transmits nothing")
            assertTrue(
                s.observe.sentinelEvent(sentinel).breadcrumbs.any { line in it },
                "it rides the next event as a breadcrumb",
            )
        }

        clause("WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            val id = uuidFor("WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED")
            s.reporter.start()
            Logger.i("enumerating $id")
            Logger.e("reconcile($id) failed")
            val event = s.observe.delivered { events -> events.any { it.message?.startsWith("reconcile(") == true } }
                .first { it.message?.startsWith("reconcile(") == true }
            assertFalse(id in event.message.orEmpty(), "an eventId IS the upload capability: '${event.message}'")
            assertTrue(event.breadcrumbs.any { it.contains("enumerating") }, "the breadcrumb arrived")
            assertTrue(event.breadcrumbs.none { id in it }, "and carries no identifier either: ${event.breadcrumbs}")
        }

        clause("WIRE_INSTALL_ID_IS_KEPT", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            s.reporter.start()
            val sentinel = sentinel("WIRE_INSTALL_ID_IS_KEPT")
            Logger.e(sentinel)
            val installId = assertNotNull(
                s.observe.sentinelEvent(sentinel).installId,
                "an automatic event carries the SDK's per-install id — it powers affected-device counts",
            )
            assertTrue(
                UUID_SHAPED.matches(installId),
                "and it is the one identifier the scrub lets through, intact: '$installId'",
            )
        }

        clause("WIRE_WORST_CASE_DUMP_ARRIVES", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            s.reporter.start()
            // A full set of breadcrumbs, each far over the cap: the breadcrumb row of the whole-event sum at its worst.
            repeat(MAX_BREADCRUMBS) { i -> Logger.w("WIRE_WORST_CASE_DUMP_ARRIVES crumb $i " + "\"q\\".repeat(2_000)) }
            s.reporter.send(worstCaseDumpFor("WIRE_WORST_CASE_DUMP_ARRIVES"))
            // The sentinel is sent after the dump. An ingest refusal would leave the dump at the head of the queue,
            // so the sentinel would never arrive either: this is the "nothing is left stuck" half.
            val before = s.observe.deliveredBefore(sentinel("WIRE_WORST_CASE_DUMP_ARRIVES"))
            val dump = before.singleOrNull { it.isDump }
            assertNotNull(dump, "the worst-case dump arrived below the ingest's maximum event size")
            val crumbs = dump.breadcrumbs.filter { "WIRE_WORST_CASE_DUMP_ARRIVES crumb" in it }
            assertTrue(crumbs.isNotEmpty(), "the over-long lines rode the dump as breadcrumbs")
            assertTrue(
                crumbs.all { it.encodeToByteArray().size <= BREADCRUMB_TEXT_BYTES && "…[+" in it },
                "each over-long breadcrumb arrives capped and says it was cut",
            )
        }

        clause("WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS", DiagnosticsReporterState.CONFIGURED_ON_THE_WIRE) { s ->
            val first = reportFor("WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS", "first")
            val second = reportFor("WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS", "second")
            s.reporter.describeProcess(first)
            s.reporter.describeProcess(second)
            val sentinel = sentinel("WIRE_LATEST_ACCOUNT_RIDES_LATER_EVENTS")
            Logger.e(sentinel)
            assertEquals(
                second.fields,
                s.observe.sentinelEvent(sentinel).processAccount,
                "the latest account rides the event, and nothing of the one it superseded",
            )
        }
    }

    private val UUID_SHAPED = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
