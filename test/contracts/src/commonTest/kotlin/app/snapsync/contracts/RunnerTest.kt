package app.snapsync.contracts

import app.snapsync.contracts.proxy.recorded
import app.snapsync.ports.Clock
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private enum class Toy { ON, OFF }

private object FixedClock : Clock {
    override fun now() = Instant.fromEpochSeconds(0)
    override fun timeZone() = TimeZone.UTC
}

/** The toys bind no port of their own, so they borrow a real one: the clock each clause reads, recorded. */
private class Switch(var on: Boolean, val clock: Clock = FixedClock)

private val TOY_COVERS = cells { on<Clock>().answers(Clock::now).returns() }

private object ToyContract : Contract<Toy, Switch>("Toy") {
    override val clauses = listOf(
        clause("ON_READS_ON", Toy.ON, TOY_COVERS) {
            it.clock.now()
            assertTrue(it.on)
        },
        clause("OFF_READS_OFF", Toy.OFF, TOY_COVERS) {
            it.clock.now()
            assertTrue(!it.on)
        },
    )
}

private class ToyBinding(
    override val reaches: Set<Toy>,
    private val answer: (Toy, CallLog) -> Entered<Switch>,
) : Binding<Toy, Switch> {
    override val host = Host.JVM
    override val kind = BindingKind.Fake
    override fun create(state: Toy, clauseId: String, log: CallLog) = answer(state, log)
}

/** A toy binding that ignores the log: its clock is the bare one, so nothing it answers is recorded. */
private fun unrecorded(reaches: Set<Toy>, answer: (Toy) -> Entered<Switch>) = ToyBinding(reaches) { s, _ -> answer(s) }

private fun honest(reaches: Set<Toy>) = ToyBinding(reaches) { s, log ->
    if (s in reaches) {
        Entered.Ready(
            Switch(s == Toy.ON, FixedClock.recorded(log)),
        )
    } else {
        Entered.Unreachable("toy cannot be $s")
    }
}

/** A one-clause contract over [covers] whose body reads the clock [reads] times. */
private fun clockContract(covers: Covers, reads: Int = 1) = object : Contract<Toy, Switch>("Clock") {
    override val clauses = listOf(clause("C", Toy.ON, covers) { repeat(reads) { _ -> it.clock.now() } })
}

class RunnerTest {

    @Test
    fun `a binding reaching every state passes every clause`() {
        val results = run(ToyContract, honest(setOf(Toy.ON, Toy.OFF)))
        assertEquals(listOf("ON_READS_ON", "OFF_READS_OFF"), results.map { it.clauseId })
        assertTrue(results.all { it.outcome == Outcome.Passed })
    }

    @Test
    fun `an undeclared unreachable state is NotRunHere with the binding’s reason`() {
        val off = run(ToyContract, honest(setOf(Toy.ON))).single { it.clauseId == "OFF_READS_OFF" }
        assertEquals(Outcome.NotRunHere("toy cannot be OFF"), off.outcome)
    }

    @Test
    fun `a declared state answered Unreachable is Failed - the declaration lied`() {
        val liar = unrecorded(setOf(Toy.ON, Toy.OFF)) { Entered.Unreachable("nope") }
        assertTrue(run(ToyContract, liar).all { it.outcome is Outcome.Failed })
    }

    @Test
    fun `an undeclared state answered Ready is Failed and still disposed`() {
        var disposed = 0
        val sneaky = unrecorded(emptySet()) { s -> Entered.Ready(Switch(s == Toy.ON)) { disposed++ } }
        assertTrue(run(ToyContract, sneaky).all { it.outcome is Outcome.Failed })
        assertEquals(2, disposed)
    }

    @Test
    fun `a violated clause is Failed and the subject is disposed`() {
        var disposed = 0
        val wrong = ToyBinding(setOf(Toy.ON, Toy.OFF)) { _, log ->
            Entered.Ready(Switch(false, FixedClock.recorded(log))) { disposed++ }
        }
        val results = run(ToyContract, wrong)
        assertIs<Outcome.Failed>(results.single { it.clauseId == "ON_READS_ON" }.outcome)
        assertEquals(Outcome.Passed, results.single { it.clauseId == "OFF_READS_OFF" }.outcome)
        assertEquals(2, disposed)
    }

