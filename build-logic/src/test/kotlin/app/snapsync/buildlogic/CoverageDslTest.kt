package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageDslTest {

    private fun problems(configure: ModuleCoverage.() -> Unit, known: Set<String> = setOf(":m", ":other")) =
        ModuleCoverage(":m").apply(configure).problems { it in known }

    @Test
    fun `an entry settling both measurements is well-formed`() {
        assertEquals(emptyList(), problems({ unit(alsoCoveredBy = listOf(":other")); integration(72, 41) }))
        assertEquals(emptyList(), problems({ unit(); integration { skipped("an adapter") } }))
        assertEquals(emptyList(), problems({ skipped("test equipment") }))
    }

    @Test
    fun `a measurement left unsettled is named`() {
        assertEquals(
            listOf(
                ":m does not settle its integration measurement: integration(…), or integration { skipped(\"<reason>\") }",
            ),
            problems({ unit() }),
        )
    }

    @Test
    fun `a skip needs a reason, and a module skipped as a whole declares nothing else`() {
        assertEquals(
            listOf(":m skips [everything] with no reason", ":m is skipped as a whole, yet declares unit()"),
            problems({ skipped(" "); unit() }),
        )
    }

    @Test
    fun `targets and a skip of the same measurement contradict each other`() {
        assertEquals(
            listOf(":m sets integration targets, yet skips the measurement"),
            problems({ unit(); integration(72, 41) { skipped("why") } }),
        )
        assertEquals(
            listOf(":m declares integration { } with no target: skip it with a reason, or set one"),
            problems({ unit(); integration { skipped("generated", packages = listOf("a.b")) } }),
        )
    }

    @Test
    fun `a target outside 0 to 100 and an unknown covering module are named`() {
        assertEquals(
            listOf(":m sets an integration target of 101 %, outside 0..100", ":m is also covered by :nope, which is not a module of this build"),
            problems({ unit(alsoCoveredBy = listOf(":nope")); integration(101, 85) }),
        )
    }
}
