package app.snapsync.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private enum class Toy { ON, OFF }

private class Switch(var on: Boolean)

private object ToyContract : Contract<Toy, Switch>("Toy") {
    override val clauses = listOf(
        clause("ON_READS_ON", Toy.ON) { assertTrue(it.on) },
        clause("OFF_READS_OFF", Toy.OFF) { assertTrue(!it.on) },
    )
}

private class ToyBinding(
    override val reaches: Set<Toy>,
    private val answer: (Toy) -> Entered<Switch>,
) : Binding<Toy, Switch> {
    override val host = Host.JVM
    override val kind = BindingKind.Fake
    override fun create(state: Toy, clauseId: String) = answer(state)
}

private fun honest(reaches: Set<Toy>) = ToyBinding(reaches) { s ->
    if (s in reaches) Entered.Ready(Switch(s == Toy.ON)) else Entered.Unreachable("toy cannot be $s")
}

class RunnerTest {

    @Test
    fun `a binding reaching every state passes every clause`() {
        val results = run(ToyContract, honest(setOf(Toy.ON, Toy.OFF)))
        assertEquals(listOf("ON_READS_ON", "OFF_READS_OFF"), results.map { it.clauseId })
        assertTrue(results.all { it.outcome == Outcome.Passed })
    }

    @Test
    fun `an undeclared unreachable state is NotRunHere with the binding's reason`() {
        val off = run(ToyContract, honest(setOf(Toy.ON))).single { it.clauseId == "OFF_READS_OFF" }
        assertEquals(Outcome.NotRunHere("toy cannot be OFF"), off.outcome)
    }

    @Test
    fun `a declared state answered Unreachable is Failed - the declaration lied`() {
        val liar = ToyBinding(setOf(Toy.ON, Toy.OFF)) { Entered.Unreachable("nope") }
        assertTrue(run(ToyContract, liar).all { it.outcome is Outcome.Failed })
    }

    @Test
    fun `an undeclared state answered Ready is Failed and still disposed`() {
        var disposed = 0
        val sneaky = ToyBinding(emptySet()) { s -> Entered.Ready(Switch(s == Toy.ON)) { disposed++ } }
        assertTrue(run(ToyContract, sneaky).all { it.outcome is Outcome.Failed })
        assertEquals(2, disposed)
    }

    @Test
    fun `a violated clause is Failed and the subject is disposed`() {
        var disposed = 0
        val wrong = ToyBinding(setOf(Toy.ON, Toy.OFF)) { Entered.Ready(Switch(false)) { disposed++ } }
        val results = run(ToyContract, wrong)
        assertIs<Outcome.Failed>(results.single { it.clauseId == "ON_READS_ON" }.outcome)
        assertEquals(Outcome.Passed, results.single { it.clauseId == "OFF_READS_OFF" }.outcome)
        assertEquals(2, disposed)
    }

    @Test
    fun `a divergence is Diverged and not Failed`() {
        val contract = object : Contract<Toy, Replayer>("Replay") {
            override val clauses = listOf(clause("C", Toy.ON) { it.answer("unrecorded()") })
        }
        val binding = object : Binding<Toy, Replayer> {
            override val host = Host.IOS_DEVICE_APP
            override val kind = BindingKind.Replay
            override val reaches = setOf(Toy.ON)
            override fun create(state: Toy, clauseId: String) = Entered.Ready(Replayer(clauseId, emptyList()))
        }
        assertIs<Outcome.Diverged>(run(contract, binding).single().outcome)
    }

    @Test
    fun `a divergence raised while disposing is Diverged even after the body passed`() {
        val binding = ToyBinding(setOf(Toy.ON, Toy.OFF)) { s ->
            Entered.Ready(Switch(s == Toy.ON)) { throw Divergence("a recorded call was never made") }
        }
        assertTrue(run(ToyContract, binding).all { it.outcome is Outcome.Diverged })
    }

    @Test
    fun `verify fails once with the whole table`() {
        val wrong = ToyBinding(setOf(Toy.ON, Toy.OFF)) { Entered.Ready(Switch(false)) }
        val error = assertFailsWith<AssertionError> { verify(ToyContract, wrong) }
        val message = error.message.orEmpty()
        assertTrue("1 of 2 clauses failed" in message, message)
        assertTrue("OFF_READS_OFF Passed" in message, "the table lists passing clauses too: $message")
    }

    @Test
    fun `verify does not fail on NotRunHere alone`() {
        verify(ToyContract, honest(setOf(Toy.ON)))
    }
}
