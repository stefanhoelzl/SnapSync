package app.snapsync.contracts

import app.snapsync.model.Availability
import app.snapsync.ports.ProcessInfo
import kotlin.test.assertEquals

/** The states a [ProcessInfo] can be found in, as far as a clause cares. */
enum class ProcessInfoState {
    /** A running app on a device that has been unlocked since boot. */
    UNLOCKED,
}

/**
 * What the process read promises (`docs/architecture.md` — this list IS the port's specification).
 *
 * One clause, and deliberately no `LOCKED` one: no host lets a binding enter "not unlocked since boot" — the
 * simulator implements no data protection, and the rig drives only a running, unlocked app (`docs/architecture.md`,
 * "Hosts are a closed set of what changes reachable states"). A clause only the in-memory double could reach
 * may not exist, so that belief lives in `IosProcessInfo`'s documentation.
 */
object ProcessInfoContract : Contract<ProcessInfoState, ProcessInfo>("ProcessInfo") {

    override val clauses = clauses {

        clause("AN_UNLOCKED_DEVICE_READS_AVAILABLE", ProcessInfoState.UNLOCKED) { process ->
            assertEquals(Availability.AVAILABLE, process.protectedDataAvailable())
        }
    }
}
