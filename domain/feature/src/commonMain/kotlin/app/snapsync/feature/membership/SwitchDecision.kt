package app.snapsync.feature.membership

/**
 * The provision-time transition **rule** (capabilities `join-event`, `join-event`): what provisioning
 * [next][switchDecision] means for the membership this device already holds.
 *
 * - provisioning a *different* event while joined is a **switch**: the previous membership is left first
 *   (uploads stopped, then the best-effort backend leave), and the new one loads its share set;
 * - provisioning while unjoined is a **join**: the new membership loads its share set;
 * - re-provisioning the joined event is **not a transition**: nothing is stopped, left or loaded.
 *
 * The third answer is what keeps a re-provision from resetting a live membership's upload ledger — its
 * `DISCOVERED`/`REQUESTED` rows are this membership's work in flight, and nothing stopped it (capability
 * `photo-sharing`, "A join loads the ledger from the per-device listing").
 *
 * The rule moved here from the Provision flow's guard at the migration finale: the flow switches on this
 * sealed answer (the transcriber grammar's sealed-result form) and fires the compose-built effects —
 * coordination; whether a transition is due is membership's decision.
 */
sealed interface SwitchDecision {

    /**
     * A new membership is being entered — a switch or a first join — so the upload ledger becomes its share
     * set; [previousEventId] is the membership to leave first, or `null` when there is none.
     */
    sealed interface Enter : SwitchDecision {
        val previousEventId: String?
    }

    /** A different event is being provisioned: leave [previousEventId] first, then load the new share set. */
    data class LeavePrevious(override val previousEventId: String) : Enter

    /** No membership yet: nothing to leave; load the new share set. */
    data object Join : Enter {
        override val previousEventId: String? = null
    }

    /** A re-provision of the joined event: nothing to leave and nothing to load. */
    data object Stay : SwitchDecision
}

/** Decide what provisioning [next] means while [current] is joined (`null` when unjoined). */
fun switchDecision(current: String?, next: String): SwitchDecision = when (current) {
    null -> SwitchDecision.Join
    next -> SwitchDecision.Stay
    else -> SwitchDecision.LeavePrevious(current)
}
