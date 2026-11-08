package app.snapsync.platform

import app.snapsync.domain.program.InvalidProgramState
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The adapters' boundary catch-all: a platform's failure becomes a value; cancellation and a program fault pass on. */
class CatchingTest {

    @Test
    fun `an answer is a success and a failure a failure value`() {
        assertEquals(Result.success(3), PlatformCalls.attempt { 3 })
        assertIs<IllegalStateException>(PlatformCalls.attempt { error("the platform refused") }.exceptionOrNull())
    }

    @Test
    fun `a suspending call answers the same`() = runTest {
        assertEquals(Result.success(4), PlatformCalls.attemptSuspending { 4 })
        assertIs<IllegalStateException>(PlatformCalls.attemptSuspending { error("refused") }.exceptionOrNull())
    }

    @Test
    fun `cancellation and an invalid program state are never caught`() = runTest {
        assertFailsWith<CancellationException> { PlatformCalls.attempt { throw CancellationException("gave up") } }
        assertFailsWith<InvalidProgramState> { PlatformCalls.attempt { throw InvalidProgramState("guaranteed") } }
        assertFailsWith<CancellationException> {
            PlatformCalls.attemptSuspending { throw CancellationException("gave up") }
        }
        assertFailsWith<InvalidProgramState> {
            PlatformCalls.attemptSuspending { throw InvalidProgramState("guaranteed") }
        }
    }
}
