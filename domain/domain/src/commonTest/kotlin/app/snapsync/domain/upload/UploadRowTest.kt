package app.snapsync.domain.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(MintsUploadRow::class, MintsCreatedJob::class, MintsEndedJob::class)
class UploadRowTest {

    private val asset = AssetId.parse("A1")!!
    private val key = UploadKey.of(asset, ResourceRole.PRIMARY, "IMG.JPG")
    private val dated = CaptureDate.Dated(Timestamp.of(Instant.parse("2026-07-01T10:00:00Z")))

    @Test
    fun `a key names its asset, its role and its lowercased extension, and equals its parts read back`() {
        assertEquals(asset, key.asset)
        assertEquals(ResourceRole.PRIMARY, key.role)
        assertEquals("jpg", key.extension)
        assertEquals(key, UploadKey.read(asset, ResourceRole.PRIMARY, "jpg"))
        assertNotEquals(key, UploadKey.of(asset, ResourceRole.LIVE, "IMG.JPG"))
        assertEquals("bin", UploadKey.of(asset, ResourceRole.PRIMARY, "IMG").extension)
    }

    @Test
    fun `a row with no capture date is bare - the walk fills it`() {
        assertTrue(RowDetail(CaptureDate.Undated, "", "").bare)
        assertFalse(RowDetail(dated, "image/jpeg", "IMG.JPG").bare)
    }

    @Test
    fun `each state's row carries its key and detail, and is recorded as its asset and capture date`() {
        val detail = RowDetail(dated, "image/jpeg", "IMG.JPG")
        val rows = listOf(
            UploadRow.Discovered(key, detail),
            UploadRow.Requested(key, detail, DestinationPath("/files/e/d/A1/primary")),
            UploadRow.Completed(key, detail),
        )
        rows.forEach { row ->
            assertEquals(key, row.key)
            assertEquals(detail, row.detail)
            assertEquals(asset, row.recorded.assetId)
            assertEquals(dated, row.recorded.captureDate)
        }
        assertEquals("/files/e/d/A1/primary", (rows[1] as UploadRow.Requested).destination?.path)
        assertNull(UploadRow.Requested(key, detail, destination = null).destination)
    }

    @Test
    fun `the platform's word names the key it is about`() {
        assertEquals(key, CreatedJob(key).key)
        assertEquals(key, ReportedFailure(key).key)
        assertEquals(key, ReportedLanding(key).key)
    }
}
