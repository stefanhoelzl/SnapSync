package app.snapsync.extension

import app.snapsync.model.ProcessingAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `PHBackgroundResourceUploadProcessingResult` raw values (capability `background-upload`). The system type is
 * Swift-only, so the Swift shell constructs it via `init?(rawValue:)` from exactly these integers — pinning them here
 * is what keeps the Swift side a transcriber. Derived from the SDK swiftinterface's case order (failure, processing,
 * completed), verified on device.
 */
class ProcessingAnswerRawValueTest {

    @Test
    fun `completed maps to the completed raw value`() {
        assertEquals(2, ProcessingAnswer.COMPLETED.processingResultRawValue())
    }

    @Test
    fun `processing maps to the processing raw value`() {
        assertEquals(1, ProcessingAnswer.PROCESSING.processingResultRawValue())
    }

    @Test
    fun `failed maps to the failure raw value`() {
        assertEquals(0, ProcessingAnswer.FAILED.processingResultRawValue())
    }
}
