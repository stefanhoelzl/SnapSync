package app.snapsync.services.upload

import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadFailure
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.TransferFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two platform answers the cycle reads in its own words. */
class UploadAnswersTest {

    @Test
    fun `every platform answer reads as the same answer to the cycle - a failure in the same words`() {
        assertEquals(
            listOf(
                UploadCreateOutcome.CREATED,
                UploadCreateOutcome.LIMIT_EXCEEDED,
                UploadCreateOutcome.FAILED,
            ),
            CreateOutcome.entries.map { it.uploadCreateOutcome() },
        )
        assertEquals(UploadFailure("http:500"), TransferFailure("http:500").uploadFailure())
    }
}
