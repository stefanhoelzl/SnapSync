package app.snapsync.services.logs

import app.snapsync.ports.LogSink
import app.snapsync.services.CapturingLogWriter
import co.touchlab.kermit.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who owns Kermit's VM-global writer list (`docs/architecture.md`, "One shared composition"): a process that supplies
 * log sinks owns it and installs its writers there; one that supplies none (a JVM "process") leaves it alone, and its
 * logger writes to its own writers AND to whatever the VM logs to, so no line is lost to another composition's list.
 */
class ProcessLogWritersTest {

    private val sink = LogSink { _, _, _ -> }
    private val own = CapturingLogWriter()
    private val vm = CapturingLogWriter()
    private lateinit var saved: List<co.touchlab.kermit.LogWriter>

    @BeforeTest
    fun saveGlobalWriters() {
        saved = Logger.config.logWriterList
        Logger.setLogWriters(vm)
    }

    @AfterTest
    fun restoreGlobalWriters() {
        Logger.setLogWriters(saved)
    }

    @Test
    fun `a process with sinks owns the global list and installs its writers there`() {
        val writers = ProcessLogWriters(listOf(own), listOf(sink))
        assertTrue(writers.ownsGlobalLogger)
        writers.install()
        assertEquals(listOf(own), Logger.config.logWriterList)
    }

    @Test
    fun `a process without sinks leaves the global list alone`() {
        val writers = ProcessLogWriters(listOf(own), emptyList())
        assertFalse(writers.ownsGlobalLogger)
        writers.install()
        assertEquals(listOf(vm), Logger.config.logWriterList)
    }

    @Test
    fun `an owning process's logger writes to its own writers only`() {
        ProcessLogWriters(listOf(own), listOf(sink)).logger("owner").i { "hello" }
        assertEquals(listOf("hello"), own.lines.map { it.second })
        assertTrue(vm.lines.isEmpty(), "the global list is this same list once installed: ${vm.lines}")
    }

    @Test
    fun `a non-owning process's logger reaches its own writers and the VM's`() {
        ProcessLogWriters(listOf(own), emptyList()).logger("guest").i { "hello" }
        assertEquals(listOf("hello"), own.lines.map { it.second })
        assertEquals(listOf("hello"), vm.lines.map { it.second })
    }
}
