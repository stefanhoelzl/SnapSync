package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * An `InvalidProgramState` ends the process (`docs/patterns.md`, "Invalid program state"): every boundary of the
 * runtime hands it to the coroutines library's last resort — on the JVM, the calling thread's uncaught handler, which
 * these tests stand in for. JVM only: on iOS the last resort terminates the test process, as it should.
 */
class EndProcessTest {
    private val fatal = InvalidProgramState("the test's work is guaranteed never to fail")
    private val lastResort = mutableListOf<Throwable>()

    /**
     * The coroutines test library hooks the same last resort, and keeps what reaches it outside a test to fail the next
     * `runTest` that starts. What these tests send there on purpose is consumed here, by a test of nothing.
     */
    @AfterTest
    fun consumeWhatTheTestLibraryKept() {
        runCatching { runTest { } }
    }

    private fun <T> onThisThread(block: () -> T): T {
        val thread = Thread.currentThread()
        val before = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> lastResort += e }
        try {
            return block()
        } finally {
            thread.uncaughtExceptionHandler = before
        }
    }

    /** A runtime whose work runs on the caller's thread, so its last resort is this thread's. */
    private val runtime =
        Runtime(parent = null, lane = Dispatchers.Unconfined, RuntimePorts(null, Unlabelled, RecordingEntryEvents()))

    @Test
    fun `it is handed to the last resort, and never returned to the caller as an answer`() {
        assertSame(fatal, assertFailsWith<InvalidProgramState> { onThisThread { endProcess(fatal) } })
        assertEquals(listOf<Throwable>(fatal), lastResort)
    }

    @Test
    fun `a delivery the platform waits on ends the process on its thread`() {
        assertFailsWith<InvalidProgramState> { onThisThread { runtime.inline("OnTest", about = null) { throw fatal } } }
        assertEquals(listOf<Throwable>(fatal), lastResort)
    }

    @Test
    fun `an answered delivery ends the process rather than answering its failure`() {
        assertFailsWith<InvalidProgramState> {
            onThisThread { runtime.blocking("OnTest", onFailure = "failed") { throw fatal } }
        }
        assertEquals(listOf<Throwable>(fatal), lastResort)
    }

    @Test
    fun `work nobody awaits ends the process through the scope's handler`() {
        onThisThread { runBlocking { runtime.detached("OnTest") { throw fatal } } }
        assertSame(fatal, lastResort.first(), "the handler's own last resort, before anything it wraps")
    }

    private object Unlabelled : ThreadLabel {
        override fun swap(label: String?): String? = null

        override fun current(): String? = null
    }
}
