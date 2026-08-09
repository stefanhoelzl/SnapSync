package app.snapsync.ios.urlsession

import app.snapsync.model.UploadError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app-driven tier's outcome decisions (capability `ios-url-session-upload`) — the symmetric
 * counterpart of `PhotoKitJobMappingTest`.
 *
 * The `taskDescription = null` case below is the same kind of guard the PhotoKit tests carry: it is a
 * distinct outcome rather than a silent early return, and asserting it here keeps it that way.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSessionOutcomeTest {

    // `NSError.domain` and `NSErrorDomain` constants are both nullable in cinterop, so the helper
    // mirrors that rather than forcing a non-null the SDK does not promise.
    private fun nsError(domain: String?, code: Long): NSError =
        NSError.errorWithDomain(domain, code, userInfo = null)

    // ---- classifyUrlSessionCompletion ------------------------------------------------------------

    @Test
    fun `a 2xx with no transport error succeeds`() {
        val outcome = classifyUrlSessionCompletion("k-primary.heic", statusCode = 200L, error = null)
        val record = assertIs<TaskCompletion.Record>(outcome)
        assertEquals("k-primary.heic", record.key)
        assertTrue(record.success)
        assertEquals(null, record.error)
    }

    /**
     * The case that makes the status check load-bearing: a rejected upload completes with **no**
     * transport error, so without the 2xx test a 403 would be recorded as a successful upload and the
     * bytes would never be re-sent.
     */
    @Test
    fun `a rejected upload with no transport error is a failure carrying its status`() {
        val outcome = classifyUrlSessionCompletion("k-primary.heic", statusCode = 403L, error = null)
        val record = assertIs<TaskCompletion.Record>(outcome)
        assertTrue(!record.success)
        assertEquals(UploadError.Unknown("http:403"), record.error)
    }

    @Test
    fun `a transport error is reported over the status`() {
        // Bound non-null so the assertion cannot pass vacuously by comparing "null:…" to itself.
        val domain = checkNotNull(NSURLErrorDomain)
        val outcome = classifyUrlSessionCompletion(
            "k-primary.heic",
            statusCode = 0L,
            error = nsError(domain, NSURLErrorTimedOut),
        )
        val record = assertIs<TaskCompletion.Record>(outcome)
        assertTrue(!record.success)
        assertEquals(UploadError.Unknown("$domain:$NSURLErrorTimedOut"), record.error)
    }

    @Test
    fun `no response at all is not a success`() {
        val outcome = classifyUrlSessionCompletion("k-primary.heic", statusCode = 0L, error = null)
        val record = assertIs<TaskCompletion.Record>(outcome)
        assertTrue(!record.success)
        assertEquals(UploadError.Unknown("http:0"), record.error)
    }

    @Test
    fun `a task with no description maps to no ledger key rather than a silent drop`() {
        assertEquals(
            TaskCompletion.NoLedgerKey,
            classifyUrlSessionCompletion(taskDescription = null, statusCode = 200L, error = null),
        )
    }

    // ---- strandedKeys ----------------------------------------------------------------------------

    /**
     * The recovery rule for a transfer lost to process death: `REQUESTED` in the ledger, no live task,
     * no completion this round. Without it the row stays `REQUESTED` forever, the engine treats it as
     * in-flight and never re-issues it, and the photo is abandoned with no error anywhere.
     */
    @Test
    fun `a requested key with no live task and no completion is stranded`() {
        assertEquals(
            listOf("lost.heic"),
            strandedKeys(
                pending = setOf("lost.heic", "running.heic", "just-done.heic"),
                live = setOf("running.heic"),
                drained = setOf("just-done.heic"),
            ),
        )
    }

    @Test
    fun `nothing is stranded when every pending key is accounted for`() {
        assertEquals(
            emptyList<String>(),
            strandedKeys(
                pending = setOf("a.heic", "b.heic"),
                live = setOf("a.heic"),
                drained = setOf("b.heic"),
            ),
        )
        assertEquals(emptyList<String>(), strandedKeys(pending = emptySet(), live = setOf("a.heic"), drained = emptySet()))
    }

    @Test
    fun `a live or drained key that is not pending is never surfaced`() {
        assertEquals(
            emptyList<String>(),
            strandedKeys(pending = emptySet(), live = setOf("x.heic"), drained = setOf("y.heic")),
        )
    }
}
