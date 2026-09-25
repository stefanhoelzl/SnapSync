package app.snapsync.contracts

import app.snapsync.ports.ProtectedStorage
import kotlin.test.assertTrue

/** The states a [ProtectedStorage] can be found in, as far as a clause cares. */
enum class ProtectedStorageState {
    /** A running app on a device that has been unlocked since boot. */
    UNLOCKED,
}

/**
 * What the protected-storage read promises (`docs/architecture.md` — this list IS the port's
 * specification).
 *
 * One clause, and deliberately no `LOCKED` one: no host lets a binding enter "not unlocked since boot" — the
 * simulator implements no data protection, and the rig drives only a running, unlocked app (`docs/architecture.md`,
 * "Hosts are a closed set of what changes reachable states"). A clause only the in-memory double could reach
 * may not exist, so that belief lives in `IosProtectedStorage`'s documentation.
 */
object ProtectedStorageContract : Contract<ProtectedStorageState, ProtectedStorage>("ProtectedStorage") {

    override val clauses = clauses {

        clause("AN_UNLOCKED_DEVICE_READS_READABLE", ProtectedStorageState.UNLOCKED) { storage ->
            assertTrue(storage.readable())
        }
    }
}
