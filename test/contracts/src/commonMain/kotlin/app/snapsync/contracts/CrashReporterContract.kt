package app.snapsync.contracts

import app.snapsync.model.BREADCRUMB_TEXT_BYTES
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashLevel
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DumpResult
import app.snapsync.model.MAX_BREADCRUMBS
import app.snapsync.model.diagnosticDumpEvent
import app.snapsync.model.scrubbedCrumb
import app.snapsync.model.scrubbedEvent
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states the channel behind a [CrashReporter] can be found in, as far as a clause cares. */
enum class CrashReporterState {
    /** A fresh adapter: nothing has started it. */
    NOT_STARTED,

    /** The same system, to be started by the clause. Clauses read the port's answers and whether the channel runs. */
    STARTED,

    /**
     * The same system as [STARTED]; clauses read what actually **left the process**. Only a binding that drives the
     * real channel reaches it: breadcrumbs riding the next event, the handlers applied on the way out and the SDK's
     * own install id are the SDK's and the adapter's, and a double that imitated them would be licensed only by
     * imitating.
     */
    ON_THE_WIRE,
}

/**
 * One event as it left the process, in the contract's own vocabulary — the binding maps the channel's wire format onto
 * it, so no clause names the reporting SDK.
 */
class DeliveredEvent(
    /** The event's message text, as transmitted. */
    val message: String?,
    /** The breadcrumb messages that rode the event, as transmitted. */
    val breadcrumbs: List<String>,
    /** The reporting SDK's own per-install identifier, as transmitted. */
    val installId: String?,
    /** The event's tags, as transmitted. */
    val tags: Map<String, String>,
    /** The event's string-valued contexts, as transmitted. */
    val contexts: Map<String, Map<String, String>>,
    /** Whether the event carries an exception (a stack trace), rather than only a message. */
    val hasException: Boolean,
)

/**
 * What a clause reads beyond the port (`docs/architecture.md`: "an outcome that leaves the process"): whether the
 * channel is running, and what has been delivered. Outcomes only — never which reporting call ran.
 */
interface CrashObservation {
    /** Whether the reporting channel is running in this process. */
    fun channelRunning(): Boolean

    /**
     * Everything delivered so far, in order, once [until] holds of it. Delivery takes an unbounded time, so this waits
     * in real time and throws [WaitExpired] (→ `NotWithin`) past a bounded deadline.
     */
    fun delivered(until: (List<DeliveredEvent>) -> Boolean): List<DeliveredEvent>
}

/**
 * What a clause is handed: the port under contract — NOT yet listened to, since each clause registers the handlers it
 * needs — the options that start it against the binding's destination, and the handle it observes outcomes through.
 */
class CrashReporterSubject(val reporter: CrashReporter, val options: CrashOptions, val observe: CrashObservation)

/**
 * What the crash-reporting channel promises (`docs/architecture.md` — this list IS the specification of the port's
 * obligations; capability `privacy-security` carries why each exists).
 *
 * - Nothing runs until `start`: before it, every member changes nothing, and a dump answers `NotSent`.
 * - `start` is idempotent: a second start that re-initialised the channel would lose its scope.
 * - A captured event is one event; a breadcrumb is not an event and rides the next one.
 * - Every event and breadcrumb passes the registered handlers on the way out: what they return is what leaves, and
 *   `null` leaves nothing.
 * - An event's tags and its exception ride it.
 * - The latest context supersedes the earlier one on every later event.
 * - The SDK's own per-install id rides every event — the one identifier nothing scrubs.
 * - Under the production handlers (`model/Crash.kt`): an automatic event leaves redacted, the operator's dump leaves
 *   verbatim, and the worst-case dump — full log budget, a full set of over-long breadcrumbs — still arrives below
 *   the ingest's maximum size, so a refused one never blocks the queue.
 *
 * **Not clauses**, each for want of a host (both documented on the adapter): that a context rides a crash delivered on
 * a LATER launch, and the `process` tag (the simulator test executable has no bundle identifier).
 *
 * Negative outcomes ("exactly one", "nothing") are judged against a **sentinel**: after the stimulus the clause
 * captures a sentinel event and waits for it, then reads only what was delivered before it. Waiting a fixed time and
 * reading silence as absence is what this refuses — delivery was measured to stall up to 26 s.
 */
