@file:OptIn(CompositionOnly::class)

package app.snapsync.inbound

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
    fun uninstall() {
        Log.install(emptyList())
    }

    @Test
    fun `a line carries the trigger of the work that wrote it`() = runTest {
        withContext(Trigger.of("OnHeartbeat") + Trigger.of("OnSilentPush")) {
            log.i("tail ran", "units" of 3)
        }
        val line = lines.single()
        assertEquals("OnHeartbeat+OnSilentPush", line.trigger)
        assertEquals(LogLevel.INFO, line.level)
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
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.WARN, LogLevel.ERROR), lines.map { it.level })
        assertNull(lines[0].trigger)
        assertSame(cause, lines[1].cause)
    }

    @Test
    fun `non-suspend code writes the same lines - unlabelled`() {
        val cause = IllegalStateException("x")
        log.unlabelled.d("zero")
        log.unlabelled.i("one", "a" of 1)
        log.unlabelled.w("two", cause = cause)
        log.unlabelled.e("three", cause = cause)
        log.unlabelled.e("four")
        assertEquals(listOf("zero", "one", "two", "three", "four"), lines.map { it.event })
        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.ERROR),
            lines.map { it.level },
        )
        assertEquals(listOf(null, null, null, null, null), lines.map { it.trigger })
        assertEquals(null, lines.last().cause)
    }

    @Test
    fun `the handle of a delivery nobody waits on answers nothing`() = Completion.None.complete()

    @Test
    fun `a trigger names each entry point once`() {
        assertEquals("A+B", (Trigger.of("A") + Trigger.of("B") + Trigger.of("A")).toString())
    }

    @Test
    fun `a line's text is its event, then each field as name=value`() {
        val line =
            LogLine(LogLevel.INFO, "Tag", null, "top-up planned", listOf("needing" of 5, "admitted" of null), null)
        assertEquals("top-up planned needing=5 admitted=null", line.text())
    }
}
