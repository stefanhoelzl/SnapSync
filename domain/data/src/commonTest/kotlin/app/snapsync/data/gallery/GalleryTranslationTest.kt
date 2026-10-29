package app.snapsync.data.gallery

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.time.Timestamp
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The gallery's vocabulary as the selection speaks it: what the platform says, translated, never judged. */
class GalleryTranslationTest {

    private fun asset(
        id: String = "ABC-1/L0/001",
        created: Instant? = Instant.parse("2026-07-15T10:00:00.750Z"),
        isVideo: Boolean = false,
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isEdited: Boolean = false,
        pixelArea: Long? = 12_000_000,
    ) = GalleryAsset(PlatformAssetId(id), created, isScreenshot, isScreenRecording, isVideo, isEdited, pixelArea)

    @Test
    fun `a dated photo's facts are what the platform said`() {
        val facts = asset(isEdited = true).facts()
        assertEquals(PlatformAssetId("ABC-1/L0/001").assetId(), facts.assetId)
        assertEquals(
            CaptureDate.Dated(Timestamp.of(Instant.parse("2026-07-15T10:00:00Z"))),
            facts.captureDate,
            "to the second",
        )
        assertEquals(MediaKind.PHOTO, facts.kind)
        assertTrue(facts.isEdited)
        assertEquals(12_000_000, assertIs<PixelArea.Known>(facts.pixelArea).pixels)
    }

    @Test
    fun `an undated asset with no dimensions is undated, of unknown area`() {
        val facts = asset(created = null, pixelArea = null).facts()
        assertEquals(CaptureDate.Undated, facts.captureDate)
        assertEquals(PixelArea.Unknown, facts.pixelArea)
        assertEquals(PixelArea.Unknown, asset(pixelArea = 0).facts().pixelArea, "a zero area is not known")
        assertEquals(PixelArea.Unknown, asset(pixelArea = -4).facts().pixelArea)
    }

    @Test
    fun `the platform's marks name the kind - a recording is a video, a screenshot a still`() {
        assertEquals(MediaKind.PHOTO, asset().facts().kind)
        assertEquals(MediaKind.SCREENSHOT, asset(isScreenshot = true).facts().kind)
        assertEquals(MediaKind.VIDEO, asset(isVideo = true).facts().kind)
        assertEquals(MediaKind.SCREEN_RECORDING, asset(isVideo = true, isScreenRecording = true).facts().kind)
        assertEquals(MediaKind.SCREEN_RECORDING, asset(isScreenshot = true, isScreenRecording = true).facts().kind)
        assertEquals(
            MediaKind.VIDEO,
            asset(isVideo = true, isScreenshot = true).facts().kind,
            "a video is never a still",
        )
    }

    @Test
    fun `only a full or a partial grant leaves something to read`() {
        assertEquals(
            mapOf(
                LibraryGrant.GRANTED to PhotoAccess.FULL,
                LibraryGrant.LIMITED to PhotoAccess.LIMITED,
                LibraryGrant.DENIED to PhotoAccess.NONE,
                LibraryGrant.NOT_DETERMINED to PhotoAccess.NONE,
            ),
            LibraryGrant.entries.associateWith { it.photoAccess() },
        )
    }

    @Test
    fun `an asset's id finds the platform's asset again`() {
        for (platform in listOf("ABC-1/L0/001", "42", "a_b~c")) {
            val id: AssetId = PlatformAssetId(platform).assetId()
            assertEquals(PlatformAssetId(platform), id.platformId(), platform)
        }
        assertEquals("ABC-1_L0_001", PlatformAssetId("ABC-1/L0/001").assetId().value, "the app's id is canonical")
    }
}
