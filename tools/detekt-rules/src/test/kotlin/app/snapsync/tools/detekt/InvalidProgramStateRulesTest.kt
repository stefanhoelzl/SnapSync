package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class InvalidProgramStateRulesTest {

    @Test
    fun `the core throws only an InvalidProgramState, or rethrows`() {
        val findings = OnlyInvalidProgramStateThrows().lint(
            """
            fun ok(key: ByteArray?, state: InvalidProgramState): Int {
                try { work() } catch (e: CancellationException) { throw e }
                listOf(1).forEach { failure -> throw failure }
                if (key == null) throw InvalidProgramState("the mint asks for exactly the count")
                throw state
            }
            fun refused(id: String?, xs: List<Int>, m: Map<String, Int>) {
                throw IllegalStateException("no")
                val e = RuntimeException()
                throw e
                require(xs.isNotEmpty())
                check(true)
                error("no")
                requireNotNull(id)
                checkNotNull(id)
                TODO()
                id!!
                xs.first()
                xs.last { it > 1 }
                xs.single()
                xs.reduce { a, b -> a + b }
                m.getValue("k")
                runCatching { 1 }.getOrThrow()
            }
            fun quiet(xs: List<Int>, m: Map<String, Int>) {
                xs.firstOrNull()
                m["k"]
                id?.length
            }
            """.trimIndent(),
        )
        assertEquals(15, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `an InvalidProgramState names its guarantee in one literal`() {
        val findings = InvalidProgramStateBecause().lint(
            """
            fun f(n: Int, why: String) {
                throw InvalidProgramState("the count asked is the count answered")
                throw InvalidProgramState("count ${'$'}n")
                throw InvalidProgramState(why)
            }
            """.trimIndent(),
        )
        assertEquals(2, findings.size, findings.map { it.message }.toString())
    }
}
