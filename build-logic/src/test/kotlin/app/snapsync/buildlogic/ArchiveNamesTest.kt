package app.snapsync.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArchiveNamesTest {
    @Test
    fun `a module's archive is named after its whole path, so two modules sharing a last segment never collide`() {
        assertEquals("core-composition-app", ArchiveNames.of(":core:composition:app"))
        assertEquals("adapter-generic-app", ArchiveNames.of(":adapter:generic:app"))
        assertNotEquals(ArchiveNames.of(":core:composition:app"), ArchiveNames.of(":adapter:generic:app"))
    }
}
