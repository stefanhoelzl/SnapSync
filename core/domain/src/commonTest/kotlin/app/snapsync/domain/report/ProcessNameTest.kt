package app.snapsync.domain.report

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessNameTest {
    @Test
    fun `a process is named by its id`() = assertEquals("app.snapsync", ProcessName.of("app.snapsync", "ios"))

    @Test
    fun `a process with no id of its own is named by its platform`() = assertEquals("ios", ProcessName.of(null, "ios"))
}
