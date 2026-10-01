package app.snapsync.control

import app.snapsync.rig.JvmRigHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The rig server's own lifecycle, as its `[rig]` log tells it: a stop is not a bind failure. Every host a test starts
 * is stopped, and an `Error` line on each stop was noise in every test log — and, on a rig build, an event for the
 * crash reporter. A bind that really fails must still say so at `Error`.
 *
 * Read off the process's stdout, where the JVM host's log sink prints every line it formats: the composition installs
 * the process's log writers itself, so nothing a test adds before a start survives it.
 */
class RigServerLifecycleTest {

    private val original = System.out
    private val captured = ByteArrayOutputStream()

    @BeforeTest
    fun capture() = System.setOut(PrintStream(TeeStream(captured, original), true))

    @AfterTest
    fun release() = System.setOut(original)

    @Test
    fun a_stop_logs_one_info_line_and_no_error() = runBlocking {
        JvmRigHost.start().close()
        // The stop is observed on the server's own coroutine; its one line is the signal it has been.
        awaitLine { "[Info/rig] stopped listening" in it }
        assertEquals(emptyList(), rigLines().filter { "[Error/rig]" in it }, "a stop is not a failure")
    }

    @Test
    fun a_refused_bind_is_an_error_and_fails_the_start_at_once() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { held ->
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<IllegalStateException> { JvmRigHost.start(port = held.localPort) }
            assertTrue(started.elapsedNow() < 10.seconds, "the failed bind was waited out, not reported")
            assertEquals(1, rigLines().count { "[Error/rig] bind" in it && "FAILED" in it }, rigLines().toString())
        }
    }

    private fun rigLines() = synchronized(captured) { captured.toString() }.lines().filter { "/rig]" in it }

    private suspend fun awaitLine(match: (String) -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (rigLines().none(match)) {
            check(deadline.hasNotPassedNow()) { "no matching [rig] line; saw ${rigLines()}" }
            delay(20)
        }
    }

    /** Keeps what the test reads and still shows it in the test's own output. */
    private class TeeStream(private val kept: ByteArrayOutputStream, private val shown: PrintStream) : java.io.OutputStream() {
        override fun write(b: Int) {
            synchronized(kept) { kept.write(b) }
            shown.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(kept) { kept.write(b, off, len) }
            shown.write(b, off, len)
        }
    }
}
