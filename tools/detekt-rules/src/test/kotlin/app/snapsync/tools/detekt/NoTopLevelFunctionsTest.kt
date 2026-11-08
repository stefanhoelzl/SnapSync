package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class NoTopLevelFunctionsTest {

    @Test
    fun `a top-level function is reported, a member and a local one are not`() {
        val findings = NoTopLevelFunctions().lint(
            """
            fun loose() = 1
            private fun helper() = 2
            internal fun String.extension() = length
            class Owner {
                fun member() = 3
                fun withLocal(): Int {
                    fun local() = 4
                    return local()
                }
                companion object { fun of() = Owner() }
            }
            object Codec { fun spell() = "x" }
            """.trimIndent(),
        )
        assertEquals(3, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `a composition's composing function is allowed by name`() {
        val findings = NoTopLevelFunctions(TestConfig("allowed" to listOf("composeApp"))).lint(
            """
            fun composeApp() = Unit
            fun composeOther() = Unit
            """.trimIndent(),
        )
        assertEquals(1, findings.size, findings.map { it.message }.toString())
    }
}
