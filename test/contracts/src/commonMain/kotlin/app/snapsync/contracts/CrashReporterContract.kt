package app.snapsync.contracts

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashLevel
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.CrashTags
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.crash.EventBounds
import app.snapsync.domain.crash.ReportedFailure
import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.event.EventId
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.domain.report.Fact
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
     * The same system as [STARTED]; clauses read what the reporter's backend **received** of the port's calls — one
     * event per capture, its message, tags and exception, the latest context of each name riding it, the shaping's
     * filing and grouping of an event, a dump as it was sent. A binding reaches it by reproducing those observables, so
     * a double that records what the port was handed, in order, is licensed here by the same clauses the real channel
     * passes; what only the SDK and the adapter do on the way out is [ON_THE_WIRE]'s.
     */
    RECEIVED,

    /**
     * The same system as [STARTED]; clauses read what actually **left the process** that only the reporting SDK and the
     * adapter decide. Only a binding that drives the real channel reaches it: breadcrumbs riding the next event, the
     * shaping applied on the way out — to the SDK's own breadcrumbs too — the build facts, the SDK's own install id and
     * the size an event arrives at are the SDK's and the adapter's, and a double that imitated them would be licensed
     * only by imitating.
     */
    ON_THE_WIRE,

    /**
     * As [ON_THE_WIRE], except that the destination is unreachable until the channel is closed and started again in
     * the same process ([CrashReporterSubject.restart]) — a report captured before it is kept and delivered after,
     * the way a crash is delivered on the next launch. Only a binding that can close the channel keeping what it had
     * not delivered reaches it.
     */
    ACROSS_A_RESTART,

    /**
     * As [ON_THE_WIRE], in the process that starts after the app's processes ENDED on an uncaught `InvalidProgramState`
     * ([CrashReporterSubject.death]) — the next launch, for real: the channel was never started in this process before
     * the clause starts it, so what it delivers is what the dead processes left. Only a binding that can run and end
     * processes of the app around one that runs the clause reaches it.
     */
    AFTER_A_PROCESS_DEATH,
}

/**
 * How an `InvalidProgramState` reaches the point where it ends the process (`docs/patterns.md`, "Invalid program
 * state") — each a road the production code takes, never a stand-in for one.
 */
enum class FatalRoute {
    /** Work nobody awaits: thrown in the process runtime's scope, whose handler ends the process. */
    UNAWAITED_WORK,

    /** A delivery the platform makes and waits on: thrown while the runtime answers it on the delivering thread. */
    PLATFORM_DELIVERY,

    /** A block or delegate method Objective-C calls into Kotlin through `objcBoundary` (iOS). */
    OBJC_CALLBACK,
}

/**
 * The app's processes, as far as a clause in [CrashReporterState.AFTER_A_PROCESS_DEATH] can end them: each [endOn] runs
 * one with the channel started against the clause's destination under the production shaping, until an
 * `InvalidProgramState` whose `because` is [becauseOf] its route reaches that route — and returns once that process has
 * ended.
 */
interface ProcessDeath {
    /** The routes this host's processes take. */
    val routes: List<FatalRoute>

    fun endOn(route: FatalRoute)
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
    /** The build's version line the event names, as transmitted. */
    val release: String? = null,
    /** The deployment the event is filed under, as transmitted. */
    val environment: String? = null,
    /** The build number the event names, as transmitted. */
    val dist: String? = null,
    /** The text of each exception the event carries, as transmitted — a thrown exception's message. */
    val exceptionValues: List<String> = emptyList(),
    /** What the event groups under, as transmitted — empty for the backend's own grouping. */
    val fingerprint: List<String> = emptyList(),
)

/**
 * A channel its binding can close and start again in the same process, keeping what it had not delivered — a
 * relaunch, as far as the channel can tell ([CrashReporterState.ACROSS_A_RESTART]).
 */
fun interface CrashRestart {
    /**
     * Closes the channel, keeping its undelivered reports, makes the destination reachable, and answers a fresh
     * reporter for this process — not yet started.
     */
    fun relaunch(): CrashReporter
}

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
 * What a clause is handed: the port under contract — NOT yet started — the options that start it against the binding's
 * destination, and the handle it observes outcomes through.
 */
