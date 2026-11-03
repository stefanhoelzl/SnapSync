package app.snapsync.domain.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(MintsUploadRow::class)
class ResumptionTest {

    @Test
    fun `a staged file starts where its bytes stopped, at its last byte when it all landed, or not now`() {
        assertEquals(TransferStart.At(4096), TransferStart.of(ResumePoint.From(4096), size = 10_000))
        assertEquals(TransferStart.At(0), TransferStart.of(ResumePoint.From(0), size = 10_000))
        assertEquals(TransferStart.At(9_999), TransferStart.of(ResumePoint.Landed, size = 10_000))
        assertEquals(TransferStart.NotNow, TransferStart.of(ResumePoint.Deferred, size = 10_000))
    }

    @Test
    fun `a failed transfer's file stays for the next attempt, a landed one's goes`() {
        assertEquals(StagedFile.KEPT, StagedFile.after(TransferEnd.FAILED))
        assertEquals(StagedFile.RELEASED, StagedFile.after(TransferEnd.LANDED))
    }

    @Test
    fun `an unclaimed file stays only while its row still needs the upload`() {
        val key = UploadKey.of(AssetId.parse("A")!!, ResourceRole.PRIMARY, "a.jpg")
        val detail = RowDetail(CaptureDate.Undated, "", "")
        assertEquals(StagedFile.KEPT, StagedFile.unclaimed(UploadRow.Discovered(key, detail)))
        assertEquals(StagedFile.KEPT, StagedFile.unclaimed(UploadRow.Requested(key, detail, null)))
        assertEquals(StagedFile.RELEASED, StagedFile.unclaimed(UploadRow.Completed(key, detail)))
        assertEquals(StagedFile.RELEASED, StagedFile.unclaimed(null))
    }
}
