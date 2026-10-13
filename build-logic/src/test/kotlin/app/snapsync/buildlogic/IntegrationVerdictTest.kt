package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationVerdictTest {

    private val goal = 95 to 85

    private fun measured(path: String, instr: Pair<Long, Long>, branch: Pair<Long, Long>, targets: Pair<Int, Int>) =
        Measured(path, mapOf("INSTRUCTION" to instr, "BRANCH" to branch), targets.first, targets.second)

    @Test
    fun `a module at or above its targets passes`() {
        val m = measured(":a", 72L to 100L, 41L to 100L, 72 to 41)
        assertEquals(emptyList(), IntegrationVerdict.problems(listOf(m), goal))
    }

    @Test
    fun `a drop below a target names the module, the measurement and the target`() {
        val m = measured(":a", 7199L to 10000L, 41L to 100L, 72 to 41)
        assertEquals(listOf(":a: integration instructions 71.99 % < 72 %"), IntegrationVerdict.problems(listOf(m), goal))
    }

    @Test
    fun `an exception the module no longer needs fails, per measurement`() {
        val m = measured(":a", 96L to 100L, 50L to 100L, 72 to 41)
        val problems = IntegrationVerdict.problems(listOf(m), goal)
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith(":a: integration instructions 96.00 % ≥ the goal's 95 %"))
    }

    @Test
    fun `a target above the goal is stricter, never an exception`() {
        val m = measured(":wiring", 999L to 1000L, 8L to 8L, 100 to 100)
        assertEquals(listOf(":wiring: integration instructions 99.90 % < 100 %"), IntegrationVerdict.problems(listOf(m), goal))
    }

    @Test
    fun `a module with no branches meets any branch target, and shows none`() {
        val m = measured(":ports", 95L to 100L, 0L to 0L, 95 to 85)
        assertEquals(emptyList(), IntegrationVerdict.problems(listOf(m), goal))
        assertTrue("| `:ports` | 95.00 % (95/100) | 95 % | – (0/0) | 85 % |" in IntegrationVerdict.summary(listOf(m), goal))
    }

    @Test
    fun `a module the report holds no instruction of fails rather than passing empty`() {
        val m = measured(":gone", 0L to 0L, 0L to 0L, 0 to 0)
        assertTrue(IntegrationVerdict.problems(listOf(m), goal).single().startsWith(":gone: the report holds no instruction"))
    }
}
