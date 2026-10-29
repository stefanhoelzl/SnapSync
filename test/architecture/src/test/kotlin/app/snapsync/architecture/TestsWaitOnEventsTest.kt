package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Tests wait on events, and the lane is the app's only source of timers** (`docs/testing.md`, "How a test waits";
 * `docs/architecture.md`, "Concurrency and failure").
 *
 * An integration test waits in exactly one way: on the event it cares about, from the JVM host's stream, with time the
 * host's loop moves. So its sources name no wall clock and no thread of their own, and the app code the host runs names
 * no dispatcher or thread the loop cannot see — or the loop's "settled" would lie and a negative would pass early.
 *
 * The waits hold with no exceptions: phase 3g-b removed the last poll and the polling helpers with it
 * (`DeletionLedgerTest`). A claim still has its list of classes not yet claiming, which may only shrink.
 */
class TestsWaitOnEventsTest {

    private val integration = SourceScan.kotlinFiles()
        .filter { it.path.startsWith("/test/integration/src/test/kotlin/") }
        .also { assertTrue(it.size > 10, "the integration suite's sources moved: ${it.size} files found") }

    @Test
    fun `every integration test verifies a requirement`() {
        val unclaimed = integration.filter { source -> unclaimedTests(source.text).isNotEmpty() }
            .map { it.file.nameWithoutExtension }.toSet() - NEVER_A_REQUIREMENT.keys
        assertEquals(
            UNCLAIMED.toSortedSet(),
            unclaimed.toSortedSet(),
            "an integration test with no @Verifies is a test of nothing the contract promises. A class leaves the list " +
                "once every test of it claims a requirement; no class joins it.",
        )
    }

    @Test
    fun `integration sources name no wall clock and no thread of their own`() {
        val waiting = integration.filter { source -> WALL_CLOCK.containsMatchIn(ZoneGates.stripComments(source.text)) }
            .map { it.file.nameWithoutExtension }.toSet()
        assertEquals(
            emptySet(),
            waiting.toSortedSet(),
            "an integration test waits on an event and moves time through the host's clock — never `delay`, " +
                "`Thread.sleep`, `withTimeout`, the system clock or a dispatcher of its own.",
        )
    }

    /**
     * A poll is a wait no event backs: it reads until a condition holds or a bound runs out, so it passes early on a
     * state the app is only passing through, and fails late on one it never reaches. The rule above never sees one — a
     * poll need name no clock — so this one names the polls the suite once had, should one return under its old name.
     * Every call counts, the support files' own included.
     */
    @Test
    fun `integration sources do not poll`() {
        val polling = integration.filter { source -> POLL.containsMatchIn(ZoneGates.stripComments(source.text)) }
            .map { it.file.nameWithoutExtension }.toSet()
        assertEquals(
            emptySet(),
            polling.toSortedSet(),
            "an integration test waits on the host's events (`behaviour { … }`, `RigClient.awaitScreen`) — never " +
                "`rigTest`, `awaitState`, `neverWithin` or `eventually`, which poll.",
        )
    }

    /**
     * The all-real journeys (`:test:journeys`) wait the same way — on the events the device host serves — and then read
     * the real system once; they claim requirements and read no log, as the suite does, with no list of exceptions. The
     * wall-clock rule is not theirs: a device runs on real time, the journeys date their events and photos from today,
     * and their bounds are the client's waits (`RigClient.awaitScreen`), not their own.
     */
    @Test
    fun `the journeys verify requirements, do not poll and read no log`() {
        val journeys = SourceScan.kotlinFiles()
            .filter { it.path.startsWith("/test/journeys/src/journeys/kotlin/") }
            .also { assertTrue(it.isNotEmpty(), "the journeys' sources moved") }
        fun breaking(rule: (String) -> Boolean) = journeys.filter { rule(it.text) }.map { it.file.nameWithoutExtension }
        assertEquals(
            emptyList(),
            breaking { unclaimedTests(it).isNotEmpty() },
            "a journey with no @Verifies is a journey of nothing the contract promises",
        )
        assertEquals(
            emptyList(),
            breaking { POLL.containsMatchIn(ZoneGates.stripComments(it)) },
            "a journey waits on the screen's events (`awaitScreen`), then reads the real system once — it never polls",
        )
        assertEquals(
            emptyList(),
            breaking { LOG_READ.containsMatchIn(ZoneGates.stripComments(it)) },
            "a journey observes the screen and what a system outside the app records — never the app's log",
        )
    }

