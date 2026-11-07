package app.snapsync.domain.report

import app.snapsync.domain.process.MemoryFootprint
import kotlin.test.Test
import kotlin.test.assertEquals

/** The build's facts a report and the app menu read, and the facts only the app holds, as a report reads them. */
class ReportFactsTest {

    @Test
    fun `an unknown build says so rather than naming a number nobody built`() {
        assertEquals(BuildLabel("unknown", "unknown"), BuildLabel.UNKNOWN)
    }

    @Test
    fun `a report goes to the developer or stays on this device, and nowhere else`() {
        assertEquals(listOf(ReportDestination.DEVELOPER, ReportDestination.THIS_DEVICE), ReportDestination.entries)
    }

    @Test
    fun `the environment names the build under the keys every report carried`() {
        val fields = ReportedEnvironment(
            BuildLabel("0.4", "512"),
            "iOS 26.5",
            "iPhone12,8",
            "app+extension",
            "https://x/api",
            "production",
        )
            .fields()
        assertEquals(
            mapOf(
                "app_version" to "0.4",
                "build" to "512",
                "os" to "iOS 26.5",
                "device" to "iPhone12,8",
                "uploaders_carried" to "app+extension",
                "upload_base" to "https://x/api",
                "reporter_environment" to "production",
            ),
            fields,
        )
    }

    @Test
    fun `a partial grant counts the selected photos`() {
        assertEquals(Fact.Known(2), ReportFacts.selectionPhotos(GrantStanding.LIMITED, 2))
    }

    @Test
    fun `an unread selection is failed and never zero`() {
        val unread = ReportFacts.selectionPhotos(GrantStanding.LIMITED, null)
        assertEquals(Fact.Failed("the selection has not been read yet"), unread)
        assertEquals(Fact.Known(0), ReportFacts.selectionPhotos(GrantStanding.LIMITED, 0))
    }

    @Test
    fun `any grant but a partial one has no selection to count`() {
        assertEquals(Fact.Unsupported, ReportFacts.selectionPhotos(GrantStanding.FULL, null))
        assertEquals(Fact.Unsupported, ReportFacts.selectionPhotos(GrantStanding.DENIED, 0))
    }

    @Test
    fun `a footprint is whole megabytes and an unread one unsupported`() {
        assertEquals(Fact.Known(3L), ReportFacts.megabytes(MemoryFootprint(footprintBytes = 3L * 1_048_576 + 5)))
        assertEquals(Fact.Unsupported, ReportFacts.megabytes(null))
    }

    @Test
    fun `a failed read fails every device fact with its reason`() {
        val failed = Fact.Failed("timed out")
        assertEquals(
            DeviceConditionsReading(failed, failed, failed, failed, failed, failed, failed),
            DeviceConditionsReading.failed("timed out"),
        )
    }
}
