package app.snapsync.model

import app.snapsync.domain.program.InvalidProgramState
import co.touchlab.kermit.Severity
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class UncaughtInRootScopeTest {

    @Test
    fun `what no coroutine handled is logged at Error, and the app lives`() {
        val captured = CapturingLogWriter()
        uncaughtInRootScope(captured.logger()).handleException(EmptyCoroutineContext, IllegalStateException("x"))
        assertEquals(listOf(Severity.Error to "uncaught in app scope — logged, not fatal"), captured.lines)
    }

    @Test
    fun `an invalid program state is rethrown to the last resort, never logged and lived through`() {
        val captured = CapturingLogWriter()
        val fatal = InvalidProgramState("the contract is guaranteed")
        val thrown = assertFailsWith<InvalidProgramState> {
            uncaughtInRootScope(captured.logger()).handleException(EmptyCoroutineContext, fatal)
        }
        assertSame(fatal, thrown)
        assertEquals(emptyList(), captured.lines)
    }
}
