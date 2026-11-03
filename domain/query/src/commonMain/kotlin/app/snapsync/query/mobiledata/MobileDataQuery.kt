package app.snapsync.query.mobiledata

import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.query.RequestStatus
import app.snapsync.query.RequestStatuses
import kotlinx.coroutines.flow.StateFlow

/** The member's changes of the mobile-data choice, each answered on this status under its request's id. */
class MobileDataStatuses {
    val change = RequestStatuses<MobileDataOutcome>()
}

/** The menu's read of the mobile-data choice: what is in effect now, and how a change the member made came out. */
interface MobileDataQuery {
    /** The choice in effect, read now. */
    fun current(): MobileDataChoice

    /** A change's answer, under its request's id. */
    val changes: StateFlow<RequestStatus<MobileDataOutcome>>
}

/** [MobileDataQuery] over the device's store and the changes' status. */
class MobileDataLookup(private val store: MobileDataStore, statuses: MobileDataStatuses) : MobileDataQuery {
    override fun current(): MobileDataChoice = store.read()

    override val changes: StateFlow<RequestStatus<MobileDataOutcome>> = statuses.change.status
}
