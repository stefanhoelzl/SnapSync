package app.snapsync.entry

import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.logging.CapturingLogWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class HandlerSlotTest {
    private val captured = CapturingLogWriter().install()
    private val lines: List<Pair<LogLevel, String>> get() = captured.written
    private val log = Log("test")

    @Test
    fun a_listened_slot_answers_the_last_registration_whatever_its_early_answer() {
        val slot = HandlerSlot<String>("Links", BeforeListen.Thrown)
        slot.set("first")
        slot.set("second")
        assertSame("second", slot.orNull("a link"))
        assertSame("second", slot.require("a link"))
    }

    @Test
    fun a_dropped_early_delivery_is_null_and_says_nothing() {
        assertNull(HandlerSlot<String>("Lifecycle", BeforeListen.Logged(log)).orNull("tick", BeforeListen.Dropped))
        assertEquals(emptyList(), lines)
    }

    @Test
    fun a_logged_early_delivery_is_null_and_logs_an_error_naming_the_port() {
        assertNull(HandlerSlot<String>("Links", BeforeListen.Logged(log)).orNull("a link"))
        assertEquals(
            listOf(LogLevel.ERROR to "arrived before the port was listened to event=a link port=Links"),
            lines,
        )
    }

    @Test
    fun a_thrown_early_delivery_and_a_required_one_throw() {
        val slot = HandlerSlot<String>("Ui", BeforeListen.Thrown)
        assertFailsWith<IllegalStateException> { slot.orNull("a tap") }
        assertFailsWith<IllegalStateException> { HandlerSlot<String>("Ui", BeforeListen.Dropped).require("a tap") }
    }
}
