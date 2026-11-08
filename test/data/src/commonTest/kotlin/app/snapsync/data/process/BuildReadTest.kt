package app.snapsync.data.process

import app.snapsync.domain.report.BuildLabel
import app.snapsync.mock.BuildInfoMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The build's facts the screen states: its store page and its label, read when asked. */
class BuildReadTest {
    @Test
    fun `the screen states the build's label and its store page`() {
        val build = BuildInfoMock(buildNumber = "42").port()
        val read = BuildRead(build)

        assertEquals(BuildLabel(build.appVersion, "42"), read.label())
        assertNull(read.store(), "a build that names no store page")
    }
}
