package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [CycleResult] cases, pinned where every build compiles them (capability `background-upload`).
 *
 * Each case has a platform answer: on iOS, `:adapter:ios:ext-safe`'s exhaustive raw-value mapping, whose own test
 * runs only on the simulator. A case added here without that mapping would fail only the iOS compile; this pin fails
 * the Linux build first, naming where the answer has to be taught.
 */
class CycleResultCasesTest {

    private fun name(result: CycleResult): String = when (result) {
        CycleResult.COMPLETED -> "completed"
        CycleResult.PROCESSING -> "processing"
        CycleResult.FAILED -> "failed"
        CycleResult.SKIPPED -> "skipped"
        is CycleResult.Paused -> "paused"
    }

    @Test
    fun `the cycle answers five kinds of result and the extension adapter maps each`() {
        val all = listOf(
            CycleResult.COMPLETED,
            CycleResult.PROCESSING,
            CycleResult.FAILED,
            CycleResult.SKIPPED,
            CycleResult.Paused(PauseReason.OLD_SCHEMA),
        )
        assertEquals(
            listOf("completed", "processing", "failed", "skipped", "paused"),
            all.map(::name),
            "a new CycleResult case needs its platform answer: `processingResultRawValue` in :adapter:ios:ext-safe",
        )
    }
}
