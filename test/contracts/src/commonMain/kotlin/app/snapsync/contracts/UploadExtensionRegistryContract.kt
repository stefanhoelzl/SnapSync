package app.snapsync.contracts

import app.snapsync.model.RegistrationOutcome
import app.snapsync.ports.UploadExtensionRegistry
import kotlin.test.assertEquals

/**
 * What the operating system holds for this app's upload-extension registration when a clause starts.
 *
 * The record is OS state keyed by bundle id that survives reinstall and reboot, so a binding enters
 * [RECORD_ABSENT] and [RECORD_PRESENT] through the port's own write before the clause — under a full grant,
 * the only grant under which the write is allowed. Under a partial grant the write is refused in both
 * directions, so the record cannot be entered at all: [UNDER_PARTIAL_GRANT] says only which grant the process
 * holds, which is the whole of what those clauses depend on.
 */
enum class UploadExtensionRegistryState {
    /** Full grant, no configuration record. */
    RECORD_ABSENT,

    /** Full grant, a configuration record exists. */
    RECORD_PRESENT,

    /** A partial (`.limited`) photo grant, whatever record exists. */
    UNDER_PARTIAL_GRANT,
}

/**
 * What every [UploadExtensionRegistry] promises (capability `port-contracts` — this list IS the specification
 * of the port's obligations): a write reports what the platform did, classified by `registrationOutcome`, and a
 * successful write is what the OS then reads back.
 *
 * The expected answers are the measured ones `RegistrationOutcome` documents — a disable with no record is
 * `3201`, and a partial grant refuses both directions with `3311` (SE2, iOS 26.6). This contract makes them
 * evidence rather than prose: the device's recording holds iOS's answer, and every CI build replays it against
 * the current adapter. The read-back is asserted only under a full grant, because it is grant-dependent (it
 * answers `false` for a live record without one — `UploadExtensionRegistry.isEnabled`).
 */
object UploadExtensionRegistryContract :
    Contract<UploadExtensionRegistryState, UploadExtensionRegistry>("UploadExtensionRegistry") {

    override val clauses = clauses {

        clause("ENABLE_CREATES_A_RECORD", UploadExtensionRegistryState.RECORD_ABSENT) { registry ->
            assertEquals(RegistrationOutcome.Applied(enabling = true), registry.setEnabled(true))
            assertEquals(true, registry.isEnabled(), "the OS reads back the record an applied enable created")
        }

        clause("DISABLE_REMOVES_A_RECORD", UploadExtensionRegistryState.RECORD_PRESENT) { registry ->
            assertEquals(RegistrationOutcome.Applied(enabling = false), registry.setEnabled(false))
            assertEquals(false, registry.isEnabled(), "the OS reads back no record after an applied disable")
        }

        clause("DISABLE_WITH_NO_RECORD_IS_EXPECTED", UploadExtensionRegistryState.RECORD_ABSENT) { registry ->
            assertEquals(
                RegistrationOutcome.NothingToDisable,
                registry.setEnabled(false),
                "a disable that finds no record is the clean device's answer (3201), never a failure",
            )
        }

        clause("ENABLE_IS_REFUSED_UNDER_A_PARTIAL_GRANT", UploadExtensionRegistryState.UNDER_PARTIAL_GRANT) { registry ->
            assertEquals(RegistrationOutcome.EnableRefusedByGrant, registry.setEnabled(true))
        }

        clause("DISABLE_IS_REFUSED_UNDER_A_PARTIAL_GRANT", UploadExtensionRegistryState.UNDER_PARTIAL_GRANT) { registry ->
            assertEquals(RegistrationOutcome.DisableRefusedByGrant, registry.setEnabled(false))
        }
    }
}
