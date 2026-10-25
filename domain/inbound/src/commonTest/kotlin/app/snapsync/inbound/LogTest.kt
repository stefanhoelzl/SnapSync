@file:OptIn(CompositionOnly::class)

package app.snapsync.inbound

import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LogTest {
    private val lines = mutableListOf<LogLine>()
    private val log = Log("Tag")

    init {
        Log.install(
            listOf(
                object : LogWriter {
                    override fun write(line: LogLine) {
                        lines += line
                    }
                },
            ),
        )
    }

    @AfterTest
    fun uninstall() = Log.install(emptyList())

    @Test
    fun `a line carries the trigger of the work that wrote it`() = runTest {
        withContext(Trigger.of("OnHeartbeat") + Trigger.of("OnSilentPush")) {
            log.i("tail ran", "units" of 3)
        }
        val line = lines.single()
        assertEquals("OnHeartbeat+OnSilentPush", line.trigger)
        assertEquals(Severity.Info, line.severity)
        assertEquals("Tag", line.tag)
        assertEquals("tail ran", line.event)
        assertEquals(listOf("units" to 3), line.fields.map { it.name to it.value })
    }

    @Test
    fun `a line written outside any work carries no trigger`() = runTest {
        val cause = IllegalStateException("x")
        log.w("warned", cause = cause)
        log.e("failed", "id" of "e1", cause = cause)
        log.w("warned without a cause")
        log.e("failed without a cause")
        assertEquals(listOf(Severity.Warn, Severity.Error, Severity.Warn, Severity.Error), lines.map { it.severity })
        assertNull(lines[0].trigger)
        assertSame(cause, lines[1].cause)
    }

    @Test
    fun `non-suspend code writes the same lines - unlabelled`() {
        val cause = IllegalStateException("x")
        log.unlabelled.i("one", "a" of 1)
        log.unlabelled.w("two", cause = cause)
        log.unlabelled.e("three", cause = cause)
        assertEquals(listOf("one", "two", "three"), lines.map { it.event })
        assertEquals(listOf(Severity.Info, Severity.Warn, Severity.Error), lines.map { it.severity })
        assertEquals(listOf(null, null, null), lines.map { it.trigger })
    }

    @Test
    fun `the handle of a delivery nobody waits on answers nothing`() = Completion.None.complete()

    @Test
    fun `a trigger names each entry point once`() {
        assertEquals("A+B", (Trigger.of("A") + Trigger.of("B") + Trigger.of("A")).toString())
    }
}
