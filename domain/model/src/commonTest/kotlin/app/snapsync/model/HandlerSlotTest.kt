package app.snapsync.model

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class HandlerSlotTest {
    private val lines = mutableListOf<Pair<Severity, String>>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
    }
    private val log = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(writer)), "test")

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
        assertEquals(listOf(Severity.Error to "a link arrived before the Links port was listened to"), lines)
    }

    @Test
    fun a_thrown_early_delivery_and_a_required_one_throw() {
        val slot = HandlerSlot<String>("Ui", BeforeListen.Thrown)
        assertFailsWith<IllegalStateException> { slot.orNull("a tap") }
        assertFailsWith<IllegalStateException> { HandlerSlot<String>("Ui", BeforeListen.Dropped).require("a tap") }
    }
}