class CrashReporterSubject(
    val reporter: CrashReporter,
    val options: CrashOptions,
    val observe: CrashObservation,
    /** How to relaunch the channel — handed only in [CrashReporterState.ACROSS_A_RESTART]. */
    val restart: CrashRestart? = null,
    /** How to end the app's processes — handed only in [CrashReporterState.AFTER_A_PROCESS_DEATH]. */
    val death: ProcessDeath? = null,
)

/**
 * What the crash-reporting channel promises (`docs/architecture.md` — this list IS the specification of the port's
 * obligations).
 *
 * - Nothing runs until `start`: before it, every member changes nothing, and a dump answers `NotSent`.
 * - `start` is idempotent: a second start that re-initialised the channel would lose its scope.
 * - A captured event is one event; a breadcrumb is not an event and rides the next one.
 * - Every event and breadcrumb leaves as the shaping it was started with shapes it — an event's release, build number,
 *   tags and fingerprint included.
 * - A failure captured as a `ReportedFailure` leaves titled by its line, the failure it reports chained beneath it.
 * - An event's tags and its exception ride it.
 * - The build facts `start` is given — release, environment, build number, and the platform and process tags — ride
 *   every event.
 * - A build number `start` is given is the one a report was CAPTURED under, even when it is delivered after a
 *   relaunch as another build — which is why a platform whose SDK re-stamps at send (iOS) is given none.
 * - The latest context supersedes the earlier one on every later event.
 * - The SDK's own per-install id rides every event.
 * - Under the production shaping (`CrashShaping.STANDARD`): an event and its breadcrumbs leave their ids in full, a
 *   report leaves verbatim, and the worst-case report — full log budget, a full set of over-long breadcrumbs — still
 *   arrives below the ingest's maximum size, so a refused one never blocks the queue.
 *
 * - A process that ends on an uncaught `InvalidProgramState` — whatever road it took there — leaves a crash report the
 *   next start delivers, its `because` intact through the production shaping
 *   ([CrashReporterState.AFTER_A_PROCESS_DEATH]: a real process death and a real next launch).
 *
 * **Not a clause**: that a CONTEXT set before such a death rides its report — the death clause asserts the report and
 * its guarantee, and [CrashReporterState.ACROSS_A_RESTART]'s clauses what a channel restart keeps.
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

    /** The shaping production starts every channel with — the only one there is. */
    private val SHAPING = CrashShaping.STANDARD

    /**
     * The guarantee a process ended on [route] names — one literal per route, as every `InvalidProgramState`'s is, so
     * each report is told apart by its text alone.
     */
    fun becauseOf(route: FatalRoute): String = when (route) {
        FatalRoute.UNAWAITED_WORK -> "the contract's unawaited work is guaranteed never to fail"
        FatalRoute.PLATFORM_DELIVERY -> "the contract's delivery is guaranteed never to fail"
        FatalRoute.OBJC_CALLBACK -> "the contract's callback is guaranteed never to fail"
    }

    fun dumpFor(clauseId: String): DiagnosticDump = DiagnosticDump(
        note = "$clauseId: upload of ${uuidFor(clauseId)} never finished",
        ids = ReportedIds(EventId.parse(uuidFor(clauseId)), Fact.Unsupported, "app.snapsync"),
        state = mapOf("clause" to clauseId),
        ledger = mapOf("pending" to "1"),
        appLog = "$clauseId app log\n",
        extensionLog = "$clauseId extension log\n",
    )

    /**
     * The largest report the app can compose: log tails filling [DiagnosticDump.LOG_BUDGET_BYTES] between them, a
     * 100-char non-ASCII event name (the backend's `MAX_EVENT_NAME_LENGTH`) and a long note. The log lines are
     * escape-heavy (quotes and backslashes, as request and path lines carry), so JSON escaping is measured rather than
     * assumed.
     */
    fun worstCaseDumpFor(clauseId: String): DiagnosticDump {
        val line = "12:00:00.000 [process] GET https://edge/events/\"a\"/devices?x=\"\\\" → 200 in 12 ms\n"
        fun tail(bytes: Int): String {
            val whole = line.repeat(bytes / line.encodeToByteArray().size)
            return whole + "x".repeat(bytes - whole.encodeToByteArray().size)
        }
        return DiagnosticDump(
            note = "$clauseId: " + "the upload never finished and nothing on screen said why. ".repeat(4),
            // The ids at their longest: an event, a device that could not be read (its reason at its bound), and the
            // extension's process name.
            ids = ReportedIds(
                EventId.parse(uuidFor(clauseId)),
                Fact.Failed("r".repeat(Fact.FAILURE_REASON_CHARS)),
                "app.snapsync.BackgroundUpload",
            ),
            state = mapOf("clause" to clauseId, "event_name" to "ü".repeat(100)),
            ledger = mapOf("pending" to "1"),
            appLog = tail(DiagnosticDump.LOG_BUDGET_BYTES / 2),
            extensionLog = tail(DiagnosticDump.LOG_BUDGET_BYTES / 2),
        )
    }

    private fun sentinel(clauseId: String) = "sentinel $clauseId"

    private fun CrashReporterSubject.start(options: CrashOptions = this.options) = reporter.start(options, SHAPING)

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

    /** The first delivered event whose message starts with [prefix]. */
    private fun CrashReporterSubject.firstStartingWith(prefix: String): DeliveredEvent =
        observe.delivered { e -> e.any { it.message?.startsWith(prefix) == true } }
            .first { it.message?.startsWith(prefix) == true }

    /** An event naming the build it describes and what it groups under, as a process-metric crossing does. */
    private fun described(message: String) = CrashEvent(
        message = message,
        tags = mapOf(
            CrashTags.DESCRIBED_RELEASE to "described-release",
            CrashTags.DESCRIBED_BUILD to "described-build",
            CrashTags.FINGERPRINT to "contract: $message",
            "kept" to "1",
        ),
    )

    private fun assertFiledAndGrouped(event: DeliveredEvent, message: String) {
        assertEquals("described-release", event.release, "the release it describes wins over the options'")
        assertEquals("described-build", event.dist, "and so does the build number")
        assertEquals(listOf("contract: $message"), event.fingerprint, "it groups under what it was captured with")
        assertEquals("1", event.tags["kept"], "an ordinary tag rides it: ${event.tags}")
        listOf(CrashTags.DESCRIBED_RELEASE, CrashTags.DESCRIBED_BUILD, CrashTags.FINGERPRINT).forEach {
            assertFalse(it in event.tags, "a tag that only carries a filing never leaves as one: ${event.tags}")
        }
    }

    override val clauses = clauses {

        // ---- NOT_STARTED: inert on every member ----------------------------------------------------------------

        clause(
            "NOT_STARTED_IS_INERT",
            CrashReporterState.NOT_STARTED,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::capture).returns()
                    answers(CrashReporter::breadcrumb).returns()
                    answers(CrashReporter::setContext).returns()
                    answers(CrashReporter::sendDump).with(DumpResult.NotSent::class)
                }
            },
        ) { s ->
            s.reporter.capture(CrashEvent(message = "NOT_STARTED_IS_INERT"))
            s.reporter.breadcrumb(Crumb(CrashLevel.INFO, "NOT_STARTED_IS_INERT"))
            s.reporter.setContext("clause", mapOf("id" to "NOT_STARTED_IS_INERT"))
            assertIs<DumpResult.NotSent>(
                s.reporter.sendDump(dumpFor("NOT_STARTED_IS_INERT").event()),
                "a dump before start says it was not sent",
            )
            assertFalse(s.observe.channelRunning(), "nothing but start runs the channel")
        }

        // ---- STARTED: the port's answers and whether the channel runs ------------------------------------------

        clause(
            "STARTED_START_RUNS_CHANNEL",
            CrashReporterState.STARTED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::start).returns()
            },
        ) { s ->
            s.start()
            s.start()
            assertTrue(s.observe.channelRunning(), "a start runs the channel, and a second keeps it running")
        }

        clause(
            "STARTED_DUMP_IS_QUEUED",
            CrashReporterState.STARTED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::sendDump).with(DumpResult.Queued::class)
            },
        ) { s ->
            s.start()
            assertEquals(
                DumpResult.Queued,
                s.reporter.sendDump(dumpFor("STARTED_DUMP_IS_QUEUED").event()),
                "a running channel takes the dump — which does not say it left the device",
            )
        }

        // ---- RECEIVED and ON_THE_WIRE: what actually left the process --------------------------------------------

        clause(
            "RECEIVED_CAPTURE_IS_ONE_EVENT",
            CrashReporterState.RECEIVED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::capture).returns()
            },
        ) { s ->
            val line = "RECEIVED_CAPTURE_IS_ONE_EVENT failed"
            s.start()
            s.start()
            s.reporter.capture(CrashEvent(message = line))
            assertEquals(
                1,
                s.deliveredBefore(sentinel("RECEIVED_CAPTURE_IS_ONE_EVENT")).count { it.message == line },
                "one capture is one event — a second start must not double anything",
            )
        }

        clause(
            "RECEIVED_THE_SHAPING_FILES_AND_GROUPS_THE_EVENT",
            CrashReporterState.RECEIVED,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::start).returns()
                    answers(CrashReporter::capture).returns()
                }
            },
        ) { s ->
            val message = "RECEIVED_THE_SHAPING_FILES_AND_GROUPS_THE_EVENT"
            s.start()
            s.reporter.capture(described(message))
            assertFiledAndGrouped(s.firstStartingWith(message), message)
        }

        clause(
            "WIRE_BREADCRUMB_IS_NOT_AN_EVENT",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::breadcrumb).returns()
            },
        ) { s ->
            val line = "WIRE_BREADCRUMB_IS_NOT_AN_EVENT retrying"
            s.start()
            s.reporter.breadcrumb(Crumb(CrashLevel.WARNING, line, "engine"))
            val sentinel = sentinel("WIRE_BREADCRUMB_IS_NOT_AN_EVENT")
            assertTrue(s.deliveredBefore(sentinel).isEmpty(), "a breadcrumb alone transmits nothing")
            assertTrue(
                s.observe.delivered { e -> e.any { it.message == sentinel } }.first { it.message == sentinel }
                    .breadcrumbs.any { line in it },
                "it rides the next event",
            )
        }

        clause(
            "WIRE_THE_SHAPING_IS_WHAT_LEAVES",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::start).returns()
                    answers(CrashReporter::capture).returns()
                    answers(CrashReporter::breadcrumb).returns()
                }
            },
        ) { s ->
            val message = "WIRE_THE_SHAPING_IS_WHAT_LEAVES"
            s.start()
            s.reporter.breadcrumb(
                Crumb(CrashLevel.INFO, "$message crumb " + "x".repeat(2 * EventBounds.BREADCRUMB_TEXT_BYTES)),
            )
            s.reporter.capture(described(message))
            val event = s.firstStartingWith(message)
            assertFiledAndGrouped(event, message)
            val crumb = event.breadcrumbs.single { it.startsWith("$message crumb") }
            assertTrue(
                crumb.encodeToByteArray().size <= EventBounds.BREADCRUMB_TEXT_BYTES && "…[+" in crumb,
                "and its breadcrumb arrives capped, saying it was cut: '$crumb'",
            )
        }

        clause(
            "WIRE_A_REPORTED_FAILURE_LEAVES_TITLED_BY_ITS_LINE",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::capture).returns()
            },
        ) { s ->
            val event = "WIRE_A_REPORTED_FAILURE_LEAVES_TITLED_BY_ITS_LINE could not open the database"
            val cause = "database version 15 newer than config version 13"
            s.start()
            s.reporter.capture(CrashEvent(throwable = ReportedFailure(event, IllegalStateException(cause))))
            val delivered = s.observe.delivered { e -> e.any { event in it.exceptionValues } }
                .first { event in it.exceptionValues }
            assertTrue(delivered.hasException, "a reported failure carries its stack")
            assertTrue(
                cause in delivered.exceptionValues,
                "the failure it reports rides beneath it, its own text intact: ${delivered.exceptionValues}",
            )
        }

        clause(
            "RECEIVED_TAGS_AND_EXCEPTION_RIDE_THE_EVENT",
            CrashReporterState.RECEIVED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::capture).returns()
            },
        ) { s ->
            s.start()
            s.reporter.capture(
                CrashEvent(
                    throwable = IllegalStateException("RECEIVED_TAGS_AND_EXCEPTION_RIDE_THE_EVENT"),
                    tags = mapOf("entry_point" to "RECEIVED_TAGS_AND_EXCEPTION_RIDE_THE_EVENT"),
                ),
            )
            val event = s.observe.delivered { e -> e.any { it.hasException } }.first { it.hasException }
            assertEquals(
                "RECEIVED_TAGS_AND_EXCEPTION_RIDE_THE_EVENT",
                event.tags["entry_point"],
                "the tag rides the event",
            )
        }

        clause(
            "WIRE_BUILD_FACTS_RIDE_THE_EVENT",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::start).returns()
            },
        ) { s ->
            s.start(
                CrashOptions(
                    dsn = s.options.dsn,
                    release = "9.9.contract",
                    environment = "contract",
                    dist = "990",
                    tags = mapOf(
                        CrashTags.PLATFORM to "contract-platform",
                        CrashTags.PROCESS to "app.snapsync.contract",
                    ),
                ),
            )
            val event = s.sentinelEvent(sentinel("WIRE_BUILD_FACTS_RIDE_THE_EVENT"))
            assertEquals("9.9.contract", event.release, "the release it was started with")
            assertEquals("contract", event.environment, "the environment it was started with")
            assertEquals("990", event.dist, "the build number it was started with")
            assertEquals("contract-platform", event.tags[CrashTags.PLATFORM], "the platform tag: ${event.tags}")
            assertEquals("app.snapsync.contract", event.tags[CrashTags.PROCESS], "the process tag: ${event.tags}")
        }

        // ---- AFTER_A_PROCESS_DEATH: what the next launch delivers -----------------------------------------------

        clause(
            "DEATH_INVALID_PROGRAM_STATE_IS_DELIVERED_ON_THE_NEXT_START",
            CrashReporterState.AFTER_A_PROCESS_DEATH,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::start).returns()
            },
        ) { s ->
            val death = assertNotNull(s.death, "the state hands the app's processes")
            assertTrue(death.routes.isNotEmpty(), "a host's processes take at least one road to the end")
            death.routes.forEach { death.endOn(it) }
            s.start()
            val expected = death.routes.map(::becauseOf)
            val events = s.observe.delivered { delivered ->
                expected.all { because -> delivered.any { because in it.exceptionValues } }
            }
            expected.forEach { because ->
                val report = events.first { because in it.exceptionValues }
                assertTrue(
                    report.hasException,
                    "the process that ended on \"$because\" is reported as the exception it threw, its stack with it",
                )
            }
        }

        // ---- ACROSS_A_RESTART: what a relaunch delivers ------------------------------------------------------------

        clause(
            "RESTART_CACHED_EVENT_KEEPS_ITS_BUILD",
            CrashReporterState.ACROSS_A_RESTART,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::start).returns()
                    answers(CrashReporter::capture).returns()
                }
            },
        ) { s ->
            val line = "RESTART_CACHED_EVENT_KEEPS_ITS_BUILD captured as build 1"
            s.start(CrashOptions(dsn = s.options.dsn, release = "9.9.contract", dist = "1"))
            s.reporter.capture(CrashEvent(message = line))
            val relaunched = assertNotNull(s.restart, "the state hands a restart").relaunch()
            relaunched.start(CrashOptions(dsn = s.options.dsn, release = "9.9.contract", dist = "2"), SHAPING)
            val event = s.observe.delivered { e -> e.any { it.message == line } }.first { it.message == line }
            assertEquals(
                "1",
                event.dist,
                "a report delivered after a relaunch as another build names the build it was captured under — the one " +
                    "whose symbols resolve its stack",
            )
        }

        clause(
            "WIRE_INSTALL_ID_IS_KEPT",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::capture).returns()
            },
        ) { s ->
            s.start()
            val installId = assertNotNull(
                s.sentinelEvent(sentinel("WIRE_INSTALL_ID_IS_KEPT")).installId,
                "an event carries the SDK's per-install id — it powers affected-device counts",
            )
            assertTrue(INSTALL_ID_SHAPED.matches(installId), "and it arrives intact: '$installId'")
        }

        clause(
            "RECEIVED_LATEST_CONTEXT_RIDES_LATER_EVENTS",
            CrashReporterState.RECEIVED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::setContext).returns()
            },
        ) { s ->
            s.start()
            s.reporter.setContext("process_metrics", mapOf("generation" to "first", "only-in-first" to "1"))
            s.reporter.setContext("process_metrics", mapOf("generation" to "second", "only-in-second" to "1"))
            assertEquals(
                mapOf("generation" to "second", "only-in-second" to "1"),
                s.sentinelEvent(sentinel("RECEIVED_LATEST_CONTEXT_RIDES_LATER_EVENTS")).contexts["process_metrics"],
                "the latest context rides the event, and nothing of the one it superseded",
            )
        }

        clause(
            "WIRE_AN_EVENT_LEAVES_ITS_IDS_IN_FULL",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::capture).returns()
                    answers(CrashReporter::breadcrumb).returns()
                }
            },
        ) { s ->
            val id = uuidFor("WIRE_AN_EVENT_LEAVES_ITS_IDS_IN_FULL")
            s.start()
            s.reporter.breadcrumb(Crumb(CrashLevel.INFO, "enumerating $id"))
            s.reporter.capture(CrashEvent(message = "reconcile($id) failed"))
            val event = s.firstStartingWith("reconcile(")
            assertEquals("reconcile($id) failed", event.message, "the ids SnapSync exchanges with its service ride it")
            assertTrue(event.breadcrumbs.any { "enumerating $id" in it }, "and its breadcrumbs: ${event.breadcrumbs}")
        }

        clause(
            "RECEIVED_DUMP_IS_DELIVERED_VERBATIM",
            CrashReporterState.RECEIVED,
            covers = cells {
                on<CrashReporter>().answers(CrashReporter::sendDump).with(DumpResult.Queued::class)
            },
        ) { s ->
            val dump = dumpFor("RECEIVED_DUMP_IS_DELIVERED_VERBATIM")
            s.start()
            s.reporter.sendDump(dump.event())
            val delivered = s.observe.delivered { e -> e.any { it.contexts.containsKey("note") } }
                .filter { it.contexts.containsKey("note") }
            assertEquals(1, delivered.size, "one dump sent, one delivered")
            val message = assertNotNull(delivered.single().message)
            assertTrue(dump.note in message, "the user's note — and the id it quotes — arrives as written: '$message'")
            assertEquals(dump.state, delivered.single().contexts["state"], "and its sections ride as contexts")
        }

        clause(
            "WIRE_WORST_CASE_DUMP_ARRIVES",
            CrashReporterState.ON_THE_WIRE,
            covers = cells {
                on<CrashReporter> {
                    answers(CrashReporter::sendDump).with(DumpResult.Queued::class)
                    answers(CrashReporter::breadcrumb).returns()
                }
            },
        ) { s ->
            s.start()
            // A full set of breadcrumbs, each far over the cap: the breadcrumb row of the whole-event sum at its worst.
            repeat(EventBounds.MAX_BREADCRUMBS) { i ->
                s.reporter.breadcrumb(
                    Crumb(CrashLevel.WARNING, "WIRE_WORST_CASE_DUMP_ARRIVES crumb $i " + "\"q\\".repeat(2_000)),
                )
            }
            s.reporter.sendDump(worstCaseDumpFor("WIRE_WORST_CASE_DUMP_ARRIVES").event())
            // The sentinel is sent after the dump. An ingest refusal would leave the dump at the head of the queue, so
            // the sentinel would never arrive either: this is the "nothing is left stuck" half.
            val before = s.deliveredBefore(sentinel("WIRE_WORST_CASE_DUMP_ARRIVES"))
            val dump = before.singleOrNull { it.contexts.containsKey("note") }
            assertNotNull(dump, "the worst-case dump arrived below the ingest's maximum event size")
            val crumbs = dump.breadcrumbs.filter { "WIRE_WORST_CASE_DUMP_ARRIVES crumb" in it }
            assertTrue(crumbs.isNotEmpty(), "the over-long lines rode the dump as breadcrumbs")
            assertTrue(
                crumbs.all { it.encodeToByteArray().size <= EventBounds.BREADCRUMB_TEXT_BYTES && "…[+" in it },
                "each over-long breadcrumb arrives capped and says it was cut",
            )
        }
    }

    /**
     * A random 128-bit id as a reporting SDK writes it: sentry-cocoa as a UUID, sentry-android as the same 32 hex
     * digits without the dashes (measured on the emulator, sentry-android 8.41.0).
     */
    private val INSTALL_ID_SHAPED = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}",
    )
}
