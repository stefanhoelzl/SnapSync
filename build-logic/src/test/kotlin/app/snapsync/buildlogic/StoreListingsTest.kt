package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreListingsTest {
    private val expected = listOf("en-US", "de-DE")
    private val en = mapOf("_comment" to listOf("x"), "name" to "N", "apple" to mapOf("subtitle" to "S"))
    private val de = mapOf("name" to "N", "apple" to mapOf("subtitle" to "U"))

    @Test
    fun `a family holding every language with the base's keys passes, its comment exempt`() {
        assertEquals(
            emptyList(),
            StoreListings.problems(mapOf("listing" to mapOf("en-US" to en, "de-DE" to de)), expected),
        )
    }

    @Test
    fun `a language the app speaks without its file fails`() {
        val problems = StoreListings.problems(mapOf("listing" to mapOf("en-US" to en)), expected)
        assertEquals(listOf("listing has no de-DE.json"), problems)
    }

    @Test
    fun `a file for a language the app does not speak fails`() {
        val files = mapOf("en-US" to en, "de-DE" to de, "fr-FR" to de)
        val problems = StoreListings.problems(mapOf("listing" to files), expected)
        assertEquals(listOf("listing has fr-FR.json, a language the app does not speak"), problems)
    }

    @Test
    fun `a translation missing a nested key fails, naming it`() {
        val files = mapOf("en-US" to en, "de-DE" to mapOf("name" to "N", "apple" to emptyMap<String, Any>()))
        val problems = StoreListings.problems(mapOf("listing" to files), expected)
        assertEquals(listOf("listing/de-DE.json lacks [apple.subtitle], which en-US.json has"), problems)
    }

    @Test
    fun `a translation with a key the base lacks fails, naming it`() {
        val files = mapOf("en-US" to en, "de-DE" to de + ("extra" to "E"))
        val problems = StoreListings.problems(mapOf("listing" to files), expected)
        assertEquals(listOf("listing/de-DE.json has [extra], which en-US.json lacks"), problems)
    }

    @Test
    fun `every family is judged`() {
        val families = mapOf("listing" to mapOf("en-US" to en), "screenshots" to mapOf("en-US" to en))
        val problems = StoreListings.problems(families, expected)
        assertTrue(problems.any { it.startsWith("listing ") } && problems.any { it.startsWith("screenshots ") })
    }
}
