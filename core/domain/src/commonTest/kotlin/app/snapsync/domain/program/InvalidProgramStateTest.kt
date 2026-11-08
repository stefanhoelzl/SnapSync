package app.snapsync.domain.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot

class InvalidProgramStateTest {

    @Test
    fun `it carries its guarantee and is no Exception a catch of one could swallow`() {
        val raised: Throwable = InvalidProgramState("the count asked is the count answered")
        assertEquals("the count asked is the count answered", raised.message)
        assertIs<Error>(raised)
        assertIsNot<Exception>(raised)
    }
}
