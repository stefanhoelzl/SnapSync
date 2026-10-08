package app.snapsync.services.downloads

import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import app.snapsync.model.PlannedResource
import app.snapsync.model.UnconfirmedImport
import app.snapsync.services.CapturingLogWriter
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an import's two platform callbacks write (capability `receiving-photos`), over the download store on a real
 * in-memory SQLite: the placeholder marks the row before the asset's creation commits — and a marker that lands on NO
 * row is said at `Error`, the only evidence a downloaded photo will be uploaded back; an imported asset confirms its
 * marker, a failed one clears the placeholder it left, and a failure that left none touches nothing.
 */
class ImportMarkersTest {

    private val ref = AssetRef("device-B", AssetId("remote-1"))
    private val marker = AssetId("local-1")
    private val downloads = DownloadService(inMemoryDatabases())
    private val log = CapturingLogWriter()
    private val markers = ImportMarkers(downloads, log.logger())

    private suspend fun planned() = downloads.plan(
        ref,
        "2026-06-01T10:00:00Z",
        listOf(PlannedResource("primary", "https://edge/x", "PRIMARY", "image/jpeg", "IMG.JPG")),
    )

    @Test
    fun `a placeholder on a planned row marks it and says nothing`() = runTest {
        planned()
        markers.placeholder(ref, marker)
        assertEquals(listOf(UnconfirmedImport(ref, marker)), downloads.unconfirmedImports())
        assertEquals(setOf(marker), downloads.suppressedLocalIds())
        assertTrue(log.lines.isEmpty(), "${log.lines}")
    }

    @Test
    fun `a placeholder that lands on no row is an Error`() = runTest {
        markers.placeholder(ref, marker)
        assertEquals(emptySet(), downloads.suppressedLocalIds())
        assertEquals(listOf(Severity.Error), log.severities)
        assertTrue("landed on NO ROW" in log.lines.single().second)
    }

    @Test
    fun `an imported asset confirms its marker`() = runTest {
        planned()
        markers.placeholder(ref, marker)
        markers.settled(ref, ImportResult.Imported(marker))
        assertTrue(downloads.isSettled(ref))
        assertEquals(emptyList(), downloads.unconfirmedImports())
        assertEquals(setOf(marker), downloads.suppressedLocalIds(), "the confirmed marker stays the suppression handle")
    }

    @Test
    fun `a failed import clears the placeholder it left`() = runTest {
        planned()
        markers.placeholder(ref, marker)
        markers.settled(ref, ImportResult.Failed("refused", placeholder = marker))
        assertEquals(emptySet(), downloads.suppressedLocalIds())
        assertEquals(emptyList(), downloads.unconfirmedImports())
        assertFalse(downloads.isSettled(ref), "a failed import is retried, not settled")
    }

    @Test
    fun `a failure that left no placeholder touches nothing`() = runTest {
        planned()
        markers.placeholder(ref, marker)
        markers.settled(ref, ImportResult.Failed("refused before the block ran"))
        assertEquals(listOf(UnconfirmedImport(ref, marker)), downloads.unconfirmedImports())
    }
}
