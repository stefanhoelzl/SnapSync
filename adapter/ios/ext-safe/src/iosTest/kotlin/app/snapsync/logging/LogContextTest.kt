package app.snapsync.logging

import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.withTempDirectory
import co.touchlab.kermit.Severity
import platform.Foundation.NSBlockOperation
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which prefix a line gets, and from which thread (capability `diagnostic-logging`).
 *
 * The measured failure this pins: a MetricKit delivery held the process-wide claim while launch work
 * logged on other threads, and seven launch lines were labelled as the delivery's. A thread-scoped
 * claim must reach none of them — while the process-wide claim must still reach other threads, because
 * that is what carries a trigger across the hops of asynchronous work.
 *
 * Only a real second thread can tell the two apart, so every cross-thread assertion runs on a private
 * `NSOperationQueue` (checked, not assumed, to be another thread). Foundation and not GCD: this source
 * set is extension-linked, and the extension-safety gate does not permit `platform.darwin`.
 */
class LogContextTest {

    /** Run [block] on another thread and hand back its result. */
    private fun <T> onAnotherThread(block: () -> T): T {
        val caller = NSThread.currentThread
        var outcome: Result<T>? = null
        var sameThread = false
        val work = NSBlockOperation.blockOperationWithBlock {
            sameThread = NSThread.currentThread === caller
            outcome = runCatching(block)
        }
        // A private queue never runs on the caller's thread, and `waitUntilFinished` is the join.
        NSOperationQueue().addOperations(listOf(work), waitUntilFinished = true)
        assertFalse(sameThread, "the helper must run on a different thread or these tests prove nothing")
        return outcome!!.getOrThrow()
    }

    @Test
    fun `a thread-scoped claim prefixes its own thread`() {
        val owned = LogContext.enterThread("didReceiveMetricPayloads")
        try {
            assertTrue(owned)
            assertEquals("didReceiveMetricPayloads", LogContext.current)
        } finally {
            LogContext.exitThread(owned)
        }
        assertNull(LogContext.current, "an owned claim is gone once it exits")
    }

    @Test
    fun `a thread-scoped claim does not reach another thread`() {
        val owned = LogContext.enterThread("didReceiveMetricPayloads")
        try {
            assertNull(onAnotherThread { LogContext.current }, "this is the measured bleed")
        } finally {
            LogContext.exitThread(owned)
        }
    }

    @Test
    fun `a line written on another thread during a thread-scoped claim carries no prefix`() {
        withTempDirectory { dir ->
            val path = "$dir/debug.log"
            val writer = FileLogWriter(path)
            val owned = LogContext.enterThread("didReceiveMetricPayloads")
            try {
                writer.log(Severity.Info, "process metrics: observing", "processMetrics", null)
                onAnotherThread { writer.log(Severity.Info, "gallery: fetched 1753 candidate(s)", "gallery", null) }
            } finally {
                LogContext.exitThread(owned)
            }

            val text = readTextFile(path).orEmpty()
            assertTrue("[didReceiveMetricPayloads] [Info/processMetrics] process metrics: observing" in text, text)
            assertTrue("[Info/gallery] gallery: fetched 1753 candidate(s)" in text, text)
            assertFalse("[didReceiveMetricPayloads] [Info/gallery]" in text, "launch work is not the delivery's: $text")
        }
    }

    @Test
    fun `a process-wide claim still reaches another thread`() {
        val owned = LogContext.enter("onSilentPush")
        try {
            assertEquals(
                "onSilentPush",
                onAnotherThread { LogContext.current },
                "a trigger's prefix must survive the thread hops of its asynchronous work",
            )
        } finally {
            LogContext.exit(owned)
        }
    }

    @Test
    fun `a process-wide enter nested under a thread-scoped claim claims nothing`() {
        val outer = LogContext.enterThread("didReceiveMetricPayloads")
        try {
            val nested = LogContext.enter("SentryDiagnosticsReporter.start")
            try {
                assertFalse(nested, "a nested seam must not take the global slot")
                assertEquals("didReceiveMetricPayloads", LogContext.current)
                assertNull(onAnotherThread { LogContext.current }, "or the bleed comes straight back")
            } finally {
                LogContext.exit(nested)
            }
        } finally {
            LogContext.exitThread(outer)
        }
    }

    @Test
    fun `a nested thread-scoped enter claims nothing`() {
        val outer = LogContext.enterThread("didReceiveMetricPayloads")
        try {
            val nested = LogContext.enterThread("didReceiveDiagnosticPayloads")
            LogContext.exitThread(nested)
            assertFalse(nested)
            assertEquals("didReceiveMetricPayloads", LogContext.current, "a nested exit must not clear the outer claim")
        } finally {
            LogContext.exitThread(outer)
        }
    }

    @Test
    fun `a thread-scoped claim wins on its thread over a process-wide claim held elsewhere`() {
        val launch = LogContext.enter("onLaunch")
        try {
            val delivery = onAnotherThread {
                val owned = LogContext.enterThread("didReceiveMetricPayloads")
                try {
                    owned to LogContext.current
                } finally {
                    LogContext.exitThread(owned)
                }
            }
            assertEquals(true to "didReceiveMetricPayloads", delivery)
            assertEquals("onLaunch", LogContext.current, "the process-wide claim is untouched")
        } finally {
            LogContext.exit(launch)
        }
    }

    @Test
    fun `exiting a thread-scoped claim restores the process-wide prefix on that thread`() {
        val launch = LogContext.enter("onLaunch")
        try {
            val owned = LogContext.enterThread("didReceiveMetricPayloads")
            LogContext.exitThread(owned)
            assertEquals("onLaunch", LogContext.current)
        } finally {
            LogContext.exit(launch)
        }
    }
}
