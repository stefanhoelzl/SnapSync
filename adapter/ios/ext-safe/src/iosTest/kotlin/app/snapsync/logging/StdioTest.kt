@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.logging

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLog
import platform.posix.EAGAIN
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.errno
import platform.posix.pipe
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [neverBlockOnStdio]: a log line must never park its thread on a stdout/stderr pipe that nobody drains
 * (capability `diagnostic-logging`). The incident behind it is in [neverBlockOnStdio]'s KDoc.
 *
 * Both tests build the undrained pipe themselves: a `pipe()` whose read end is held open and never read. Without
 * the fix, each of them BLOCKS instead of failing. A blocking write is the defect, so a test run that hangs here
 * reads as red.
 */
class StdioTest {

    @Test
    fun `a write to a full undrained pipe answers EAGAIN instead of waiting`() = withPipe { _, writeEnd ->
        assertTrue(makeNonBlocking(writeEnd), "O_NONBLOCK could not be set")
        val chunk = ByteArray(4096) { 'x'.code.toByte() }
        var written = 0L
        var refused = false
        // A pipe holds 16–64 KiB on Darwin; 4 MiB is far past any capacity.
        while (written < 4L * 1024 * 1024) {
            val n = chunk.usePinned { write(writeEnd, it.addressOf(0), chunk.size.convert()) }
            if (n < 0) {
                assertEquals(EAGAIN, errno, "a full non-blocking pipe refuses with EAGAIN")
                refused = true
                break
            }
            written += n
        }
        assertTrue(refused, "4 MiB went into a pipe nobody reads — the write end is not a pipe at all?")
    }

    @Test
    fun `NSLog keeps returning while stderr is an undrained pipe`() = withPipe { _, writeEnd ->
        // Point this process's stderr at the undrained pipe — exactly what a DVT/Xcode launch hands the app when
        // its host-side reader stops — and restore the real one afterwards.
        val savedStderr = dup(STDERR_FILENO)
        dup2(writeEnd, STDERR_FILENO)
        try {
            // fd 2 only, as neverBlockOnStdio does for it: the test process's real stdout is the runner's pipe, and
            // flipping IT to non-blocking could drop the runner's own report lines.
            assertTrue(makeNonBlocking(STDERR_FILENO), "O_NONBLOCK could not be set on stderr")
            // ~2.5 MB through NSLog: CoreFoundation copies every line to stderr because it is now a pipe.
            repeat(20_000) { i -> NSLog("StdioTest line $i — padding padding padding padding padding padding padding") }
        } finally {
            dup2(savedStderr, STDERR_FILENO)
            close(savedStderr)
        }
        // Reaching here IS the assertion: without the fix the loop parks inside NSLog's writev once the pipe fills.
    }

    private fun withPipe(body: (readEnd: Int, writeEnd: Int) -> Unit) = memScoped {
        val fds = allocArray<IntVar>(2)
        assertEquals(0, pipe(fds), "pipe() failed")
        val readEnd = fds[0]
        val writeEnd = fds[1]
        try {
            body(readEnd, writeEnd)
        } finally {
            close(writeEnd)
            close(readEnd)
        }
    }
}
