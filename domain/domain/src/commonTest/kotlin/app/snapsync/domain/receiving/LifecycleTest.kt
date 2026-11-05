package app.snapsync.domain.receiving

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LifecycleTest {

    @Test
    @OptIn(MintsStagedDownload::class, MintsImportedAsset::class)
    fun each_state_carries_what_the_next_step_needs() {
        val name = checkNotNull(SenderFilename.parse("IMG.HEIC"))
        val resource = StagedResource("k", "primary", "image/heic", name, StagedPath("download-staging/x"))
        val staged = StagedDownload(ref("A"), CaptureDate.Undated, listOf(resource))
        assertEquals(ref("A"), staged.ref)
        assertEquals(CaptureDate.Undated, staged.captured)
        assertEquals("download-staging/x", staged.resources.single().stagedPath.value)
        val imported = ImportedAsset(ref("A"), local("L"))
        assertEquals(ref("A"), imported.ref)
        assertEquals(local("L"), imported.created)
        val row = UnconfirmedImport(ref("A"), local("L"))
        assertEquals(local("L"), row.createdLocalId)
    }

    @Test
    fun a_photo_is_pending_until_it_settles_imported_or_unimportable() {
        assertEquals(listOf("PENDING", "IMPORTED", "UNIMPORTABLE"), DownloadState.entries.map { it.name })
    }

    @Test
    fun an_import_that_created_its_asset_is_imported() {
        assertEquals(
            local("L"),
            assertIs<ImportVerdict.Imported>(ImportVerdict.of(ImportReport.Imported(local("L")))).created,
        )
    }

    @Test
    fun a_failure_that_took_the_bytes_never_arrives_and_one_that_did_not_is_retried() {
        val taken = ImportVerdict.of(ImportReport.Failed("rejected", consumedResources = true, placeholder = null))
        assertEquals("rejected", assertIs<ImportVerdict.Unimportable>(taken).reason)
        val kept = ImportVerdict.of(ImportReport.Failed("busy", consumedResources = false, placeholder = local("P")))
        assertEquals("busy", assertIs<ImportVerdict.Deferred>(kept).reason)
    }

    @Test
    fun a_staged_resource_is_photo_video_or_paired_video_by_its_role_and_type() {
        assertEquals(ReceivedMedia.PAIRED_VIDEO, ReceivedMedia.of(ResourceRole.LIVE, "video/quicktime"))
        assertEquals(ReceivedMedia.PHOTO, ReceivedMedia.of(ResourceRole.PRIMARY, "image/heic"))
        assertEquals(ReceivedMedia.VIDEO, ReceivedMedia.of(ResourceRole.PRIMARY, "video/mp4"))
        assertEquals(ReceivedMedia.NONE, ReceivedMedia.of(ResourceRole.PRIMARY, "application/pdf"))
    }

    @Test
    fun only_a_read_of_the_whole_library_may_answer_absent() {
        assertEquals(AssetPresence.PRESENT, AssetPresence.inLibrary(find = true))
        assertEquals(AssetPresence.ABSENT, AssetPresence.inLibrary(find = false))
        assertEquals(AssetPresence.PRESENT, AssetPresence.inSelection(find = true))
        assertEquals(AssetPresence.UNKNOWN, AssetPresence.inSelection(find = false))
    }

    @Test
    fun staged_bytes_are_evidence_only_when_a_row_records_some() {
        assertEquals(StagedEvidence.NoneRecorded, StagedEvidence.of(recorded = 0, missing = 0))
        assertEquals(
            StagedEvidence.Consumed,
            StagedEvidence.of(recorded = 2, missing = 1),
            "one taken is as good as all",
        )
        assertEquals(StagedEvidence.AllPresent, StagedEvidence.of(recorded = 2, missing = 0))
    }

    @Test
    fun a_present_asset_settles_and_an_unknown_one_waits() {
        for (staged in listOf(StagedEvidence.NoneRecorded, StagedEvidence.Consumed, StagedEvidence.AllPresent)) {
            assertEquals(
                InterruptedImportVerdict.Settle,
                InterruptedImportVerdict.of(AssetPresence.PRESENT, false, staged),
            )
            assertEquals(
                InterruptedImportVerdict.Leave,
                InterruptedImportVerdict.of(AssetPresence.UNKNOWN, false, staged),
            )
        }
    }

    @Test
    fun absence_is_never_acted_on_while_the_import_runs_here() {
        assertEquals(
            InterruptedImportVerdict.Leave,
            InterruptedImportVerdict.of(AssetPresence.ABSENT, importing = true, StagedEvidence.AllPresent),
        )
    }

    @Test
    fun absence_with_the_bytes_taken_settles_with_them_there_clears_and_without_any_waits() {
        assertEquals(
            InterruptedImportVerdict.Settle,
            InterruptedImportVerdict.of(AssetPresence.ABSENT, false, StagedEvidence.Consumed),
        )
        assertEquals(
            InterruptedImportVerdict.Clear,
            InterruptedImportVerdict.of(AssetPresence.ABSENT, false, StagedEvidence.AllPresent),
        )
        assertEquals(
            InterruptedImportVerdict.Leave,
            InterruptedImportVerdict.of(AssetPresence.ABSENT, false, StagedEvidence.NoneRecorded),
        )
    }
}
