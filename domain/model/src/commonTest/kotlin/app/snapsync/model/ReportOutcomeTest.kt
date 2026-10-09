package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the user is told of a confirmed report (capability `privacy-security`): one word per way a hand-off ends. */
class ReportOutcomeTest {

    @Test
    fun each_dump_result_is_told_as_what_happened_and_no_more() {
        assertEquals(ReportOutcome.SENT, DumpResult.Queued.outcome)
        assertEquals(ReportOutcome.SAVED, DumpResult.Saved("diagnostic-report.json").outcome)
        assertEquals(ReportOutcome.NOT_SENT, DumpResult.NotSent("the channel is not running").outcome)
    }

    @Test
    fun an_unknown_build_says_so_rather_than_naming_a_number_nobody_built() {
        assertEquals(BuildLabel("unknown", "unknown"), BuildLabel.UNKNOWN)
    }
}
