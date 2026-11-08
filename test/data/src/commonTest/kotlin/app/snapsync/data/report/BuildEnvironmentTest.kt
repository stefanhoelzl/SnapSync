package app.snapsync.data.report

import app.snapsync.domain.report.BuildLabel
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.inMemoryExtensionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The build, OS and device a report names, read from the build and the extension's registration when asked. */
class BuildEnvironmentTest {

    private val build = BuildInfoMock(buildNumber = "42", osVersion = "26.1", deviceModel = "SE2").port()

    @Test
    fun `the report names the build and which uploaders the OS carries`() {
        val carried = BuildEnvironment(build, inMemoryExtensionRegistry(MutableStateFlow(false))).environment()

        assertEquals(BuildLabel(build.appVersion, "42"), carried.build)
        assertEquals("26.1", carried.osVersion)
        assertEquals("SE2", carried.deviceModel)
        assertEquals("app+extension", carried.uploaders)
        assertEquals(build.uploadHost, carried.uploadBase)
        assertEquals("app", BuildEnvironment(build, inMemoryExtensionRegistry()).environment().uploaders)
    }
}
