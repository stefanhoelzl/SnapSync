@file:OptIn(CompositionOnly::class)

package app.snapsync.inbound

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A process that supplies log sinks owns the log's writers; one that supplies none leaves them alone. */
class ProcessLoggingTest {
    private val theirs = object : LogWriter {
        override fun write(line: LogLine) = Unit
    }
    private val ours = object : LogWriter {
        override fun write(line: LogLine) = Unit
    }

    @AfterTest
    fun uninstall() {
        Log.install(emptyList())
    }

    @Test
    fun `a process with sinks installs its writers, and the install answers those it replaced`() {
        Log.install(listOf(theirs))
        ProcessLogging(listOf(ours), sinks = 1).install()
        assertEquals(listOf(ours), Log.install(emptyList()))
    }

    @Test
    fun `a process with no sinks leaves the writers it found`() {
        Log.install(listOf(theirs))
        ProcessLogging(listOf(ours), sinks = 0).install()
        assertSame(theirs, Log.install(emptyList()).single())
    }
}
