package app.snapsync.model

import app.snapsync.domain.service.ServiceFault

/**
 * What a join request answered.
 *
 * The refusals are kept **apart**, because their consequences differ and a caller must be able to act on
 * that (`docs/architecture.md`, "Absence is never silent"): [EventClosed] is a refusal the user can act on
 * and a screen can explain, [EventNotFound] means the event is gone, and [Failed] is a failure of the service
 * that a retry may heal — carrying how it failed, as the user is told it. Collapsing them into one boolean is what
 * made a refusal and "the network blipped" the same sentence on the join surface. [Unverified] is a `401` the
 * authenticated backend's recovery did not heal: this phone holds no credential the service accepts, and the
 * attestation's verdict says why.
 */
sealed interface JoinResult {
    data object Joined : JoinResult

    data object EventClosed : JoinResult

    data object EventNotFound : JoinResult

    data object Unverified : JoinResult

    data class Failed(val fault: ServiceFault = ServiceFault.UNREACHABLE) : JoinResult
}
