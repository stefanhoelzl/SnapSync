package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.inbound.LogLevel
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RootScopeFailuresTest {

    @Test
    fun `what no coroutine handled is logged at Error, and the app lives`() {
        val log = RecordingLog().also { it.install() }
        RootScopeFailures().handleException(EmptyCoroutineContext, IllegalStateException("x"))
        assertEquals(
            listOf(LogLevel.ERROR to "uncaught in the root scope: logged, not fatal"),
            log.lines.map { it.level to it.event },
        )
    }

    @Test
    fun `an invalid program state is rethrown to the last resort, never logged and lived through`() {
        val log = RecordingLog().also { it.install() }
        val fatal = InvalidProgramState("the contract is guaranteed")
        val thrown = assertFailsWith<InvalidProgramState> {
            RootScopeFailures().handleException(EmptyCoroutineContext, fatal)
        }
        assertSame(fatal, thrown)
        assertEquals(emptyList(), log.lines)
    }
}
