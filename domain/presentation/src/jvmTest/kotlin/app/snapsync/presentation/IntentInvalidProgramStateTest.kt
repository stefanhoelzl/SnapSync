package app.snapsync.presentation

import app.snapsync.domain.program.InvalidProgramState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Nothing catches an `InvalidProgramState` (`docs/patterns.md`, "Invalid program state"): a command that throws one is
 * not reported as an intent's error — the container's handler rethrows it to the coroutines library's last resort,
 * the thread's uncaught handler on the JVM, which this test stands in for. JVM only: on iOS the last resort terminates.
 */
class IntentInvalidProgramStateTest {

    /**
     * The coroutines test library hooks the same last resort, and keeps what reaches it outside a test to fail the next
     * `runTest` that starts. What this test sends there on purpose is consumed here, by a test of nothing.
     */
    @AfterTest
    fun consumeWhatTheTestLibraryKept() {
        runCatching { runTest { } }
    }

    @Test
    fun `an invalid program state in a command reaches the last resort, never the intent error`() = runBlocking {
        val fatal = InvalidProgramState("the test's command is guaranteed never to fail")
        val lastResort = CompletableDeferred<Throwable>()
        val reported = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> lastResort.complete(e) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val commands = TestCommands(leave = { throw fatal })
            val host = StatusContainerHost(
                StatusSources(
                    FakeSyncStatusSource(),
                    FakePermissionSource().permission,
                    FakeConfig().config,
                    requests = commands.requests.flows,
                ),
                scope,
                commands = commands,
                cutoffFormatter = CutoffFormatter(now = { Clock.System.now() }, zone = TimeZone.UTC),
                joinDetails = unanswered,
                diagnostics = testDiagnostics(onIntentError = { reported += it }),
                queries = noQueries,
                reduction = Dispatchers.Default,
            )
            host.onLeaveEvent()
            assertSame(fatal, withTimeout(10.seconds) { lastResort.await() })
            assertEquals(emptyList(), reported, "never handled as an intent's error")
        } finally {
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
