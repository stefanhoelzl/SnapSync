package app.snapsync.services.upload

import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJobState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-job decisions over a platform's upload jobs (capability `background-upload`) — what the PhotoKit adapter
 * decided until phase 11f, and the upload services decide now for every platform.
 */
class UploadJobDecisionsTest {

    @Test
    fun `a job with a destination is emitted with its facts`() {
        val emit = assertIs<FetchedJob.Emit>(
            classifyFetchedJob("/api/v2/files/devices/D/k/primary", UploadJobState.FAILED, UploadError.Unknown("x:1")),
        )
        assertEquals("/api/v2/files/devices/D/k/primary", emit.destinationPath)
        assertEquals(UploadJobState.FAILED, emit.state)
        assertEquals(UploadError.Unknown("x:1"), emit.error)
    }

    /** A job whose destination cannot be recovered must still be acknowledged, or PhotoKit reports error 50008. */
    @Test
    fun `a job with no destination is drained rather than dropped`() {
        assertEquals(FetchedJob.AcknowledgeToDrain, classifyFetchedJob(null, UploadJobState.SUCCEEDED, null))
    }

    @Test
    fun `a succeeded job is COMPLETED and is never re-created`() {
        for (live in listOf(true, false)) {
            val disposition = terminalDisposition(UploadJobState.SUCCEEDED, resourceIsLive = live)
            assertEquals(TerminalOutcome.COMPLETED, disposition.outcome, "resourceIsLive=$live")
            assertFalse(disposition.reCreate, "a succeeded job has nothing to re-create (live=$live)")
        }
    }

    /**
     * Every non-success terminal state records `FAILED`, returning its row to `DISCOVERED` — stated over the whole enum,
     * UNKNOWN (an untaught platform value) included, so a `when` growing an arm that quietly changed one is caught.
     */
    @Test
    fun `every non-succeeded terminal state records FAILED`() {
        for (state in UploadJobState.entries.filter { it != UploadJobState.SUCCEEDED }) {
            assertEquals(TerminalOutcome.FAILED, terminalDisposition(state, resourceIsLive = true).outcome, "state=$state")
        }
    }

    /** Re-creation is gated on the resource, not on the state. */
    @Test
    fun `a failure is re-created only while its resource is live`() {
        for (state in UploadJobState.entries.filter { it != UploadJobState.SUCCEEDED }) {
            assertTrue(terminalDisposition(state, resourceIsLive = true).reCreate, "state=$state")
            assertFalse(terminalDisposition(state, resourceIsLive = false).reCreate, "state=$state")
        }
    }

    @Test
    fun `the content type is the stored one then the resource type then generic`() {
        assertEquals("image/heic", jobContentType("image/heic", "public.jpeg"))
        assertEquals("public.jpeg", jobContentType(null, "public.jpeg"))
        assertEquals("application/octet-stream", jobContentType(null, null))
    }

    private fun failed(path: String): FetchedJob = classifyFetchedJob(path, UploadJobState.FAILED, null)

    @Test
    fun `a v2 retry finds its job by the recorded destination although the last segment is the role`() = runTest {
        // Comparing the last segment (`primary`) to the key matched nothing, so every free retry was lost.
        val recorded = mapOf("/api/v2/files/devices/D/ABC-123/primary" to "ABC-123-primary.heic")
        val candidates = listOf(
            "other" to failed("/api/v2/files/devices/D/XYZ-9/primary"),
            "mine" to failed("/api/v2/files/devices/D/ABC-123/primary"),
        )
        assertEquals("mine", retryJobMatching(candidates, "ABC-123-primary.heic") { recorded[it.destinationPath] })
    }

    @Test
    fun `no candidate resolving to the key yields none and a job with no destination never matches`() = runTest {
        val candidates = listOf(
            "drained" to FetchedJob.AcknowledgeToDrain,
            "other" to failed("/api/v2/files/devices/D/XYZ-9/primary"),
        )
        assertNull(retryJobMatching(candidates, "ABC-123-primary.heic") { "XYZ-9-primary.heic" })
    }

    private val v2 = "/api/v2/files/devices/D/ABC-123/primary"
    private val v1 = "/api/v1/files/devices/D/ABC-123-primary.heic"

    @Test
    fun `a job whose destination the ledger recorded belongs to that row`() {
        assertEquals(JobRow.Found("ABC-123-primary.heic"), jobRowOf(v2, "ABC-123-primary.heic"))
    }

    /** The photo left the library or the selection while its upload was in flight: expected, not a fault. */
    @Test
    fun `a byte-route job whose row the walk removed is pruned`() {
        assertEquals(JobRow.Pruned, jobRowOf(v2, null))
    }

    /** The v1 fallback is retired (`changes/retire-legacy-key-fallback`, D2): its row may still be REQUESTED. */
    @Test
    fun `a v1 destination is unmappable and never resolved from its last segment`() {
        assertEquals(JobRow.Unmappable, jobRowOf(v1, null))
    }

    @Test
    fun `a destination of no byte-route shape is unmappable`() {
        assertEquals(JobRow.Unmappable, jobRowOf("/something/else", null))
        assertEquals(JobRow.Unmappable, jobRowOf("/api/v2/files/devices/D/a/b/c", null))
    }

    @Test
    fun `only an http or https URL with a host is an upload destination`() {
        assertTrue(isUploadDestination("https://edge.example/api/v2/files/devices/D/k/primary"))
        assertTrue(isUploadDestination("http://127.0.0.1:18099/api/v2/x"))
        assertFalse(isUploadDestination(""), "since iOS 17 NSURL parses an empty string")
        assertFalse(isUploadDestination("file:///tmp/x"))
        assertFalse(isUploadDestination("https:///no-host"))
    }
}
