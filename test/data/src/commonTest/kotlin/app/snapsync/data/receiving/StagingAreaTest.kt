package app.snapsync.data.receiving

import app.snapsync.domain.receiving.StagedEvidence
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.ports.FileResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The download staging area (capability `receiving-photos`): a finished download's bytes taken over, a staged file
 * located for the library, released, listed for a sweep — a listing it could not make is never "nothing staged" — and
 * read as evidence of an import that never reported, where an inability to look counts as present.
 */
class StagingAreaTest {
    private val files = ScriptedFiles()
    private val staging = StagingArea(files)
    private val at = StagedPath("download-staging/e/d/a-primary.heic")

    @Test
    fun `a finished download's bytes are moved into staging, or reported not kept`() {
        assertSame(Landed.Kept, staging.land(files.temp("t1"), at))
        assertTrue(files.has(at.value))

        files.adopt = FileResult.Failed("disk full")
        assertIs<Landed.NotKept>(staging.land(files.temp("t2"), at))
    }

    @Test
    fun `a staged file is located on this device, unless the shared area cannot be reached`() {
        assertIs<Located.At>(staging.locate(at))
        files.locate = FileResult.AreaUnavailable
        assertIs<Located.Unreachable>(staging.locate(at))
    }

    @Test
    fun `a release deletes each file, a missing one being no error and a refused one left for a later sweep`() = runTest {
        files.staged(at.value)
        staging.release(listOf(at, StagedPath("download-staging/missing")))
        assertEquals(false, files.has(at.value))

        for (refusal in REFUSALS) {
            files.staged(at.value)
            files.delete = refusal
            staging.release(listOf(at))
            assertTrue(files.has(at.value), "$refusal")
        }
    }

    @Test
    fun `the staged files are listed, and a listing that cannot be made is unreadable`() {
        files.staged(at.value)
        assertEquals(listOf(at), assertIs<Listed.Files>(staging.listed()).paths)

        for (refusal in REFUSALS) {
            files.list = refusal
            assertIs<Listed.Unreadable>(staging.listed(), "$refusal")
        }
    }

    @Test
    fun `a row's staged files are evidence - any one gone is consumed, and one it cannot look for is present`() =
        runTest {
            val other = StagedPath("download-staging/e/d/a-live.mov")
            assertSame(StagedEvidence.NoneRecorded, staging.evidence(emptyList()))
            files.staged(at.value)
            files.staged(other.value)
            assertSame(StagedEvidence.AllPresent, staging.evidence(listOf(at, other)))
            files.disk.remove(app.snapsync.ports.FileArea.SHARED, other.value)
            assertSame(StagedEvidence.Consumed, staging.evidence(listOf(at, other)))

            for (refusal in REFUSALS) {
                files.exists = refusal
                assertSame(StagedEvidence.AllPresent, staging.evidence(listOf(other)), "$refusal")
            }
        }
}

/** Every way the device's files refuse a look, short of the file being absent. */
private val REFUSALS: List<app.snapsync.ports.FileAccess<Nothing>> =
    listOf(FileResult.AreaUnavailable, FileResult.Denied("no"), FileResult.Failed("io"))
