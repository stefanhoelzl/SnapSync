package app.snapsync.application.mobiledata

import app.snapsync.application.EntryPointCall
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("SetMobileData")

/**
 * Set the device's mobile-data choice, applied as it is switched: it governs every photo transfer that starts after
 * it. One that cannot be saved changes nothing, and answers the choice still in effect — so the
 * switch returns to what really governs the transfers.
 */
class SetMobileData(private val store: MobileDataStore) {
    @EntryPointCall
    suspend operator fun invoke(
        choice: MobileDataChoice,
    ): MobileDataOutcome = when (val written = store.write(choice)) {
        StoreWrite.Written -> MobileDataOutcome.Saved(choice)
        is StoreWrite.Failed -> MobileDataOutcome.NotSaved(store.read()).also {
            log.w("mobile data choice not saved", "choice" of choice, "detail" of written.detail)
        }
    }
}
