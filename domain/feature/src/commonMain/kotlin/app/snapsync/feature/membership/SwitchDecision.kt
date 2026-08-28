package app.snapsync.feature.membership

/**
 * The provision-time switch **rule** (capabilities `event-link`, `join-event`): provisioning a
 * *different* event while joined leaves the previous one on the backend first; re-provisioning the
 * same event — or provisioning while unjoined — is not a switch and fires no leave. The rule moved
 * here from the Provision flow's guard at the migration finale: the flow switches on this sealed
 * answer (the transcriber grammar's sealed-result form) and fires the compose-built leave effect on
 * [LeavePrevious] — coordination; whether a leave is due is membership's decision.
 */
sealed interface SwitchDecision {

    /** A different event is being provisioned: leave [previousEventId] (best-effort) first. */
    data class LeavePrevious(val previousEventId: String) : SwitchDecision

    /** First join, or a re-provision of the same event — no leave fires. */
    data object Stay : SwitchDecision
}

/** Decide whether provisioning [next] while [current] is joined constitutes a switch. */
fun switchDecision(current: String?, next: String): SwitchDecision =
    if (current != null && current != next) SwitchDecision.LeavePrevious(current) else SwitchDecision.Stay
