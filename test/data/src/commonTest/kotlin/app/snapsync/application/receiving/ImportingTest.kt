package app.snapsync.application.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ImportCorrelation
import app.snapsync.data.receiving.ImportMarkerWrites
import app.snapsync.domain.receiving.TransferFacts
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.wake.StagedImports
import app.snapsync.levers.WriteKind
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import drain, one staged photo at a time: imported and its bytes freed, unimportable and settled for good,
 * deferred and retried — and what follows it: the bytes of imports confirmed earlier released, unclaimed files swept,
 * and the imports a dead process left unconfirmed adjudicated against the library.
 */
@OptIn(EntryPointCall::class)
class ImportingTest {

    private val r = Receiver()
    private val membership = r.joined()
    private val importNext = ImportNextStaged(r.queue, r.releases, r.libraryImport)
    private val markers = ImportMarkerWrites(r.file)

    /** [assets] planned, downloaded and recorded as staged. */
    private suspend fun staged(vararg assets: String) {
        r.listed += assets.map { Listed(it) }
        r.position = r.listed.size.toLong()
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )
        val stage = StageDownload(r.staging, r.opening)
        val record = RecordStaged(r.queue, r.opening)
        r.download.started.forEachIndexed { i, tag ->
            record(stage(tag, TransferFacts(200, -1, 3), r.temporary("t$i")) as StageOutcome.Landed)
        }
    }

    private suspend fun drained(pass: ImportPass = ImportPass(album = null)): Int {
        var steps = 0
        while (importNext(recognised(membership), pass)) steps++
        return steps
    }

    @Test
    fun `each staged photo is imported into the album and its bytes freed, one at a time`() = runTest {
        staged("A", "B")
        val pass = ImportPass(album = "album-1")

        assertEquals(2, drained(pass))

        assertEquals(2, r.importer.requests.size)
        assertEquals("album-1", r.importer.requests.first().album?.value)
        assertEquals("album-1", pass.landedIn, "the album held a photo of this pass")
        assertEquals(emptySet(), r.shared.keys, "the staged bytes are freed")
        assertEquals(StagedImports.NONE, r.counts.stagedImports())
    }

    @Test
    fun `a pass with no album lands in none`() = runTest {
        staged("A")
        val pass = ImportPass(album = null)
        drained(pass)
        assertNull(pass.landedIn)
        assertNull(ImportPass(album = "album-1").landedIn, "nothing imported, nothing landed")
    }

    @Test
    fun `an import whose bytes the library took is settled for good and never offered again`() = runTest {
        staged("A")
        r.importer.answer = { ImportOutcome.Failed("undecodable", consumedResources = true) }

        val lines = linesOf("ImportNextStaged") { assertEquals(1, drained()) }

        assertTrue(lines.any { it.severity == Severity.Error }, "a photo that never arrives is an error")
        assertEquals(StagedImports.NONE, r.counts.stagedImports())
    }

    @Test
    fun `a failed import whose row moved on meanwhile is not settled over`() = runTest {
        staged("A")
        r.importer.answer = {
            markers.placeholder(it.correlation, PlatformAssetId("created-meanwhile"))
            ImportOutcome.Failed("cut short", consumedResources = true)
        }

        val lines = linesOf("ImportNextStaged") { drained() }

        assertTrue("an import failed, but its row already settled" in lines.messages)
    }

    @Test
    fun `an unimportable photo the store cannot record is told`() = runTest {
        staged("A")
        r.importer.answer = { ImportOutcome.Failed("undecodable", consumedResources = true) }
        r.fail("downloadAsset", WriteKind.UPDATE)

        val lines = linesOf("ImportNextStaged") { drained() }

        assertTrue("an unimportable photo could not be recorded" in lines.messages)
    }

    @Test
    fun `an import that took nothing is deferred and retried by a later pass`() = runTest {
        staged("A")
        r.importer.answer = { ImportOutcome.Failed("busy") }

        assertEquals(1, drained(), "never offered twice in one pass")
        assertEquals(StagedImports.REMAIN, r.counts.stagedImports())

        r.importer.answer = { ImportOutcome.Imported(PlatformAssetId("imported-later")) }
        assertEquals(1, drained())
        assertEquals(StagedImports.NONE, r.counts.stagedImports())
    }

    @Test
    fun `an import the store cannot confirm keeps its bytes for the release`() = runTest {
        staged("A")
        r.fail("downloadAsset", WriteKind.UPDATE)

        val lines = linesOf("ImportNextStaged") { drained() }

        assertTrue("an import could not be recorded" in lines.messages)
    }

    @Test
    fun `an unreadable store imports nothing and ends the drain`() = runTest {
        staged("A")
        r.unreadable()
        assertFalse(importNext(recognised(membership), ImportPass(album = null)))
        assertEquals(emptyList(), r.importer.requests)
    }

    @Test
    fun `the bytes of photos imported before their release are released`() = runTest {
        val release = ReleaseImported(r.releases)
        linesOf("ReleaseImported") { release() }.let { assertEquals(emptyList(), it.messages, "nothing held") }

        staged("A")
        val claim = (r.queue.claimNext(emptySet()) as DownloadRead.Ok).value!!
        r.queue.imported(claim, importedId("A"))
        assertTrue(r.shared.isNotEmpty())

        val lines = linesOf("ReleaseImported") { release() }

        assertEquals(listOf("released the staged bytes of imported photos"), lines.messages)
        assertEquals(emptySet(), r.shared.keys)

        r.unreadable()
        assertEquals(1, linesOf("ReleaseImported") { release() }.size, "an unreadable store is told")
    }

    @Test
    fun `unclaimed staged files are swept, and an unreadable store is told`() = runTest {
        val sweep = SweepUnclaimedStaging(r.cleanup)
        r.shared["download-staging/stray"] = byteArrayOf(1)

        assertEquals(listOf("swept unclaimed staged files"), linesOf("SweepUnclaimedStaging") { sweep() }.messages)
        assertEquals(emptySet(), r.shared.keys)

        r.unreadable()
        assertEquals(
            listOf("the unclaimed staged files could not be swept"),
            linesOf("SweepUnclaimedStaging") { sweep() }.messages,
        )
    }

    @Test
    fun `an import a dead process left unconfirmed is adjudicated against the library`() = runTest {
        val settle = SettleInterruptedImports(r.releases, r.presence)
        assertEquals(emptyList(), linesOf("SettleInterruptedImports") { settle() }, "nothing inherited, nothing asked")

        staged("A", "B")
        val a = Listed("A")
        markers.placeholder(ImportCorrelation.of(a.ref), PlatformAssetId("created-A"))
        markers.placeholder(ImportCorrelation.of(Listed("B").ref), PlatformAssetId("created-B"))
        r.library.value = listOf(LibraryAssets.photo("created-A"))
        r.fail("downloadAsset", WriteKind.UPDATE)

        val lines = linesOf("SettleInterruptedImports") { settle() }

        assertEquals(
            setOf("adjudicated", "an unconfirmed import could not be adjudicated"),
            lines.messages.toSet(),
        )

        r.unreadable()
        assertEquals(
            listOf("the unconfirmed imports could not be read"),
            linesOf("SettleInterruptedImports") { settle() }.messages,
        )
    }
}
