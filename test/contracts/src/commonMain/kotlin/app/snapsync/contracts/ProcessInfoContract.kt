package app.snapsync.contracts

import app.snapsync.ports.ProcessInfo
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The states a [ProcessInfo] can be found in, as far as a clause cares. */
enum class ProcessInfoState {
    /**
     * A running process whose platform accounts its memory to it — iOS. Not Android: nothing there consumes a
     * footprint, so its binding answers none and never reaches this state.
     */
    MEMORY_ACCOUNTED,

    /** A running process whose platform's footprint is not read — Android, where nothing consumes one. */
    MEMORY_NOT_ACCOUNTED,
}

/** What the process read promises (`docs/architecture.md` — this list IS the port's specification). */
object ProcessInfoContract : Contract<ProcessInfoState, ProcessInfo>("ProcessInfo") {

    override val clauses = clauses {

        clause(
            "A_PROCESS_NOT_ACCOUNTED_READS_NO_FOOTPRINT",
            ProcessInfoState.MEMORY_NOT_ACCOUNTED,
            covers = cells {
                on<ProcessInfo>().answers(ProcessInfo::memoryFootprint).with(null)
            },
        ) { process ->
            assertNull(
                process.memoryFootprint(),
                "where the platform's accounting is not read, no footprint is invented",
            )
        }

        clause(
            "A_RUNNING_PROCESS_READS_ITS_OWN_FOOTPRINT",
            ProcessInfoState.MEMORY_ACCOUNTED,
            covers = cells {
                on<ProcessInfo>().answers(ProcessInfo::memoryFootprint).returns()
            },
        ) { process ->
            val read = assertNotNull(process.memoryFootprint(), "a process the platform accounts reads a footprint")
            assertTrue(read.footprintBytes > 0, "a running process occupies memory: $read")
            read.peakBytes?.let { assertTrue(it >= read.footprintBytes, "the peak is never below the present: $read") }
            read.headroomBytes?.let { assertTrue(it > 0, "a running process is still under its limit: $read") }
        }
    }
}
