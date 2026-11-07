package app.snapsync.domain.crash

import app.snapsync.domain.report.Platform
import app.snapsync.domain.report.ReportDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Whether a build reports, and what its channel is started with — every value a fact of the build. */
class ReportingTest {

    private fun reporting(
        dsn: String? = "https://key@ingest/1",
        version: String = "1.4",
        platform: Platform = Platform.IOS,
        buildNumber: String = "2512",
        processId: String? = "app.snapsync",
    ) = ReportingBuild(dsn, version, "production", platform, buildNumber, processId).reporting()

    private fun options(reporting: Reporting) = assertIs<Reporting.To>(reporting).options

    @Test
    fun `a build with no destination reports nowhere and keeps a report on the device`() {
        val nowhere = reporting(dsn = null)
        assertEquals(Reporting.Nowhere, nowhere)
        assertEquals(ReportDestination.THIS_DEVICE, nowhere.destination)
    }

    @Test
    fun `an ios build reports its release, environment, platform and process and leaves the build number unset`() {
        val reporting = reporting()
        assertEquals(ReportDestination.DEVELOPER, reporting.destination)
        val options = options(reporting)
        assertEquals("https://key@ingest/1", options.dsn)
        assertEquals("1.4", options.release)
        assertEquals("production", options.environment)
        assertNull(options.dist, "a cached iOS crash keeps the build it crashed in only while dist is unset")
        assertEquals(mapOf("platform" to "ios", "process" to "app.snapsync"), options.tags)
        assertEquals(EventBounds.MAX_BREADCRUMBS, options.maxBreadcrumbs)
    }

    @Test
    fun `an android build stamps the build it runs`() {
        assertEquals("2512", options(reporting(platform = Platform.ANDROID)).dist)
    }

    @Test
    fun `a blank build number stamps nothing`() {
        assertNull(options(reporting(platform = Platform.ANDROID, buildNumber = " ")).dist)
    }

    @Test
    fun `a blank version names no release, and a process with no id or a blank one no process tag`() {
        assertNull(options(reporting(version = "")).release)
        assertEquals(mapOf("platform" to "ios"), options(reporting(processId = null)).tags)
        assertEquals(mapOf("platform" to "ios"), options(reporting(processId = " ")).tags)
    }
}