    @Test
    fun `a divergence is Diverged and not Failed`() {
        val contract = object : Contract<Toy, Replayer>("Replay") {
            override val clauses = listOf(clause("C", Toy.ON, TOY_COVERS) { it.answer("unrecorded()") })
        }
        val binding = object : Binding<Toy, Replayer> {
            override val host = Host.IOS_DEVICE_APP
            override val kind = BindingKind.Replay
            override val reaches = setOf(Toy.ON)
            override fun create(
                state: Toy,
                clauseId: String,
                log: CallLog,
            ) = Entered.Ready(Replayer(clauseId, emptyList()))
        }
        assertIs<Outcome.Diverged>(run(contract, binding).single().outcome)
    }

    @Test
    fun `a divergence raised while disposing is Diverged even after the body passed`() {
        val binding = unrecorded(setOf(Toy.ON, Toy.OFF)) { s ->
            Entered.Ready(Switch(s == Toy.ON)) { throw Divergence("a recorded call was never made") }
        }
        assertTrue(run(ToyContract, binding).all { it.outcome is Outcome.Diverged })
    }

    @Test
    fun `verify fails once with the whole table`() {
        val wrong =
            ToyBinding(setOf(Toy.ON, Toy.OFF)) { _, log -> Entered.Ready(Switch(false, FixedClock.recorded(log))) }
        val error = assertFailsWith<AssertionError> { verify(ToyContract, wrong) }
        val message = error.message.orEmpty()
        assertTrue("1 of 2 clauses failed" in message, message)
        assertTrue("OFF_READS_OFF Passed" in message, "the table lists passing clauses too: $message")
    }

    @Test
    fun `verify fails on an expired wait - it established nothing`() {
        val contract = object : Contract<Toy, Switch>("Waits") {
            override val clauses = listOf(clause("C", Toy.ON, TOY_COVERS) { throw WaitExpired(10) })
        }
        val error = assertFailsWith<AssertionError> { verify(contract, honest(setOf(Toy.ON))) }
        val message = error.message.orEmpty()
        assertTrue("1 of 1 clauses failed" in message, message)
        assertTrue("C NotWithin(10ms)" in message, message)
    }

    @Test
    fun `verify does not fail on NotRunHere alone`() {
        verify(ToyContract, honest(setOf(Toy.ON)))
    }

    @Test
    fun `a declared cell that never occurred is Failed naming it`() {
        val bare = unrecorded(setOf(Toy.ON, Toy.OFF)) { s -> Entered.Ready(Switch(s == Toy.ON)) }
        val results = run(ToyContract, bare)
        assertTrue(
            results.all { it.outcome == Outcome.Failed("declared Clock.now → returns never occurred") },
            results.table(),
        )
    }

    @Test
    fun `what the binding does entering the state is outside the window`() {
        val seeding = ToyBinding(setOf(Toy.ON)) { s, log ->
            val clock = FixedClock.recorded(log).also { it.now() }
            Entered.Ready(Switch(s == Toy.ON, clock))
        }
        val outcome = run(clockContract(TOY_COVERS, reads = 0), seeding).single().outcome
        assertIs<Outcome.Failed>(outcome)
    }

    @Test
    fun `a call made while disposing is inside the window`() {
        val late = ToyBinding(setOf(Toy.ON)) { s, log ->
            val clock = FixedClock.recorded(log)
            Entered.Ready(Switch(s == Toy.ON, clock)) { clock.now() }
        }
        assertEquals(Outcome.Passed, run(clockContract(TOY_COVERS, reads = 0), late).single().outcome)
    }

    @Test
    fun `a failing body is reported as itself, not as a missing cell`() {
        val wrong = unrecorded(setOf(Toy.ON, Toy.OFF)) { Entered.Ready(Switch(false)) }
        val on = run(ToyContract, wrong).single { it.clauseId == "ON_READS_ON" }.outcome
        assertTrue(on is Outcome.Failed && "never occurred" !in on.message, on.render())
    }

    @Test
    fun `a one-of group needs one of its cells and no more`() {
        val group = cells {
            oneOf {
                on<Clock>().answers(Clock::now).returns()
                on<Clock>().answers(Clock::timeZone).returns()
            }
        }
        assertEquals(Outcome.Passed, run(clockContract(group), honest(setOf(Toy.ON))).single().outcome)
        assertEquals(
            Outcome.Failed("none of Clock.now → returns | Clock.timeZone → returns occurred"),
            run(clockContract(group, reads = 0), honest(setOf(Toy.ON))).single().outcome,
        )
    }

    @Test
    fun `the log records only inside its window`() {
        val log = CallLog()
        log.record("before")
        log.open()
        log.record("inside")
        log.close()
        log.record("after")
        assertEquals(setOf("inside"), log.cells)
    }
}
