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

    @Test
    fun `the domain holds values and verdicts - each impurity is a finding`() {
        val findings = DomainPurity().lint(
            """
            fun topLevel() = Unit
            var counter = 0
            val String.shout: String get() = uppercase()
            class Holder(var changing: Int, val rule: (Int) -> Boolean) {
                val cache = mutableListOf<Int>()
                val names: MutableList<String> = ArrayList()
                val bytes = ByteArray(4)
                suspend fun waits() = Unit
                fun takes(block: (Int) -> Unit) = block(1)
                val later: suspend () -> Unit get() = {}
                fun reads() = Clock.System.now()
                fun rolls() = Random.nextInt()
                fun says() = println("x")
            }
            """.trimIndent(),
        )
        assertEquals(14, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a pure value passes - a local var, a lazy memo and a list built and returned`() {
        val findings = DomainPurity().lint(
            """
            class Range private constructor(val from: Int, val until: Int) {
                val span by lazy { until - from }
                fun steps(): List<Int> {
                    var at = from
                    return buildList { while (at < until) add(at++) }
                }
                companion object {
                    private val CANONICAL = Regex("[0-9]+")
                    fun of(from: Int, until: Int): Range? = if (from <= until) Range(from, until) else null
                    fun isCanonical(text: String): Boolean = CANONICAL.matches(text)
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }
}