object CrashReporterContract : Contract<CrashReporterState, CrashReporterSubject>("CrashReporter") {

    /** A UUID-shaped token derived from [clauseId], so every input is deterministic. */
    fun uuidFor(clauseId: String): String {
        val hex = clauseId.encodeToByteArray().fold(0x811c9dc5.toInt()) { h, b -> (h xor b.toInt()) * 0x01000193 }
            .toUInt().toString(16).padStart(8, '0')
        return "$hex-${hex.take(4)}-4${hex.drop(1).take(3)}-8${hex.drop(4).take(3)}-$hex${hex.take(4)}"
    }

    /** Handlers that let everything through as it is — for the clauses about the channel, not about shaping. */
    val PASS_THROUGH = CrashHandlers(onEvent = { it }, onBreadcrumb = { it })

    /** The handlers production registers (`CrashReporting`): the pure rules of `model/Crash.kt`. */
    val PRODUCTION = CrashHandlers(onEvent = ::scrubbedEvent, onBreadcrumb = ::scrubbedCrumb)

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

    private fun CrashReporterSubject.startListening(handlers: CrashHandlers = PASS_THROUGH) {
        reporter.listen(handlers)
        reporter.start(options)
    }

    /** Captures the sentinel, waits for it, and answers everything delivered strictly before it. */
    private fun CrashReporterSubject.deliveredBefore(sentinel: String): List<DeliveredEvent> {
        reporter.capture(CrashEvent(message = sentinel))
        val all = observe.delivered { events -> events.any { it.message == sentinel } }
        return all.takeWhile { it.message != sentinel }
    }

    private fun CrashReporterSubject.sentinelEvent(sentinel: String): DeliveredEvent {
        reporter.capture(CrashEvent(message = sentinel))
        return observe.delivered { events -> events.any { it.message == sentinel } }.first { it.message == sentinel }
    }

