package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class DetektIncludesTest {

    private val config = """
        snapsync:
          DataShape:
            # Data translates.
            active: true
            includes:
              - '**/core/data/src/commonMain/**'
              - "**/core/gone/src/commonMain/**"
          HardCodedUiText:
            includes: ['**/ui/screens/src/commonMain/**']
            active: true
          OneCallPerStep:
            active: true
    """.trimIndent()

    @Test
    fun `a rule's includes are read in both list forms`() {
        assertEquals(
            listOf(
                "**/core/data/src/commonMain/**",
                "**/core/gone/src/commonMain/**",
                "**/ui/screens/src/commonMain/**",
            ),
            DetektIncludes.globs(config),
        )
    }

    @Test
    fun `a glob that matches no source file is named`() {
        val files = listOf(
            "/repo/core/data/src/commonMain/kotlin/app/snapsync/data/Store.kt",
            "/repo/ui/screens/src/commonMain/kotlin/app/snapsync/ui/Screen.kt",
        )
        assertEquals(
            listOf("**/core/gone/src/commonMain/**"),
            DetektIncludes.unmatched(DetektIncludes.globs(config), files),
        )
    }
}
