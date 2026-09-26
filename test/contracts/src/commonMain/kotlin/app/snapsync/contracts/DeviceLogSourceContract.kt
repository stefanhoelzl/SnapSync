package app.snapsync.contracts

import app.snapsync.services.logs.LogTailService
import app.snapsync.services.logs.LogTailService.Process
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The states the two device logs can be found in, as far as a clause cares. Both processes alike. */
enum class DeviceLogSourceState {
    /** Neither log exists. */
    NO_LOG,

    /** Both logs exist and are empty. */
    EMPTY_LOG,

    /** Each process's log holds [DeviceLogSourceContract.seedLog] for that process and the clause being run. */
    HOLDING,

    /** Only the rolled `.1` sibling of each log exists, holding [DeviceLogSourceContract.seedLog]. */
    ROLLED_ONLY,
}

/**
 * What reading a device log's tail promises (`docs/architecture.md`; the port's KDoc carries why):
 * at most `maxBytes`, cut so the first line is whole, from the **current** file only, and `null` — never a
 * partial lie, never an empty string — when there is nothing to read.
 */
object DeviceLogSourceContract : Contract<DeviceLogSourceState, LogTailService>("LogTailService") {

    /** Budget comfortably above every seeded log. */
    const val WHOLE_BUDGET = 1_000_000

    /** Budget well inside every seeded log, and not on a line boundary of it. */
    const val SMALL_BUDGET = 100

    /**
     * The text a log holds for [process] and [clauseId]: ASCII (so bytes and chars agree), one entry per
     * line, newline-terminated, and several times [SMALL_BUDGET] long. Bindings seed exactly this.
     */
    fun seedLog(process: Process, clauseId: String): String =
        (1..LINES).joinToString(separator = "") { "${process.name} $clauseId line $it\n" }

    private const val LINES = 20

    override val clauses = clauses {

        clause("NO_LOG_TAIL_IS_NULL", DeviceLogSourceState.NO_LOG) { logs ->
            Process.entries.forEach { assertNull(logs.tail(it, WHOLE_BUDGET), "$it: no log reads as null") }
        }

        clause("EMPTY_LOG_TAIL_IS_NULL", DeviceLogSourceState.EMPTY_LOG) { logs ->
            Process.entries.forEach { assertNull(logs.tail(it, WHOLE_BUDGET), "$it: an empty log reads as null") }
        }

        clause("ROLLED_ONLY_TAIL_IS_NULL", DeviceLogSourceState.ROLLED_ONLY) { logs ->
            Process.entries.forEach {
                assertNull(logs.tail(it, WHOLE_BUDGET), "$it: a rolled sibling is stale and is never read")
            }
        }

        clause("HOLDING_WITHIN_BUDGET_IS_WHOLE", DeviceLogSourceState.HOLDING) { logs ->
            Process.entries.forEach {
                assertEquals(seedLog(it, "HOLDING_WITHIN_BUDGET_IS_WHOLE"), logs.tail(it, WHOLE_BUDGET))
            }
        }

        clause("HOLDING_TAIL_IS_BOUNDED_AND_LINE_ALIGNED", DeviceLogSourceState.HOLDING) { logs ->
            Process.entries.forEach {
                val log = seedLog(it, "HOLDING_TAIL_IS_BOUNDED_AND_LINE_ALIGNED")
                val tail = assertNotNull(logs.tail(it, SMALL_BUDGET), "$it: a log longer than the budget has a tail")
                assertTrue(tail.encodeToByteArray().size <= SMALL_BUDGET, "$it: the tail exceeds its budget")
                assertTrue(tail.isNotEmpty() && log.endsWith(tail), "$it: the tail is the end of the log")
                assertTrue(log.contains("\n$tail"), "$it: the tail must begin on a whole line: '$tail'")
            }
        }

        clause("HOLDING_READS_THE_PROCESS_ASKED_FOR", DeviceLogSourceState.HOLDING) { logs ->
            val app = logs.tail(Process.APP, WHOLE_BUDGET)
            val extension = logs.tail(Process.EXTENSION, WHOLE_BUDGET)
            assertEquals(seedLog(Process.APP, "HOLDING_READS_THE_PROCESS_ASKED_FOR"), app)
            assertEquals(seedLog(Process.EXTENSION, "HOLDING_READS_THE_PROCESS_ASKED_FOR"), extension)
            assertNotEquals(app, extension)
        }

        clause("HOLDING_NON_POSITIVE_BUDGET_IS_NULL", DeviceLogSourceState.HOLDING) { logs ->
            Process.entries.forEach { assertNull(logs.tail(it, 0), "$it: no budget, no tail") }
        }
    }
}
