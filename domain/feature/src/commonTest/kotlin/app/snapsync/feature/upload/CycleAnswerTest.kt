package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.model.PauseReason
import app.snapsync.model.ProcessingAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the operating system is told each cycle came to (capability `background-upload`) — the composition's decision,
 * which the extension's adapter only passes through.
 */
class CycleAnswerTest {

    @Test
    fun `a drained cycle lets the system rest`() {
        assertEquals(ProcessingAnswer.COMPLETED, CycleResult.COMPLETED.answer())
    }

    @Test
    fun `a declined cycle rests like a drained one`() {
        // This device contributes nothing: another invocation would decline again.
        assertEquals(ProcessingAnswer.COMPLETED, CycleResult.SKIPPED.answer())
    }

    @Test
    fun `remaining work asks to be invoked again`() {
        assertEquals(ProcessingAnswer.PROCESSING, CycleResult.PROCESSING.answer())
    }

    @Test
    fun `a paused cycle asks to be invoked again`() {
        // It touched nothing and waits for the app to migrate the download store.
        assertEquals(ProcessingAnswer.PROCESSING, CycleResult.Paused(PauseReason.OLD_SCHEMA).answer())
    }

    @Test
    fun `a failed cycle fails the invocation`() {
        assertEquals(ProcessingAnswer.FAILED, CycleResult.FAILED.answer())
    }

    @Test
    fun `every cycle result has an answer`() {
        assertEquals(CycleResult.all.size, CycleResult.all.map { it.answer() }.size)
    }
}
