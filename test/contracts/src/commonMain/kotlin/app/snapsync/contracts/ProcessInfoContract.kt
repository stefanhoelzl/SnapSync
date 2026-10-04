package app.snapsync.contracts

import app.snapsync.model.Availability
import app.snapsync.ports.ProcessInfo
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The states a [ProcessInfo] can be found in, as far as a clause cares. */
enum class ProcessInfoState {
    /** A running app on a device that has been unlocked since boot. */
    UNLOCKED,

    /**
     * A running process whose platform accounts its memory to it — iOS. Not Android: nothing there consumes a
     * footprint, so its binding answers none and never reaches this state.
     */
    MEMORY_ACCOUNTED,
}

/**
 * What the process read promises (`docs/architecture.md` — this list IS the port's specification).
 *
 * Deliberately no `LOCKED` clause: no host lets a binding enter "not unlocked since boot" — the
 * simulator implements no data protection, and the rig drives only a running, unlocked app (`docs/architecture.md`,
 * "Hosts are a closed set of what changes reachable states"). A clause only the in-memory double could reach
 * may not exist, so that belief lives in `IosProcessInfo`'s documentation.
 */
object ProcessInfoContract : Contract<ProcessInfoState, ProcessInfo>("ProcessInfo") {

    override val clauses = clauses {

        clause("AN_UNLOCKED_DEVICE_READS_AVAILABLE", ProcessInfoState.UNLOCKED) { process ->
            assertEquals(Availability.AVAILABLE, process.protectedDataAvailable())
        }

        clause("A_RUNNING_PROCESS_READS_ITS_OWN_FOOTPRINT", ProcessInfoState.MEMORY_ACCOUNTED) { process ->
            val read = assertNotNull(process.memoryFootprint(), "a process the platform accounts reads a footprint")
            assertTrue(read.footprintBytes > 0, "a running process occupies memory: $read")
            read.peakBytes?.let { assertTrue(it >= read.footprintBytes, "the peak is never below the present: $read") }
            read.headroomBytes?.let { assertTrue(it > 0, "a running process is still under its limit: $read") }
        }
    }
}
