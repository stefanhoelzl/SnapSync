package app.snapsync.domain.service

import app.snapsync.domain.trust.BackendAnswer

/**
 * How SnapSync's service failed, as a member is told it — three conditions, each with its own words, because each
 * asks something different of the member (decision record `changes/tell-service-faults`).
 */
enum class ServiceFault {
    /**
     * No answer came back, or one the app does not understand — a Wi-Fi login page, a proxy: the only one whose remedy
     * may be the member's own connection.
     */
    UNREACHABLE,

    /** The service answered that it failed. */
    TROUBLE,

    /** The service answered that it is briefly unavailable for an update. */
    UPDATING,
    ;

    companion object {
        /** The condition [answer] tells of, or `null` for an answer from a working service — a refusal included. */
        fun of(answer: BackendAnswer): ServiceFault? = when (answer) {
            BackendAnswer.NoAnswer -> UNREACHABLE
            BackendAnswer.ServerIssue -> TROUBLE
            BackendAnswer.Updating -> UPDATING
            BackendAnswer.Served,
            BackendAnswer.Rejected,
            BackendAnswer.Refused,
            is BackendAnswer.TooOld,
            -> null
        }
    }
}
