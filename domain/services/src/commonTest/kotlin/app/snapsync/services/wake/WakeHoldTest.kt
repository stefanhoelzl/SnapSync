package app.snapsync.services.wake

import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.Completion
import app.snapsync.services.CapturingLogWriter
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One wake's hold on the process's background time (capability `sync-status`, "OS completion handlers are released
 * only after their work completes"): the hold is ended on EVERY path — a finished tail, a failing tail or end-of-wake
 * step, an expiry before or during the tail, a refused begin — and settling runs just before it ends; on the
 * operating system's expiry the tail is asked to stop, every guarded handler is released at once, and no tail or
 * end-of-wake step starts afterwards.
 */
class WakeHoldTest {

    /** A [BackgroundTime] whose expiry the test fires — at once inside `begin` when [refuse]d, as a refusal reports. */
    private class Time(private val refuse: Boolean = false) : BackgroundTime {
        val labels = mutableListOf<String>()
        var ends = 0
        private var onExpiry: () -> Unit = {}

        override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
            labels += label
            this.onExpiry = onExpiry
            if (refuse) onExpiry()
            return object : BackgroundTimeHold {
                override fun end() {
                    ends++
                }
            }
        }

        fun expire() = onExpiry()
    }

    private val log = CapturingLogWriter()
    private val stops = mutableListOf<String>()
    private val settled = mutableListOf<String>()

    private fun hold(time: Time, settling: (String) -> Unit = { settled += it }) =
        WakeHold("push", time, stopTail = { stops += it }, log = log.logger(), settling = settling)

    private fun lines(severity: Severity) = log.lines.filter { it.first == severity }.map { it.second }

    @Test
    fun `a hold is begun under the wake's label and ended once with no tail`() {
        val time = Time()
        hold(time).end()
        assertEquals(listOf("push"), time.labels)
        assertEquals(1, time.ends)
        assertTrue(stops.isEmpty())
    }

    @Test
    fun `a refused begin expires inside begin and the granted hold is still ended`() = runTest {
        val time = Time(refuse = true)
        val hold = hold(time)
        assertEquals(1, time.ends, "the expiry came before there was a hold, so the constructor ends it")
        assertEquals(listOf("background time for push is up"), stops)
        var tailRan = false
        hold.thenTail("upload", tail = { tailRan = true }, finish = { })
        assertFalse(tailRan, "no tail is requested once the time is up")
        assertTrue(lines(Severity.Info).any { "no tail requested" in it }, "${log.lines}")
    }

    @Test
    fun `a tail and its finish run and the hold settles and ends`() = runTest {
        val time = Time()
        val order = mutableListOf<String>()
        hold(time, settling = { order += "settling:$it" }).thenTail(
            "upload",
            tail = { order += "tail" },
            finish = { order += "finish" },
        )
        assertEquals(listOf("tail", "finish", "settling:push"), order)
        assertEquals(1, time.ends)
        assertTrue(lines(Severity.Warn).isEmpty(), "${log.lines}")
    }

    @Test
    fun `a failing tail is logged and the finish still runs`() = runTest {
        val time = Time()
        var finished = false
        hold(time).thenTail("upload", tail = { error("tail blew up") }, finish = { finished = true })
        assertTrue(finished)
        assertTrue(lines(Severity.Warn).any { "its tail (upload) failed" in it }, "${log.lines}")
        assertEquals(listOf("push"), settled)
        assertEquals(1, time.ends)
    }

    @Test
    fun `a failing finish is logged and the hold still ends`() = runTest {
        val time = Time()
        hold(time).thenTail("upload", tail = { }, finish = { error("finish blew up") })
        assertTrue(lines(Severity.Warn).any { "end-of-wake step failed" in it }, "${log.lines}")
        assertEquals(listOf("push"), settled)
        assertEquals(1, time.ends)
    }

    @Test
    fun `an expiry during the tail stops it and skips the finish`() = runTest {
        val time = Time()
        var finished = false
        hold(time).thenTail("upload", tail = { time.expire() }, finish = { finished = true })
        assertFalse(finished, "the end-of-wake step does not start once the time is up")
        assertEquals(listOf("background time for push is up"), stops)
        assertTrue(lines(Severity.Warn).any { "OS expiry" in it }, "${log.lines}")
        assertEquals(listOf("push"), settled)
        assertEquals(2, time.ends, "the expiry ends the hold at once, the tail's end once more (a no-op on the OS)")
    }

    @Test
    fun `a cancelled tail still settles and ends the hold`() = runTest {
        val time = Time()
        assertFailsWith<CancellationException> {
            hold(time).thenTail("upload", tail = { throw CancellationException("stopped") }, finish = { })
        }
        assertEquals(listOf("push"), settled)
        assertEquals(1, time.ends)
    }

    @Test
    fun `a settling step that throws still ends the hold`() = runTest {
        val time = Time()
        assertFailsWith<IllegalStateException> {
            hold(time, settling = { error("footprint read failed") }).thenTail("upload", tail = { }, finish = { })
        }
        assertEquals(1, time.ends)
    }

    @Test
    fun `a guarded handover is released on the expiry and not before`() {
        val time = Time()
        val released = mutableListOf<String>()
        val hold = hold(time)
        val handover = OsCompletions("test").adopt(completionOf { released += "push" })
        hold.guard(handover)
        assertTrue(released.isEmpty(), "released before the time was up")
        time.expire()
        assertEquals(listOf("push"), released)
        assertTrue(handover.isReleased)
    }

    @Test
    fun `a handover guarded after the expiry is released at once`() {
        val time = Time()
        val released = mutableListOf<String>()
        val hold = hold(time)
        time.expire()
        hold.guard(OsCompletions("test").adopt(completionOf { released += "late" }))
        assertEquals(listOf("late"), released)
    }

    @Test
    fun `a second expiry does nothing`() {
        val time = Time()
        hold(time)
        time.expire()
        time.expire()
        assertEquals(1, stops.size, "the tail is asked to stop once")
        assertEquals(1, time.ends)
        assertEquals(1, lines(Severity.Warn).count { "OS expiry" in it })
    }
}

/** A [Completion] that runs [onComplete] when completed. */
private fun completionOf(onComplete: () -> Unit): Completion = object : Completion {
    override fun complete() = onComplete()
}
