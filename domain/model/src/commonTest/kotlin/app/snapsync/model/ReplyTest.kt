package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The backend answer's two readings the services share: "the value, if served" and "as a Result". */
class ReplyTest {

    private val offline = IllegalStateException("offline")

    @Test
    fun only_a_served_answer_has_a_value() {
        assertEquals("v", Reply.Ok("v").okOrNull())
        assertNull(Reply.Refused(404, "").okOrNull())
        assertNull(Reply.Malformed("x").okOrNull())
        assertNull(Reply.Unreachable(offline).okOrNull())
    }

    @Test
    fun as_a_result_every_answer_but_a_served_one_fails_naming_what_came_back() {
        assertEquals(Result.success("v"), Reply.Ok("v").toResult("read"))
        val refused = Reply.Refused(409, "full").toResult("join").exceptionOrNull()?.message.orEmpty()
        assertTrue("join" in refused && "409" in refused && "full" in refused, refused)
        val malformed = Reply.Malformed("no id").toResult("list").exceptionOrNull()?.message.orEmpty()
        assertTrue("list" in malformed && "no id" in malformed, malformed)
        assertSame(offline, Reply.Unreachable(offline).toResult("leave").exceptionOrNull(), "a transport failure is its own cause")
    }

    @Test
    fun an_unreachable_answer_names_its_cause() {
        assertEquals("Unreachable(offline)", Reply.Unreachable(offline).toString())
    }
}
