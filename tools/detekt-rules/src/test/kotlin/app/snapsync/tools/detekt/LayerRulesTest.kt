package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class LayerRulesTest {

    @Test
    fun `an entry point holds no if, and a when only over a subject with every case named`() {
        val findings = EntryPointShape().lint(
            """
            fun run(due: Boolean, n: Int) {
                if (due) println()
                when { n > 1 -> println() }
                when (n) { 1 -> println(); else -> Unit }
                when (due) { true -> println(); false -> Unit }
            }
            """.trimIndent(),
        )
        assertEquals(3, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a step holds one call`() {
        val findings = OneCallPerStep().lint(
            """
            fun required(block: () -> Unit) = block()
            fun isolated(name: String, block: () -> Unit) = block()
            fun run() {
                required { println() }
                isolated("two") {
                    println()
                    println()
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a coroutine started outside the runtime is a finding`() {
        val findings = CoroutinesInScheduling().lint(
            """
            fun launch(block: () -> Unit) = block()
            fun async(block: () -> Unit) = block()
            fun detached(block: () -> Unit) = block()
            fun run() {
                launch { }
                async { }
                detached { }
            }
            """.trimIndent(),
        )
        assertEquals(2, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `behaviour crosses as an interface - never as a function-typed field`() {
        val findings = NoFunctionTypes().lint(
            """
            class Seam(private val call: () -> Unit, private val maybe: (() -> Int)?, private val name: String)
            class Slot { val handler: (String) -> Unit = {} }
            fun interface Callback { fun call() }
            typealias Handler = () -> Unit
            fun steps(block: () -> Unit) = block()
            interface Named { fun call() }
            """.trimIndent(),
        )
        assertEquals(5, findings.size, findings.map { it.message }.toString())
    }
}
