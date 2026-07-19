package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `PHBackgroundResourceUploadProcessingResult` raw values (capability `ios-photokit-upload`;
 * settled forcing proof ① of migration step 12). The system type is Swift-only, so the Swift shell
 * constructs it via `init?(rawValue:)` from exactly these integers — pinning them here is what makes
 * "Kotlin decides, Swift constructs" a tested decision rather than an untestable Swift `switch`.
 * Derived from the SDK swiftinterface's case order (failure, processing, completed); Session D
 * verifies them on device.
 */
class CycleResultRawValueTest {

    @Test
    fun `completed maps to the completed raw value`() {
        assertEquals(2, CycleResult.COMPLETED.processingResultRawValue())
    }

    @Test
    fun `skipped rests like completed`() {
        // Nothing to do (no membership / membership contributes nothing) — the system rests.
        assertEquals(2, CycleResult.SKIPPED.processingResultRawValue())
    }

    @Test
    fun `processing maps to the processing raw value`() {
        assertEquals(1, CycleResult.PROCESSING.processingResultRawValue())
    }

    @Test
    fun `failed maps to the failure raw value`() {
        assertEquals(0, CycleResult.FAILED.processingResultRawValue())
    }
}