    override val clauses = clauses {

        // ---- NOT_STARTED: inert on every member ----------------------------------------------------------------

        clause("NOT_STARTED_IS_INERT", CrashReporterState.NOT_STARTED) { s ->
            s.reporter.listen(PASS_THROUGH)
            s.reporter.capture(CrashEvent(message = "NOT_STARTED_IS_INERT"))
            s.reporter.breadcrumb(Crumb(CrashLevel.INFO, "NOT_STARTED_IS_INERT"))
            s.reporter.setContext("clause", mapOf("id" to "NOT_STARTED_IS_INERT"))
            assertIs<DumpResult.NotSent>(
                s.reporter.sendDump(diagnosticDumpEvent(dumpFor("NOT_STARTED_IS_INERT"))),
                "a dump before start says it was not sent",
            )
            assertFalse(s.observe.channelRunning(), "nothing but start runs the channel")
        }

        // ---- STARTED: the port's answers and whether the channel runs ------------------------------------------

        clause("STARTED_START_RUNS_CHANNEL", CrashReporterState.STARTED) { s ->
            s.startListening()
            s.reporter.start(s.options)
            assertTrue(s.observe.channelRunning(), "a start runs the channel, and a second keeps it running")
        }

        clause("STARTED_DUMP_IS_QUEUED", CrashReporterState.STARTED) { s ->
            s.startListening()
            assertEquals(
                DumpResult.Queued,
                s.reporter.sendDump(diagnosticDumpEvent(dumpFor("STARTED_DUMP_IS_QUEUED"))),
                "a running channel takes the dump — which does not say it left the device",
            )
        }

        // ---- ON_THE_WIRE: what actually left the process -------------------------------------------------------

        clause("WIRE_CAPTURE_IS_ONE_EVENT", CrashReporterState.ON_THE_WIRE) { s ->
            val line = "WIRE_CAPTURE_IS_ONE_EVENT failed"
            s.startListening()
            s.reporter.start(s.options)
            s.reporter.capture(CrashEvent(message = line))
            assertEquals(
                1,
                s.deliveredBefore(sentinel("WIRE_CAPTURE_IS_ONE_EVENT")).count { it.message == line },
                "one capture is one event — a second start must not double anything",
            )
        }

        clause("WIRE_BREADCRUMB_IS_NOT_AN_EVENT", CrashReporterState.ON_THE_WIRE) { s ->
            val line = "WIRE_BREADCRUMB_IS_NOT_AN_EVENT retrying"
            s.startListening()
            s.reporter.breadcrumb(Crumb(CrashLevel.WARNING, line, "engine"))
            val sentinel = sentinel("WIRE_BREADCRUMB_IS_NOT_AN_EVENT")
            assertTrue(s.deliveredBefore(sentinel).isEmpty(), "a breadcrumb alone transmits nothing")
            assertTrue(
                s.observe.delivered { e -> e.any { it.message == sentinel } }.first { it.message == sentinel }
                    .breadcrumbs.any { line in it },
                "it rides the next event",
            )
        }

        clause("WIRE_HANDLERS_SHAPE_WHAT_LEAVES", CrashReporterState.ON_THE_WIRE) { s ->
            s.startListening(
                CrashHandlers(
                    // Both fields: a captured message's text reaches the handler as the SDK's rendering, `formatted`.
                    onEvent = { e ->
                        e.copy(message = e.message?.let { "$it (shaped)" }, formatted = e.formatted?.let { "$it (shaped)" })
                    },
                    onBreadcrumb = { c -> c.copy(message = c.message?.let { "shaped: $it" }) },
                ),
            )
            s.reporter.breadcrumb(Crumb(CrashLevel.INFO, "WIRE_HANDLERS_SHAPE_WHAT_LEAVES crumb"))
            s.reporter.capture(CrashEvent(message = "WIRE_HANDLERS_SHAPE_WHAT_LEAVES"))
            val event = s.observe.delivered { e -> e.any { it.message?.startsWith("WIRE_HANDLERS") == true } }
                .first { it.message?.startsWith("WIRE_HANDLERS") == true }
            assertEquals("WIRE_HANDLERS_SHAPE_WHAT_LEAVES (shaped)", event.message, "the event leaves as the handler shaped it")
            assertTrue(
                event.breadcrumbs.any { it.startsWith("shaped: WIRE_HANDLERS_SHAPE_WHAT_LEAVES crumb") },
                "and so does its breadcrumb: ${event.breadcrumbs}",
            )
        }

        clause("WIRE_A_DROPPED_EVENT_LEAVES_NOTHING", CrashReporterState.ON_THE_WIRE) { s ->
            val dropped = "WIRE_A_DROPPED_EVENT_LEAVES_NOTHING drop me"
            s.startListening(
                CrashHandlers(onEvent = { e -> e.takeIf { (it.formatted ?: it.message) != dropped } }, onBreadcrumb = { it }),
            )
            s.reporter.capture(CrashEvent(message = dropped))
            assertTrue(
                s.deliveredBefore(sentinel("WIRE_A_DROPPED_EVENT_LEAVES_NOTHING")).none { it.message == dropped },
                "a handler's null leaves nothing",
            )
        }

        clause("WIRE_TAGS_AND_EXCEPTION_RIDE_THE_EVENT", CrashReporterState.ON_THE_WIRE) { s ->
            s.startListening()
            s.reporter.capture(
                CrashEvent(
                    throwable = IllegalStateException("WIRE_TAGS_AND_EXCEPTION_RIDE_THE_EVENT"),
                    tags = mapOf("entry_point" to "WIRE_TAGS_AND_EXCEPTION_RIDE_THE_EVENT"),
                ),
            )
            val event = s.observe.delivered { e -> e.any { it.hasException } }.first { it.hasException }
            assertEquals("WIRE_TAGS_AND_EXCEPTION_RIDE_THE_EVENT", event.tags["entry_point"], "the tag rides the event")
        }

        clause("WIRE_INSTALL_ID_IS_KEPT", CrashReporterState.ON_THE_WIRE) { s ->
            s.startListening(PRODUCTION)
            val installId = assertNotNull(
                s.sentinelEvent(sentinel("WIRE_INSTALL_ID_IS_KEPT")).installId,
                "an event carries the SDK's per-install id — it powers affected-device counts",
            )
            assertTrue(
                UUID_SHAPED.matches(installId),
                "and it is the one identifier the production scrub lets through, intact: '$installId'",
            )
        }

        clause("WIRE_LATEST_CONTEXT_RIDES_LATER_EVENTS", CrashReporterState.ON_THE_WIRE) { s ->
            s.startListening()
            s.reporter.setContext("process_metrics", mapOf("generation" to "first", "only-in-first" to "1"))
            s.reporter.setContext("process_metrics", mapOf("generation" to "second", "only-in-second" to "1"))
            assertEquals(
                mapOf("generation" to "second", "only-in-second" to "1"),
                s.sentinelEvent(sentinel("WIRE_LATEST_CONTEXT_RIDES_LATER_EVENTS")).contexts["process_metrics"],
                "the latest context rides the event, and nothing of the one it superseded",
            )
        }

        clause("WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED", CrashReporterState.ON_THE_WIRE) { s ->
            val id = uuidFor("WIRE_AUTOMATIC_EVENTS_ARE_SCRUBBED")
            s.startListening(PRODUCTION)
            s.reporter.breadcrumb(Crumb(CrashLevel.INFO, "enumerating $id"))
            s.reporter.capture(CrashEvent(message = "reconcile($id) failed"))
            val event = s.observe.delivered { e -> e.any { it.message?.startsWith("reconcile(") == true } }
                .first { it.message?.startsWith("reconcile(") == true }
            assertFalse(id in event.message.orEmpty(), "an eventId IS the upload capability: '${event.message}'")
            assertTrue(event.breadcrumbs.any { it.contains("enumerating") }, "the breadcrumb arrived")
            assertTrue(event.breadcrumbs.none { id in it }, "and carries no identifier either: ${event.breadcrumbs}")
        }

        clause("WIRE_DUMP_IS_DELIVERED_VERBATIM", CrashReporterState.ON_THE_WIRE) { s ->
            val dump = dumpFor("WIRE_DUMP_IS_DELIVERED_VERBATIM")
            s.startListening(PRODUCTION)
            s.reporter.sendDump(diagnosticDumpEvent(dump))
            val delivered = s.observe.delivered { e -> e.any { it.contexts.containsKey("note") } }
                .filter { it.contexts.containsKey("note") }
            assertEquals(1, delivered.size, "one dump sent, one delivered")
            val message = assertNotNull(delivered.single().message)
            assertTrue(dump.note in message, "the operator's note — and the id it quotes — arrives unredacted: '$message'")
            assertEquals(dump.state, delivered.single().contexts["state"], "and its sections ride as contexts")
        }

        clause("WIRE_WORST_CASE_DUMP_ARRIVES", CrashReporterState.ON_THE_WIRE) { s ->
            s.startListening(PRODUCTION)
            // A full set of breadcrumbs, each far over the cap: the breadcrumb row of the whole-event sum at its worst.
            repeat(MAX_BREADCRUMBS) { i ->
                s.reporter.breadcrumb(Crumb(CrashLevel.WARNING, "WIRE_WORST_CASE_DUMP_ARRIVES crumb $i " + "\"q\\".repeat(2_000)))
            }
            s.reporter.sendDump(diagnosticDumpEvent(worstCaseDumpFor("WIRE_WORST_CASE_DUMP_ARRIVES")))
            // The sentinel is sent after the dump. An ingest refusal would leave the dump at the head of the queue, so
            // the sentinel would never arrive either: this is the "nothing is left stuck" half.
            val before = s.deliveredBefore(sentinel("WIRE_WORST_CASE_DUMP_ARRIVES"))
            val dump = before.singleOrNull { it.contexts.containsKey("note") }
            assertNotNull(dump, "the worst-case dump arrived below the ingest's maximum event size")
            val crumbs = dump.breadcrumbs.filter { "WIRE_WORST_CASE_DUMP_ARRIVES crumb" in it }
            assertTrue(crumbs.isNotEmpty(), "the over-long lines rode the dump as breadcrumbs")
            assertTrue(
                crumbs.all { it.encodeToByteArray().size <= BREADCRUMB_TEXT_BYTES && "…[+" in it },
                "each over-long breadcrumb arrives capped and says it was cut",
            )
        }
    }

    private val UUID_SHAPED = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