    /**
     * Log text is no outcome a person observes (`docs/testing.md`, "The rules"): the host's log read (`HostLevers.logs`)
     * is an agent's, through the `rig` CLI, never a test's.
     */
    @Test
    fun `integration sources read no log text`() {
        val reading = integration.filter { source -> LOG_READ.containsMatchIn(ZoneGates.stripComments(source.text)) }
            .map { it.file.nameWithoutExtension }.toSet()
        assertEquals(
            emptySet(),
            reading.toSortedSet(),
            "an integration test observes the screen and what a system outside the app records — never the app's log.",
        )
    }

    @Test
    fun `the app code the JVM host runs names no dispatcher or thread`() {
        val scanned = SourceScan.kotlinFiles().filter { source -> APP_CODE.any { it.matches(source.path) } }
        assertTrue(scanned.size > 100, "the app's sources moved: only ${scanned.size} files scanned")
        val found = scanned.filter { THREADS.containsMatchIn(ZoneGates.stripComments(it.text)) }.map { it.path }.toSet()
        assertEquals(
            OWN_THREAD.keys.toSortedSet(),
            found.toSortedSet(),
            "the composition names every lane the app runs on, and the JVM host's loop runs each one: a dispatcher, a " +
                "thread or a timer named in the app is work the loop cannot see, so its `Settled` would lie. Take the " +
                "lane as a parameter from the root instead.",
        )
    }

    /** The test functions of [text] with no `@Verifies` of their own, or of their class. */
    private fun unclaimedTests(text: String): List<String> {
        val classClaimed = CLASS_CLAIM.containsMatchIn(text)
        return TEST.findAll(text).mapNotNull { test ->
            val start = maxOf(text.lastIndexOf('}', test.range.first), text.lastIndexOf('{', test.range.first))
            val function = FUNCTION.find(text, test.range.last) ?: return@mapNotNull null
            function.groupValues[1].takeIf { !classClaimed && "@Verifies" !in text.substring(start + 1, function.range.first) }
        }.toList()
    }

    private companion object {
        val TEST = Regex("""@Test\b""")
        val FUNCTION = Regex("""\bfun\s+(`[^`]+`|\w+)""")
        val CLASS_CLAIM = Regex("""@Verifies\([^)]*\)\s*(@\w+[^\n]*\s*)*class\s""")

        /** A call of a polling wait — never its declaration (`fun eventually`). */
        val POLL = Regex("""(?<!fun )\b(rigTest|awaitState|neverWithin|eventually)\s*[({<]""")

        /** A read of the host's device log. */
        val LOG_READ = Regex("""\.logs\(""")

        val WALL_CLOCK =
            Regex("""\bdelay\(|Thread\.sleep|\bwithTimeout|Clock\.System|\bDispatchers\.|TimeSource\.Monotonic""")
        val THREADS = Regex(
            """\bDispatchers\.|\bGlobalScope\b|newSingleThreadContext|newFixedThreadPoolContext|\bExecutors\.|""" +
                """\bThread\(|java\.util\.Timer\b""",
        )

        /** What the JVM host runs of the app: the core's zones, and the generic adapters and mocks it composes over. */
        val APP_CODE = listOf(
            Regex("""^/domain/[^/]+/src/\w+Main/.*"""),
            Regex("""^/adapter/generic/app/src/\w+Main/.*"""),
            Regex("""^/adapter/generic/mock/src/\w+Main/.*"""),
        )

        /** App code that owns a thread for a reason no lane can stand in for — none the JVM host runs. */
        val OWN_THREAD = mapOf(
            "/adapter/generic/app/src/jvmMain/kotlin/app/snapsync/databases/JdbcDatabases.kt" to
                "the JVM's own Databases adapter, which the JVM host does not run (its databases are the mock's): its " +
                "commit notifications stand in for the operating system's cross-process one, on a thread of their own",
        )

        /** Test classes that verify no requirement, and never will. */
        val NEVER_A_REQUIREMENT = mapOf(
            "ShotsTest" to "drives the marketing screenshots' scenarios to their states: a capture, not a contract",
        )

        /** May only shrink: classes with a test that claims no requirement yet. */
        val UNCLAIMED = setOf(
            "EntryIntegrationTest",
            "EventChecksIntegrationTest",
            "HandOffIntegrationTest",
            "IncrementalUnionIntegrationTest",
            "ManifestVersionIntegrationTest",
            "OsCompletionIntegrationTest",
            "RelaunchIntegrationTest",
        )
    }
}
